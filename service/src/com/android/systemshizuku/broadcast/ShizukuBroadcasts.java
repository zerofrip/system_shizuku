/*
 * Copyright (C) 2026 The system_shizuku Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemshizuku.broadcast;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.util.Log;

/**
 * Central helper for broadcasting system_shizuku events.
 *
 * <p>
 * All broadcasts are sent as protected broadcasts
 * ({@code android:protectionLevel="signature"} in AndroidManifest.xml) so
 * only platform-signed or system receivers can listen.
 *
 * <h3>Public constants</h3>
 * These constants should also be declared in a companion client library
 * (e.g. {@code system_shizuku_aidl_public}) so app clients can register
 * receivers without hardcoding the strings.
 */
public final class ShizukuBroadcasts {

    private static final String TAG = "ShizukuBroadcast";

    /**
     * Broadcast sent whenever a permission is granted, revoked, or expires.
     *
     * <p>
     * Extras:
     * <ul>
     * <li>{@link #EXTRA_PACKAGE_NAME} — affected package
     * <li>{@link #EXTRA_USER_ID} — affected user
     * <li>{@link #EXTRA_GRANTED} — {@code true} if newly granted,
     * {@code false} if revoked/expired
     * </ul>
     */
    public static final String ACTION_SHIZUKU_PERMISSION_CHANGED = "com.android.systemshizuku.action.PERMISSION_CHANGED";

    /** String extra: affected package name. */
    public static final String EXTRA_PACKAGE_NAME = "packageName";

    /** Int extra: affected Android user ID. */
    public static final String EXTRA_USER_ID = "userId";

    /** Boolean extra: new grant state (true = granted, false = revoked). */
    public static final String EXTRA_GRANTED = "granted";

    private ShizukuBroadcasts() {
    }

    // -----------------------------------------------------------------------
    // Broadcast helpers
    // -----------------------------------------------------------------------

    /**
     * Sends {@link #ACTION_SHIZUKU_PERMISSION_CHANGED} to all processes
     * running as {@code userId}.
     *
     * @param context     system context
     * @param packageName the affected package
     * @param userId      the Android user ID
     * @param granted     the new grant state
     */
    public static void sendPermissionChanged(
            Context context, String packageName, int userId, boolean granted) {
        Intent intent = new Intent(ACTION_SHIZUKU_PERMISSION_CHANGED);
        intent.putExtra(EXTRA_PACKAGE_NAME, packageName);
        intent.putExtra(EXTRA_USER_ID, userId);
        intent.putExtra(EXTRA_GRANTED, granted);
        // Restrict delivery to the affected user's processes.
        intent.setPackage(packageName); // targeted so only the app receives it
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_FOREGROUND);
        try {
            context.sendBroadcastAsUser(intent, UserHandle.of(userId));
            Log.d(TAG, "sent PERMISSION_CHANGED pkg=" + packageName
                    + " user=" + userId + " granted=" + granted);
        } catch (Exception e) {
            Log.e(TAG, "sendPermissionChanged failed", e);
        }
    }
}
