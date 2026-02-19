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

/**
 * One-shot callback delivered to an app after it calls
 * {@link ISystemShizukuService#requestPermission}.
 *
 * <p>The service calls exactly one of these methods and then drops its
 * reference to the callback object.
 *
 * <p><b>Session token</b>: when {@link #onGranted} is called, the
 * {@code sessionToken} IBinder represents the live session.  The service
 * registers a death recipient on this token; when the app process dies the
 * grant is automatically downgraded to GRANT_PERSISTENT (or fully revoked
 * if the flag was GRANT_SESSION_ONLY).  Apps must NOT release this token
 * until they are done using the elevated channel.
 *
 * <p><b>Threading</b>: these callbacks are dispatched from a Binder thread
 * pool.  Apps must not perform long-running work inside them.
 */
oneway interface ISystemShizukuCallback {

    /**
     * Invoked when the user grants the permission request.
     *
     * @param permission the grant record as stored by the service
     * @param sessionToken an IBinder the app must hold until it no longer
     *                     needs the elevated channel; passing it to
     *                     {@link ISystemShizukuService#attachSession} ties
     *                     the Binder proxy to this session.
     */
    void onGranted(in ShizukuPermission permission, IBinder sessionToken);

    /**
     * Invoked when the user denies the permission request or when the
     * package is already permanently denied.
     *
     * @param packageName the requesting package (for disambiguation when a
     *                    single callback object is reused across calls)
     * @param userId      the requesting user
     */
    void onDenied(@utf8InCpp String packageName, int userId);
}
