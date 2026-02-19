/*
 * Copyright (C) 2026 The system_shizuku Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.settings.applications.specialaccess.systemshizuku;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

import com.android.systemshizuku.ISystemShizukuManager;
import com.android.systemshizuku.ShizukuAuditEvent;
import com.android.systemshizuku.ShizukuPermission;

import java.util.Collections;
import java.util.List;

/**
 * Controller used by the "System Shizuku" entry under
 * Settings › Privacy & Security › Special App Access.
 *
 * <p>This controller is <strong>read and revoke only</strong>. There is
 * intentionally no "Grant" button or code path in this UI. Granting is
 * handled exclusively by the system_shizuku service via its consent dialog.
 *
 * <p>Wire this into the Settings preference graph by adding an entry to
 * {@code res/xml/special_access.xml} (or equivalent) and resolving the
 * fragment to {@link SystemShizukuListFragment}.
 *
 * <p><b>Typical integration:</b>
 * <pre>
 *   &lt;Preference
 *       android:fragment="...SystemShizukuListFragment"
 *       android:key="system_shizuku"
 *       android:title="@string/system_shizuku_title"
 *       android:summary="@string/system_shizuku_summary" /&gt;
 * </pre>
 */
public class SystemShizukuController {

    private static final String TAG = "SystemShizukuCtrl";
    private static final String MGR_SERVICE = "shizuku_mgr";

    private final Context mContext;
    private ISystemShizukuManager mManager;

    public SystemShizukuController(Context context) {
        mContext = context;
    }

    // -----------------------------------------------------------------------
    // Service binding
    // -----------------------------------------------------------------------

    /**
     * Returns the {@link ISystemShizukuManager} proxy, lazily resolved.
     *
     * @return the manager proxy, or {@code null} if the service is unavailable
     */
    private ISystemShizukuManager getManager() {
        if (mManager != null && mManager.asBinder().isBinderAlive()) {
            return mManager;
        }
        IBinder binder = ServiceManager.getService(MGR_SERVICE);
        if (binder == null) {
            Log.w(TAG, "system_shizuku_mgr service not available");
            return null;
        }
        mManager = ISystemShizukuManager.Stub.asInterface(binder);
        return mManager;
    }

    // -----------------------------------------------------------------------
    // Read APIs — called by the list fragment to populate entries
    // -----------------------------------------------------------------------

    /**
     * Returns all packages that have (or had) a Shizuku permission record for
     * the given user, for display in the preference list.
     *
     * @param userId Android user ID of the current foreground user
     * @return list of permission records; empty list if service unavailable
     */
    public List<ShizukuPermission> getGrantedPackages(int userId) {
        ISystemShizukuManager mgr = getManager();
        if (mgr == null) return Collections.emptyList();
        try {
            return mgr.getGrantedPackages(userId);
        } catch (RemoteException e) {
            Log.e(TAG, "getGrantedPackages failed", e);
            return Collections.emptyList();
        }
    }

    /**
     * Returns the permission record for a single package, or {@code null}.
     *
     * @param packageName target package
     * @param userId      Android user ID
     */
    public ShizukuPermission getPermission(String packageName, int userId) {
        ISystemShizukuManager mgr = getManager();
        if (mgr == null) return null;
        try {
            return mgr.getPermission(packageName, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "getPermission failed", e);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Revoke — called when the user taps "Revoke" in the detail fragment
    // NOTE: there is deliberately no grantPermission() method here.
    // -----------------------------------------------------------------------

    /**
     * Revokes the permission for {@code packageName} in {@code userId}.
     *
     * @param packageName target package
     * @param userId      Android user ID
     * @return {@code true} if the revoke call succeeded (even if no record
     *         existed), {@code false} if the service was unreachable
     */
    public boolean revokePermission(String packageName, int userId) {
        ISystemShizukuManager mgr = getManager();
        if (mgr == null) return false;
        try {
            mgr.revokePermission(packageName, userId);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "revokePermission failed", e);
            return false;
        }
    }

    /**
     * Returns the audit log for {@code packageName} in {@code userId},
     * newest first.  Returns an empty list if the service is unavailable.
     *
     * @param packageName target package, or {@code null} for all packages
     * @param userId      Android user ID
     */
    public List<ShizukuAuditEvent> getAuditLog(String packageName, int userId) {
        ISystemShizukuManager mgr = getManager();
        if (mgr == null) return Collections.emptyList();
        try {
            return mgr.getAuditLog(packageName, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "getAuditLog failed", e);
            return Collections.emptyList();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns a human-readable label for a package, falling back to the
     * package name if the PackageManager lookup fails.
     */
    public CharSequence getAppLabel(String packageName, int userId) {
        try {
            PackageManager pm = mContext.createContextAsUser(
                    UserHandle.of(userId), 0).getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(ai);
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }
}
