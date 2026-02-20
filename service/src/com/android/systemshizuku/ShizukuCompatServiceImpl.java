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
import android.util.Slog;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import com.android.systemshizuku.store.PermissionStore;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compatibility implementation of moe.shizuku.server.IShizukuService.
 * This is the object registered as "shizuku" in ServiceManager.
 */
public class ShizukuCompatServiceImpl extends IShizukuService.Stub {

    private static final String TAG = "ShizukuCompat";

    // Production Constants
    private static final int MAX_GLOBAL_PROCESSES = 64;
    private static final int MAX_PER_UID_PROCESSES = 8;

    private final Context mContext;
    private final SystemShizukuServiceImpl mInternalService;
    private final PermissionStore mStore;

    // Process tracking
    private final AtomicInteger mGlobalProcessCount = new AtomicInteger(0);
    private final Map<Integer, AtomicInteger> mUidProcessCounts = new ConcurrentHashMap<>();

    public ShizukuCompatServiceImpl(Context context, SystemShizukuServiceImpl internalService) {
        mContext = context;
        mInternalService = internalService;
        mStore = new PermissionStore(context);
    }

    @Override
    public int getVersion() {
        return 13;
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
        final int callingUid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(callingUid);
        final String packageName = getPackageNameForUid(callingUid);

        if (!checkSelfPermission()) {
            throw new SecurityException("Shizuku permission not granted for " + packageName);
        }

        // Enforce process limits
        if (mGlobalProcessCount.get() >= MAX_GLOBAL_PROCESSES) {
            throw new SecurityException("Global process limit reached (" + MAX_GLOBAL_PROCESSES + ")");
        }

        AtomicInteger uidCounter = mUidProcessCounts.computeIfAbsent(callingUid, k -> new AtomicInteger(0));
        if (uidCounter.get() >= MAX_PER_UID_PROCESSES) {
            throw new SecurityException("Per-UID process limit reached (" + MAX_PER_UID_PROCESSES + ")");
        }

        // Audit Logging
        Slog.i(TAG, "newProcess: cmd=" + Arrays.toString(cmd) + " uid=" + callingUid + " pkg=" + packageName);

        try {
            mGlobalProcessCount.incrementAndGet();
            uidCounter.incrementAndGet();

            // Use the caller's binder to register death recipient in RemoteProcessImpl
            IBinder clientBinder = Binder.getCallingBinder();

            Process p = Runtime.getRuntime().exec(cmd, env, dir != null ? new java.io.File(dir) : null);

            return new RemoteProcessImpl(p, clientBinder) {
                @Override
                public void destroy() {
                    super.destroy();
                    mGlobalProcessCount.decrementAndGet();
                    uidCounter.decrementAndGet();
                }
            };
        } catch (IOException e) {
            mGlobalProcessCount.decrementAndGet();
            uidCounter.decrementAndGet();
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
        if (checkSelfPermission()) {
            Slog.w(TAG, "setSystemProperty: name=" + name + " uid=" + Binder.getCallingUid());
            android.os.SystemProperties.set(name, value);
        }
    }

    @Override
    public int addUserService(IBinder conn, Bundle args) {
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
        // Shizuku-API calls this but it's not strictly required for our simple shim
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
