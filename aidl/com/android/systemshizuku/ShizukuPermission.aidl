/*
 * Copyright (C) 2026 The system_shizuku Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemshizuku;

/**
 * Describes a single permission grant record stored by system_shizuku.
 *
 * <p>Version history:
 *   1 — initial (packageName, appId, userId, granted, grantedAt, flags, scope, expiresAt)
 *
 * <p>Flags (bit field):
 *   0x1  — GRANT_PERSISTENT   : grant survives reboots (default)
 *   0x2  — GRANT_SESSION_ONLY : grant is invalidated when the app process dies
 *   0x4  — REVOKED_BY_USER    : package was manually revoked via Settings
 *   0x8  — REVOKED_BY_POLICY  : revoked programmatically (e.g. device admin)
 *
 * <p>Scope (string, may be null):
 *   Reserved for future fine-grained scope control (e.g. "shell:read-only").
 *   Currently unused; implementations must treat null and "" as equivalent.
 */
parcelable ShizukuPermission {
    /** Parcelable schema version — always write 1, tolerate higher on read. */
    int version = 1;

    /**
     * The application package name this record belongs to.
     * Must not be null.
     */
    @utf8InCpp String packageName;

    /**
     * The application ID (uid % 100000) — does NOT include userId.
     * Use appId + (userId * UserHandle.PER_USER_RANGE) to reconstruct the
     * full uid if needed. Stored separately so the record survives user
     * re-creation with a new uid.
     */
    int appId;

    /**
     * Android user ID this grant belongs to.
     */
    int userId;

    /**
     * Whether the permission is currently active.
     * false means the record exists but has been revoked or expired.
     */
    boolean granted;

    /** Epoch-millisecond timestamp of the most recent grant. */
    long grantedAt;

    /**
     * Epoch-millisecond expiry time, or 0 for "never expires".
     * The service must check this on every inbound call and auto-revoke
     * when now > expiresAt.
     */
    long expiresAt;

    /**
     * Bit-field of GRANT_* / REVOKED_* flags defined in the class javadoc.
     * Unknown bits must be preserved on read/write for forwards compatibility.
     */
    int flags;

    /**
     * Optional scope string. Null / "" means full elevated access.
     * Future versions may define scope tokens to limit what APIs the
     * granted app can actually invoke.
     */
    @nullable @utf8InCpp String scope;
}
