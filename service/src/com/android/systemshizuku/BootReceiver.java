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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.util.Log;

import com.android.systemshizuku.store.PermissionStore;

/**
 * Handles system lifecycle events relevant to system_shizuku.
 *
 * <p>
 * Registered events (declared in AndroidManifest.xml):
 * <ul>
 * <li>{@link Intent#ACTION_LOCKED_BOOT_COMPLETED} — re-validate session
 * tokens after direct-boot phase (credential storage available).
 * <li>{@link Intent#ACTION_BOOT_COMPLETED} — full-user unlock; session-only
 * grants from the previous boot are cleaned up.
 * <li>{@link Intent#ACTION_USER_REMOVED} — purge all grant records and
 * audit log for the removed user.
 * <li>{@link Intent#ACTION_PACKAGE_REMOVED} — revoke and remove the grant
 * record of uninstalled packages.
 * </ul>
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "ShizukuBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null)
            return;

        switch (intent.getAction()) {
            case Intent.ACTION_LOCKED_BOOT_COMPLETED:
                onLockedBootCompleted(context, intent);
                break;
            case Intent.ACTION_BOOT_COMPLETED:
                onBootCompleted(context, intent);
                break;
            case Intent.ACTION_USER_REMOVED:
                onUserRemoved(context, intent);
                break;
            case Intent.ACTION_PACKAGE_REMOVED:
                onPackageRemoved(context, intent);
                break;
            default:
                Log.w(TAG, "Unhandled action: " + intent.getAction());
        }
    }

    // -----------------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------------

    /**
     * Direct-boot complete — credential-encrypted storage is available.
     * Log that the service is ready; actual grant loading deferred until
     * {@link #onBootCompleted}.
     */
    private void onLockedBootCompleted(Context context, Intent intent) {
        int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                UserHandle.USER_SYSTEM);
        Log.i(TAG, "LOCKED_BOOT_COMPLETED for user " + userId);
        // No store operations here; EncryptedFile requires credential storage
        // which is available after ACTION_USER_UNLOCKED, not
        // ACTION_LOCKED_BOOT_COMPLETED.
    }

    /**
     * Full boot complete — purge any session-only grants that survived an
     * unclean shutdown. Under normal operation the service's DeathRecipient
     * handles this, but on a crash/power-loss session-only grants may be
     * left in the store.
     */
    private void onBootCompleted(Context context, Intent intent) {
        int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                UserHandle.USER_SYSTEM);
        Log.i(TAG, "BOOT_COMPLETED for user " + userId + " — cleaning session grants");

        PermissionStore store = new PermissionStore(context);

        // Revoke any SESSION_ONLY grants left over from the previous boot.
        for (var p : store.getGrants(userId)) {
            if ((p.flags & PermissionStore.FLAG_GRANT_SESSION_ONLY) != 0 && p.granted) {
                Log.d(TAG, "Cleaning session-only grant: " + p.packageName);
                store.revokeGrant(p.packageName, userId);
            }
        }

        // Auto-expire any time-limited grants that expired while the device was off.
        long now = System.currentTimeMillis();
        for (var p : store.getGrants(userId)) {
            if (p.granted && p.expiresAt > 0 && now > p.expiresAt) {
                Log.d(TAG, "Auto-expiring grant: " + p.packageName);
                store.revokeGrant(p.packageName, userId);
            }
        }
    }

    /**
     * User removed — delete all data for that user so no stale records remain.
     */
    private void onUserRemoved(Context context, Intent intent) {
        int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                UserHandle.USER_NULL);
        if (userId == UserHandle.USER_NULL) {
            Log.w(TAG, "USER_REMOVED intent missing EXTRA_USER_HANDLE");
            return;
        }
        Log.i(TAG, "USER_REMOVED: purging all data for user " + userId);
        new PermissionStore(context).deleteUser(userId);
    }

    /**
     * Package uninstalled — revoke its grant so Settings doesn't show stale
     * entries. The audit log is intentionally preserved for forensic purposes.
     */
    private void onPackageRemoved(Context context, Intent intent) {
        // Ignore package replacements (updates)
        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
        if (replacing)
            return;

        String packageName = intent.getData() != null
                ? intent.getData().getSchemeSpecificPart()
                : null;
        if (packageName == null)
            return;

        int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                UserHandle.USER_SYSTEM);

        Log.i(TAG, "PACKAGE_REMOVED: revoking grant for "
                + packageName + " u" + userId);
        new PermissionStore(context).revokeGrant(packageName, userId);
    }
}
