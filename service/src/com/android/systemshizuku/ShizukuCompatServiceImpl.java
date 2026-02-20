package com.android.systemshizuku;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import rikka.shizuku.server.IShizukuService;
import com.android.systemshizuku.store.PermissionStore;

import java.io.IOException;

/**
 * Compatibility implementation of rikka.shizuku.server.IShizukuService.
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
        // Return >= 11 for modern Shizuku-API compatibility
        return 11;
    }

    @Override
    public int getUid() {
        // We are running as system (1000)
        return Process.SYSTEM_UID;
    }

    @Override
    public int checkSelfPermission(String permission) {
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getUserId(callingUid);
        String packageName = getPackageNameForUid(callingUid);

        if (packageName == null) return PackageManager.PERMISSION_DENIED;

        ShizukuPermission grant = mStore.getGrant(packageName, userId);
        if (grant != null && grant.granted) {
            return PackageManager.PERMISSION_GRANTED;
        }
        return PackageManager.PERMISSION_DENIED;
    }

    @Override
    public void requestPermission(int requestCode) {
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getUserId(callingUid);
        String packageName = getPackageNameForUid(callingUid);

        if (packageName == null) return;

        // Delegate to existing internal request logic.
        // For Shizuku-API compatibility, the user usually expects a broadcast 
        // or a callback. Since we are integrated, we trigger our own dialog.
        mInternalService.requestPermission(packageName, userId, new ISystemShizukuCallback.Stub() {
            @Override
            public void onGranted(ShizukuPermission permission, IBinder sessionToken) {
                // Shizuku apps often listen for Rikka-specific broadcasts 
                // but since we are system_shizuku, we might need to send 
                // the expected broadcast if apps rely on it.
                Log.d(TAG, "Permission granted for " + packageName);
            }

            @Override
            public void onDenied(String packageName, int userId) {
                Log.d(TAG, "Permission denied for " + packageName);
            }
        });
    }

    @Override
    public String getToken() {
        // system_shizuku doesn't use tokens for standard calls, 
        // it uses UID checking. Return a dummy or null.
        return "system_integrated_shizuku";
    }

    @Override
    public void newProcess(String[] cmd, String[] env, String dir) {
        // Check permission first
        if (checkSelfPermission(null) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Shizuku permission not granted");
        }

        try {
            // Execute as system UID
            Runtime.getRuntime().exec(cmd, env, dir != null ? new java.io.File(dir) : null);
            // Note: In a real implementation, you'd want to return a Process-like 
            // object/interface so the caller can interact with it.
        } catch (IOException e) {
            Log.e(TAG, "Failed to execute command", e);
        }
    }

    private String getPackageNameForUid(int uid) {
        String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        return (packages != null && packages.length > 0) ? packages[0] : null;
    }
}
