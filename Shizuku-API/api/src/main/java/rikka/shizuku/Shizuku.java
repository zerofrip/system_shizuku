package rikka.shizuku;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import rikka.shizuku.server.IShizukuService;

/**
 * Forked Shizuku-API main class.
 * Modified to bypass ADB/Root discovery and connect directly to the "shizuku"
 * system service.
 *
 * This version removes dependencies on ShizukuProvider and BinderContainer,
 * treating Shizuku as a standard Android system service.
 */
public class Shizuku {

    private static final String TAG = "Shizuku";
    private static IShizukuService sService;

    /**
     * Connects to the system Shizuku service.
     * In system_shizuku, this service is always available via ServiceManager.
     */
    private static synchronized IShizukuService getService() {
        if (sService != null && sService.asBinder().isBinderAlive()) {
            return sService;
        }

        try {
            // Using reflection to access ServiceManager from client app
            // as it is not part of the public SDK.
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "shizuku");

            if (binder != null) {
                sService = IShizukuService.Stub.asInterface(binder);
                Log.i(TAG, "Connected to system_shizuku service");
            } else {
                Log.w(TAG, "system_shizuku service ('shizuku') not found in ServiceManager");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to connect to system Shizuku service", e);
        }

        return sService;
    }

    /**
     * Returns the version of the Shizuku service.
     * 
     * @return version >= 11 if connected, -1 otherwise.
     */
    public static int getVersion() {
        IShizukuService service = getService();
        if (service == null)
            return -1;
        try {
            return service.getVersion();
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * Returns the UID of the Shizuku service (typically 1000 for system).
     */
    public static int getUid() {
        IShizukuService service = getService();
        if (service == null)
            return -1;
        try {
            return service.getUid();
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * Checks if the calling app has been granted Shizuku permission.
     */
    public static int checkSelfPermission() {
        IShizukuService service = getService();
        if (service == null)
            return PackageManager.PERMISSION_DENIED;
        try {
            // Delegate checking to the system service.
            return service.checkSelfPermission(null);
        } catch (RemoteException e) {
            return PackageManager.PERMISSION_DENIED;
        }
    }

    /**
     * Requests Shizuku permission from the user.
     * Triggers the system_shizuku consent dialog.
     */
    public static void requestPermission(int requestCode) {
        IShizukuService service = getService();
        if (service == null)
            return;
        try {
            service.requestPermission(requestCode);
        } catch (RemoteException e) {
            Log.e(TAG, "requestPermission failed", e);
        }
    }

    /**
     * Executes a command as the system user via Shizuku.
     */
    public static void newProcess(String[] cmd, String[] env, String dir) {
        IShizukuService service = getService();
        if (service == null)
            return;
        try {
            service.newProcess(cmd, env, dir);
        } catch (RemoteException e) {
            Log.e(TAG, "newProcess failed", e);
        }
    }

    /**
     * Returns true if the Shizuku service is reachable.
     */
    public static boolean pingBinder() {
        return getService() != null;
    }

    /**
     * Compatibility listener for apps expecting asynchronous binder acquisition.
     */
    public static void addBinderReceivedListener(OnBinderReceivedListener listener) {
        if (getService() != null) {
            listener.onBinderReceived();
        }
    }

    public static boolean removeBinderReceivedListener(OnBinderReceivedListener listener) {
        return true;
    }

    public interface OnBinderReceivedListener {
        void onBinderReceived();
    }
}
