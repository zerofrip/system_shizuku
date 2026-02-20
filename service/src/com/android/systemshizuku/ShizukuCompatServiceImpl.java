package com.android.systemshizuku;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import com.android.systemshizuku.store.PermissionStore;

import java.io.IOException;

/**
 * Compatibility implementation of moe.shizuku.server.IShizukuService.
 * This is the object registered as "shizuku" in ServiceManager.
 */
public class ShizukuCompatServiceImpl extends IShizukuService.Stub {

    private static final String TAG = "ShizukuCompat";
    private final Context mContext;
    private final SystemShizukuServiceImpl mInternalService;
    private final PermissionStore mStore;

    public ShizukuCompatServiceImpl(Context context, SystemShizukuServiceImpl internalService) {
        mContext = context;
        mInternalService = internalService;
        mStore = new PermissionStore(context);
    }

    @Override
    public int getVersion() {
        return 13; // Return 13 to indicate modern Shizuku support
    }

    @Override
    public int getUid() {
        return Process.SYSTEM_UID;
    }

    @Override
    public int checkPermission(String permission) {
        return checkSelfPermission() ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }

    @Override
    public IRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
        if (!checkSelfPermission()) {
            throw new SecurityException("Shizuku permission not granted");
        }
        try {
            // Execution as system user
            Process p = Runtime.getRuntime().exec(cmd, env, dir != null ? new java.io.File(dir) : null);
            return new RemoteProcessImpl(p);
        } catch (IOException e) {
            Log.e(TAG, "newProcess failed", e);
            return null;
        }
    }

    @Override
    public String getSELinuxContext() {
        return "u:r:system_shizuku:s0";
    }

    @Override
    public String getSystemProperty(String name, String defaultValue) {
        return android.os.SystemProperties.get(name, defaultValue);
    }

    @Override
    public void setSystemProperty(String name, String value) {
        // Only allow if granted
        if (checkSelfPermission()) {
            android.os.SystemProperties.set(name, value);
        }
    }

    @Override
    public int addUserService(IBinder conn, Bundle args) {
        // UserService not implemented in this system shim
        return -1;
    }

    @Override
    public int removeUserService(IBinder conn, Bundle args) {
        return -1;
    }

    @Override
    public void requestPermission(int requestCode) {
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getUserId(callingUid);
        String packageName = getPackageNameForUid(callingUid);

        if (packageName == null)
            return;

        mInternalService.requestPermission(packageName, userId, new ISystemShizukuCallback.Stub() {
            @Override
            public void onGranted(ShizukuPermission permission, IBinder sessionToken) {
                Log.d(TAG, "Permission granted for " + packageName);
            }

            @Override
            public void onDenied(String packageName, int userId) {
                Log.d(TAG, "Permission denied for " + packageName);
            }
        });
    }

    @Override
    public boolean checkSelfPermission() {
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getUserId(callingUid);
        String packageName = getPackageNameForUid(callingUid);

        if (packageName == null)
            return false;

        ShizukuPermission grant = mStore.getGrant(packageName, userId);
        return grant != null && grant.granted;
    }

    @Override
    public boolean shouldShowRequestPermissionRationale() {
        return false;
    }

    @Override
    public void attachApplication(IBinder application, Bundle args) {
        // No-op for system shim
    }

    @Override
    public void exit() {
        // System service does not exit on command
    }

    @Override
    public void attachUserService(IBinder binder, Bundle options) {
    }

    @Override
    public void dispatchPackageChanged(Intent intent) {
    }

    @Override
    public boolean isHidden(int uid) {
        return false;
    }

    @Override
    public void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, Bundle data) {
    }

    @Override
    public int getFlagsForUid(int uid, int mask) {
        return 0;
    }

    @Override
    public void updateFlagsForUid(int uid, int mask, int value) {
    }

    private String getPackageNameForUid(int uid) {
        String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        return (packages != null && packages.length > 0) ? packages[0] : null;
    }
}
