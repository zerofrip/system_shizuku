/*
 * Copyright (C) 2026 The system_shizuku Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemshizuku;

import android.content.Context;
import android.os.Binder;
import android.os.UserHandle;
import android.util.Log;

import com.android.systemshizuku.broadcast.ShizukuBroadcasts;
import com.android.systemshizuku.store.PermissionStore;

import java.util.List;

/**
 * Full implementation of the system-only management Binder service.
 *
 * <p>
 * Service name: {@code "system_shizuku_mgr"}
 */
public class SystemShizukuManagerImpl extends ISystemShizukuManager.Stub {

    private static final String TAG = "SystemShizukuMgr";

    private final Context mContext;
    private final PermissionStore mStore;

    public SystemShizukuManagerImpl(Context context, PermissionStore store) {
        mContext = context;
        mStore = store;
    }

    // -----------------------------------------------------------------------
    // ISystemShizukuManager
    // -----------------------------------------------------------------------

    @Override
    public List<ShizukuPermission> getGrantedPackages(int userId) {
        enforceManagePermission();
        if (userId == UserHandle.USER_ALL) {
            // USER_ALL requires INTERACT_ACROSS_USERS_FULL in addition to
            // MANAGE_SYSTEM_SHIZUKU; enforce it here.
            mContext.enforceCallingPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "USER_ALL requires INTERACT_ACROSS_USERS_FULL");
            // Aggregate across all users — for now, defer to the caller
            // passing explicit user IDs. Returning empty list for USER_ALL
            // is intentional until multi-user iteration is implemented.
            return java.util.Collections.emptyList();
        }
        return mStore.getGrants(userId);
    }

    @Override
    public ShizukuPermission getPermission(String packageName, int userId) {
        enforceManagePermission();
        return mStore.getGrant(packageName, userId);
    }

    @Override
    public void revokePermission(String packageName, int userId) {
        enforceManagePermission();
        int callerUid = Binder.getCallingUid();
        Log.i(TAG, "revokePermission: pkg=" + packageName
                + " user=" + userId + " callerUid=" + callerUid);

        ShizukuPermission revoked = mStore.revokeGrant(packageName, userId);
        if (revoked == null) {
            // No record — no-op
            return;
        }

        // Audit entry
        appendAudit(packageName, revoked.appId, userId,
                PermissionStore.EVENT_REVOKE, "callerUid=" + callerUid);

        // Broadcast to the affected app
        ShizukuBroadcasts.sendPermissionChanged(
                mContext, packageName, userId, false);
    }

    @Override
    public void revokeAllPermissions(int userId) {
        enforceManagePermission();
        int callerUid = Binder.getCallingUid();
        Log.i(TAG, "revokeAllPermissions: user=" + userId
                + " callerUid=" + callerUid);

        List<ShizukuPermission> revoked = mStore.revokeAll(userId);

        // Batch audit + broadcast
        for (ShizukuPermission p : revoked) {
            appendAudit(p.packageName, p.appId, userId,
                    PermissionStore.EVENT_REVOKE,
                    "bulk; callerUid=" + callerUid);
            ShizukuBroadcasts.sendPermissionChanged(
                    mContext, p.packageName, userId, false);
        }
    }

    @Override
    public List<ShizukuAuditEvent> getAuditLog(String packageName, int userId) {
        enforceManagePermission();
        return mStore.getAudit(packageName, userId);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Asserts the caller holds {@code android.permission.MANAGE_SYSTEM_SHIZUKU}.
     * Must be the first call in every public method.
     */
    private void enforceManagePermission() {
        mContext.enforceCallingPermission(
                "android.permission.MANAGE_SYSTEM_SHIZUKU",
                "Caller must hold android.permission.MANAGE_SYSTEM_SHIZUKU");
    }

    /** Convenience: build and append a {@link ShizukuAuditEvent}. */
    private void appendAudit(
            String packageName, int appId, int userId, int eventType, String detail) {
        ShizukuAuditEvent ev = new ShizukuAuditEvent();
        ev.version = 1;
        ev.eventType = eventType;
        ev.packageName = packageName;
        ev.appId = appId;
        ev.userId = userId;
        ev.eventAt = System.currentTimeMillis();
        ev.detail = detail;
        mStore.appendAudit(ev);
    }
}
