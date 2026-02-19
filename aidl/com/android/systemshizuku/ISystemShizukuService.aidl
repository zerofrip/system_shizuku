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

import com.android.systemshizuku.ShizukuPermission;
import com.android.systemshizuku.ISystemShizukuCallback;

/**
 * Public-facing Binder interface for system_shizuku.
 *
 * <p><b>Caller requirements</b>: Any installed application may resolve this
 * interface via {@code ServiceManager.getService("system_shizuku")}. However,
 * only apps that have been granted permission (via the system dialog) may
 * successfully perform elevated operations.
 *
 * <p><b>Security model</b>:
 * <ul>
 *   <li>The service must verify {@code Binder.getCallingUid()} on every call.
 *   <li>{@link #requestPermission} is rate-limited per-package to prevent
 *       dialog spam; excessive calls are silently dropped.
 *   <li>{@link #getMyPermission} only returns data for the *calling* uid —
 *       it cannot be used to probe other apps' grant status.
 *   <li>{@link #ping} is the only unrestricted method.
 * </ul>
 *
 * <p><b>Thread safety</b>: Binder calls arrive on a thread pool.
 * Implementations must be thread-safe.
 */
interface ISystemShizukuService {

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Returns the server-side protocol version.
     *
     * <p>Clients should call this first to detect capability differences.
     * The current version is {@code 1}.
     *
     * <p>No permission required.
     *
     * @return protocol version integer, always >= 1
     */
    int ping();

    // -----------------------------------------------------------------------
    // Permission requests (app → service → user dialog)
    // -----------------------------------------------------------------------

    /**
     * Requests elevated permission for {@code packageName} in {@code userId}.
     *
     * <p>If the package already holds a valid grant the callback is fired
     * immediately with {@link ISystemShizukuCallback#onGranted} — no dialog
     * is shown.
     *
     * <p>If no grant exists the service shows a system-level permission
     * dialog (UID 1000 overlay). The callback is fired when the user responds.
     *
     * <p>If the package has been permanently denied (REVOKED_BY_USER flag set
     * on a prior record), {@link ISystemShizukuCallback#onDenied} is called
     * immediately without a dialog.
     *
     * <p><b>Rate limiting</b>: at most one pending dialog is allowed per
     * package at a time. Subsequent calls while a dialog is visible are
     * queued; if the queue depth exceeds 3 the call is rejected with a
     * {@link SecurityException}.
     *
     * <p>The caller's uid must match {@code packageName} in {@code userId}.
     * Calling on behalf of another package is rejected with
     * {@link SecurityException}.
     *
     * @param packageName the package requesting elevated access; must match
     *                    the calling uid's package
     * @param userId      the Android user ID the package is running in
     * @param callback    one-shot result callback; must not be null
     * @throws SecurityException if the caller does not own {@code packageName}
     *                           in {@code userId}
     */
    void requestPermission(
            @utf8InCpp String packageName,
            int userId,
            ISystemShizukuCallback callback);

    // -----------------------------------------------------------------------
    // Self-query (callers may inspect only their own grant)
    // -----------------------------------------------------------------------

    /**
     * Returns the {@link ShizukuPermission} record for the calling package
     * in {@code userId}, or {@code null} if no record exists.
     *
     * <p>The service enforces that the calling uid owns {@code packageName}
     * in {@code userId}. Any attempt to query another package's record
     * results in a {@link SecurityException}.
     *
     * @param packageName calling package name
     * @param userId      the Android user ID
     * @return grant record, or null
     * @throws SecurityException if caller does not own the package
     */
    @nullable ShizukuPermission getMyPermission(
            @utf8InCpp String packageName,
            int userId);

    // -----------------------------------------------------------------------
    // Session management
    // -----------------------------------------------------------------------

    /**
     * Attaches a session token obtained from {@link ISystemShizukuCallback#onGranted}.
     *
     * <p>Once attached, the service registers a {@link IBinder.DeathRecipient}
     * on the token. If the token dies  (app process killed) and the grant
     * has the {@code GRANT_SESSION_ONLY} flag set, the grant is automatically
     * revoked. For persistent grants the grant record is preserved but the
     * active session ends.
     *
     * <p>Calling this with a token that was not issued to the calling uid
     * throws {@link SecurityException}.
     *
     * @param sessionToken the IBinder received in {@link ISystemShizukuCallback#onGranted}
     * @throws SecurityException     if the token does not belong to the caller
     * @throws IllegalStateException if the token has already been attached
     */
    void attachSession(IBinder sessionToken);
}
