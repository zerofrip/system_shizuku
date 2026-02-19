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

/**
 * A single audit-log entry emitted by the system_shizuku service.
 *
 * <p>Event types (eventType field):
 *   1 — GRANT      : permission was granted (by user dialog)
 *   2 — REVOKE     : permission was revoked (by user via Settings or policy)
 *   3 — USE        : a binder call was dispatched on behalf of this package
 *   4 — DENY       : permission was denied (user dismissed dialog or policy)
 *   5 — EXPIRE     : time-limited grant expired automatically
 */
parcelable ShizukuAuditEvent {
    /** Parcelable schema version. */
    int version = 1;

    /**
     * One of the GRANT / REVOKE / USE / DENY / EXPIRE constants above.
     */
    int eventType;

    /** Package name of the affected app. */
    @utf8InCpp String packageName;

    /** Application ID (uid without userId component). */
    int appId;

    /** Android user ID. */
    int userId;

    /** Epoch-millisecond timestamp of the event. */
    long eventAt;

    /**
     * Optional free-form detail, e.g. the Binder transaction code for USE,
     * or "policy:mdm" for a REVOKE triggered by a device admin.
     * May be null.
     */
    @nullable @utf8InCpp String detail;
}
