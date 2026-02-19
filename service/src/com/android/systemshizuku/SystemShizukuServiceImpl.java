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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;

import com.android.systemshizuku.broadcast.ShizukuBroadcasts;
import com.android.systemshizuku.store.PermissionStore;
import com.android.systemshizuku.ui.PermissionConsentActivity;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full implementation of the public app-facing Binder service.
 *
 * <p>
 * Service name: {@code "system_shizuku"}
 */
public class SystemShizukuServiceImpl extends ISystemShizukuService.Stub {

    private static final String TAG = "SystemShizuku";

    /** Protocol version — increment when incompatible AIDL changes land. */
    private static final int PROTOCOL_VERSION = 1;

    /** Rate-limit: max pending dialog requests per package at a time. */
    private static final int MAX_PENDING_REQUESTS = 3;

    private final Context mContext;
    private final PermissionStore mStore;

    // key = "packageName:userId", value = pending callbacks queue size
    private final ConcurrentHashMap<String, Integer> mPendingCount = new ConcurrentHashMap<>();

    // Active session tokens: key = sessionToken Binder identity hash,
    // value = (packageName + ":" + userId)
    private final ConcurrentHashMap<Integer, String> mSessions = new ConcurrentHashMap<>();

    public SystemShizukuServiceImpl(Context context) {
        mContext = context;
        mStore = new PermissionStore(context);
    }

    // -----------------------------------------------------------------------
    // ISystemShizukuService
    // -----------------------------------------------------------------------

    @Override
    public int ping() {
        return PROTOCOL_VERSION;
    }

    @Override
    public void requestPermission(
            String packageName, int userId, ISystemShizukuCallback callback) {
        enforceCallerOwnsPackage(packageName, userId);

        // -- 1. Check existing valid grant ----------------------------------
        ShizukuPermission existing = mStore.getGrant(packageName, userId);
        if (existing != null && existing.granted) {
            // Auto-expire check
            if (existing.expiresAt > 0
                    && System.currentTimeMillis() > existing.expiresAt) {
                mStore.revokeGrant(packageName, userId);
                // Fall through to show dialog
            } else {
                Log.d(TAG, "requestPermission: existing valid grant for " + packageName);
                IBinder token = issueSessionToken(packageName, userId);
                try {
                    callback.onGranted(existing, token);
                } catch (RemoteException e) {
                    Log.w(TAG, "onGranted delivery failed", e);
                }
                return;
            }
        }

        // -- 2. Permanently denied? ----------------------------------------
        if (existing != null
                && (existing.flags & PermissionStore.FLAG_REVOKED_BY_USER) != 0) {
            Log.d(TAG, "requestPermission: permanently denied for " + packageName);
            try {
                callback.onDenied(packageName, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "onDenied delivery failed", e);
            }
            return;
        }

        // -- 3. Rate limiting -----------------------------------------------
        String key = packageName + ":" + userId;
        int pending = mPendingCount.merge(key, 1, Integer::sum);
        if (pending > MAX_PENDING_REQUESTS) {
            mPendingCount.merge(key, -1, Integer::sum);
            throw new SecurityException(
                    "Too many pending permission requests for " + packageName);
        }

        // -- 4. Show consent dialog -----------------------------------------
        Intent intent = new Intent(mContext, PermissionConsentActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        intent.putExtra(PermissionConsentActivity.EXTRA_PACKAGE_NAME, packageName);
        intent.putExtra(PermissionConsentActivity.EXTRA_APP_ID,
                Binder.getCallingUid() % UserHandle.PER_USER_RANGE);
        intent.putExtra(PermissionConsentActivity.EXTRA_USER_ID, userId);
        intent.putExtra(PermissionConsentActivity.EXTRA_CALLBACK,
                wrapCallback(callback, key));

        long identity = Binder.clearCallingIdentity();
        try {
            mContext.startActivityAsUser(intent, UserHandle.of(userId));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public ShizukuPermission getMyPermission(String packageName, int userId) {
        enforceCallerOwnsPackage(packageName, userId);
        return mStore.getGrant(packageName, userId);
    }

    @Override
    public void attachSession(IBinder sessionToken) {
        int callingUid = Binder.getCallingUid();
        int tokenId = System.identityHashCode(sessionToken);
        String entry = mSessions.get(tokenId);

        if (entry == null) {
            throw new SecurityException(
                    "sessionToken not issued to uid " + callingUid);
        }
        // RegisterDeathRecipient so we can clean up when app process dies
        try {
            sessionToken.linkToDeath(() -> onSessionDied(tokenId, entry), 0);
            Log.d(TAG, "attachSession: uid=" + callingUid + " key=" + entry);
        } catch (RemoteException e) {
            // Token already dead
            onSessionDied(tokenId, entry);
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Issues a new session-scoped IBinder token and registers it in
     * {@link #mSessions}.
     */
    private IBinder issueSessionToken(String packageName, int userId) {
        IBinder token = new Binder();
        mSessions.put(System.identityHashCode(token),
                packageName + ":" + userId);
        return token;
    }

    /**
     * Wraps the caller's callback to decrement the pending count when the
     * dialog resolves, and to issue a token on grant.
     */
    private IBinder wrapCallback(ISystemShizukuCallback original, String key) {
        ISystemShizukuCallback wrapper = new ISystemShizukuCallback.Stub() {
            @Override
            public void onGranted(ShizukuPermission permission, IBinder sessionToken) {
                mPendingCount.merge(key, -1, Integer::sum);
                IBinder myToken = issueSessionToken(
                        permission.packageName, permission.userId);
                try {
                    original.onGranted(permission, myToken);
                } catch (RemoteException e) {
                    Log.w(TAG, "Wrapped onGranted failed", e);
                }
            }

            @Override
            public void onDenied(String packageName, int userId) {
                mPendingCount.merge(key, -1, Integer::sum);
                try {
                    original.onDenied(packageName, userId);
                } catch (RemoteException e) {
                    Log.w(TAG, "Wrapped onDenied failed", e);
                }
            }
        };
        return wrapper.asBinder();
    }

    /** Called when a session token's process dies. */
    private void onSessionDied(int tokenId, String key) {
        mSessions.remove(tokenId);
        String[] parts = key.split(":");
        if (parts.length != 2)
            return;
        String packageName = parts[0];
        int userId;
        try {
            userId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return;
        }
        // Revoke if SESSION_ONLY
        ShizukuPermission p = mStore.getGrant(packageName, userId);
        if (p != null && (p.flags & PermissionStore.FLAG_GRANT_SESSION_ONLY) != 0) {
            Log.i(TAG, "Session died — revoking SESSION_ONLY grant: " + packageName);
            mStore.revokeGrant(packageName, userId);
            ShizukuBroadcasts.sendPermissionChanged(
                    mContext, packageName, userId, false);
        }
    }

    /**
     * Verifies the calling uid owns {@code packageName} in {@code userId}.
     *
     * @throws SecurityException on mismatch
     */
    private void enforceCallerOwnsPackage(String packageName, int userId) {
        int callingUid = Binder.getCallingUid();
        PackageManager pm = mContext.getPackageManager();
        int expectedUid;
        try {
            expectedUid = pm.getPackageUidAsUser(packageName,
                    PackageManager.MATCH_ALL, userId);
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException(
                    "Package " + packageName + " not found for user " + userId);
        }
        if (callingUid != expectedUid) {
            throw new SecurityException(
                    "Caller uid " + callingUid + " does not own "
                            + packageName + " in user " + userId
                            + " (expected " + expectedUid + ")");
        }
    }
}
