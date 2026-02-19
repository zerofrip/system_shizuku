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
import com.android.systemshizuku.ShizukuAuditEvent;

/**
 * System-only management interface for system_shizuku.
 *
 * <p><b>Caller requirements</b>: Every method in this interface requires the
 * caller to hold {@code android.permission.MANAGE_SYSTEM_SHIZUKU}, which is
 * declared as {@code signatureOrSystem}. On Android 8+, the permission must
 * additionally be whitelisted in the privileged-permissions XML for the
 * calling app's partition.
 *
 * <p>The service additionally enforces {@code Binder.getCallingUid() == SYSTEM_UID}
 * (uid 1000) or a recognised management package before processing any call.
 *
 * <p>This interface is intentionally <em>read + revoke only</em>. There is
 * no {@code grantPermission} method — granting is exclusively triggered by
 * the user responding to the system dialog shown by the service itself.
 *
 * <p>Clients obtain this interface via the same service name
 * {@code "system_shizuku"} but must cast the retrieved Binder to
 * {@code ISystemShizukuManager} using the appropriate stub:
 * <pre>
 *   IBinder b = ServiceManager.getService("system_shizuku_mgr");
 *   ISystemShizukuManager mgr = ISystemShizukuManager.Stub.asInterface(b);
 * </pre>
 * The service is registered under the separate name
 * {@code "system_shizuku_mgr"} to enforce a hard access separation.
 */
interface ISystemShizukuManager {

    // -----------------------------------------------------------------------
    // Read-only queries (Settings list view)
    // -----------------------------------------------------------------------

    /**
     * Returns all active grant records for the given user, including
     * revoked records (so that Settings can show a "recently revoked" list).
     *
     * <p>Records with {@code granted = false} and an {@code eventAt} older
     * than 30 days may be omitted at the discretion of the implementation.
     *
     * @param userId Android user ID to query; use
     *               {@link android.os.UserHandle#USER_ALL} (-1) to retrieve
     *               records for all users (requires INTERACT_ACROSS_USERS_FULL)
     * @return list of permission records, never null, may be empty
     * @throws SecurityException if caller lacks MANAGE_SYSTEM_SHIZUKU
     */
    List<ShizukuPermission> getGrantedPackages(int userId);

    /**
     * Returns the permission record for a specific package in a user.
     *
     * @param packageName  target package name
     * @param userId       Android user ID
     * @return grant record, or {@code null} if no record exists
     * @throws SecurityException if caller lacks MANAGE_SYSTEM_SHIZUKU
     */
    @nullable ShizukuPermission getPermission(
            @utf8InCpp String packageName,
            int userId);

    // -----------------------------------------------------------------------
    // Revocation (Settings detail view)
    // -----------------------------------------------------------------------

    /**
     * Revokes the permission grant for {@code packageName} in {@code userId}.
     *
     * <p>The implementation must:
     * <ol>
     *   <li>Set {@code granted = false} and OR-in the {@code REVOKED_BY_USER}
     *       flag on the stored record.
     *   <li>Invalidate any live session token for the package immediately by
     *       calling {@link IBinder#unlinkToDeath} and signalling the token as
     *       dead.
     *   <li>Write an audit-log entry with type {@code REVOKE}.
     *   <li>Broadcast {@code ACTION_SHIZUKU_PERMISSION_CHANGED} so that any
     *       bound client can react.
     * </ol>
     *
     * <p>If no record exists this method is a no-op (does not throw).
     *
     * @param packageName target package name
     * @param userId      Android user ID
     * @throws SecurityException if caller lacks MANAGE_SYSTEM_SHIZUKU
     */
    void revokePermission(@utf8InCpp String packageName, int userId);

    /**
     * Revokes all grants for every package in {@code userId}.
     *
     * <p>Useful for user-removal or factory-reset flows. The implementation
     * must batch the audit-log writes rather than calling
     * {@link #revokePermission} in a loop to minimise I/O.
     *
     * @param userId Android user ID (-1 = all users, requires extra privilege)
     * @throws SecurityException if caller lacks MANAGE_SYSTEM_SHIZUKU
     */
    void revokeAllPermissions(int userId);

    // -----------------------------------------------------------------------
    // Audit log (Settings detail view)
    // -----------------------------------------------------------------------

    /**
     * Returns the audit log for a package, ordered newest-first.
     *
     * <p>Implementations may cap the returned list (e.g. last 100 entries).
     *
     * @param packageName target package, or {@code null} for all packages
     * @param userId      Android user ID
     * @return list of audit events, never null, may be empty
     * @throws SecurityException if caller lacks MANAGE_SYSTEM_SHIZUKU
     */
    List<ShizukuAuditEvent> getAuditLog(
            @nullable @utf8InCpp String packageName,
            int userId);
}
