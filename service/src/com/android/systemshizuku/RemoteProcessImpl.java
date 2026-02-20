package com.android.systemshizuku;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import moe.shizuku.server.IRemoteProcess;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Hardened implementation of IRemoteProcess.
 * Handles automatic process cleanup via DeathRecipient.
 */
public class RemoteProcessImpl extends IRemoteProcess.Stub {

    private static final String TAG = "ShizukuRemoteProcess";
    private final Process mProcess;
    private final IBinder mClientBinder;
    private final IBinder.DeathRecipient mDeathRecipient;

    public RemoteProcessImpl(Process process, IBinder clientBinder) {
        mProcess = process;
        mClientBinder = clientBinder;
        mDeathRecipient = () -> {
            Log.w(TAG, "Client died, destroying process: " + mProcess);
            destroy();
        };

        try {
            mClientBinder.linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to link to death, destroying process immediately");
            destroy();
        }
    }

    @Override
    public ParcelFileDescriptor getOutputStream() {
        return getPfd(mProcess.getOutputStream());
    }

    @Override
    public ParcelFileDescriptor getInputStream() {
        return getPfd(mProcess.getInputStream());
    }

    @Override
    public ParcelFileDescriptor getErrorStream() {
        return getPfd(mProcess.getErrorStream());
    }

    @Override
    public int waitFor() {
        try {
            // java.lang.Process.waitFor() is blocking.
            // In system_shizuku standalone process, this is acceptable as long as it's not
            // the main thread.
            return mProcess.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    @Override
    public int exitValue() {
        return mProcess.exitValue();
    }

    @Override
    public void destroy() {
        mProcess.destroy();
        try {
            mClientBinder.unlinkToDeath(mDeathRecipient, 0);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean alive() {
        return mProcess.isAlive();
    }

    @Override
    public boolean waitForTimeout(long timeout, String unit) {
        try {
            return mProcess.waitFor(timeout, TimeUnit.valueOf(unit));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private ParcelFileDescriptor getPfd(Object stream) {
        FileDescriptor fd = getFileDescriptor(stream);
        if (fd == null || !fd.valid()) {
            return null;
        }
        try {
            return ParcelFileDescriptor.dup(fd);
        } catch (IOException e) {
            Log.e(TAG, "Failed to dup FileDescriptor", e);
            return null;
        }
    }

    private FileDescriptor getFileDescriptor(Object stream) {
        if (stream == null)
            return null;

        // 1. Direct getFD() if supported by type
        try {
            if (stream instanceof FileInputStream) {
                return ((FileInputStream) stream).getFD();
            } else if (stream instanceof FileOutputStream) {
                return ((FileOutputStream) stream).getFD();
            }
        } catch (IOException e) {
            // Ignored, attempt alternatives
        }

        // 2. Reflection for getFD() method (handles wrapped streams)
        try {
            Method method = stream.getClass().getMethod("getFD");
            return (FileDescriptor) method.invoke(stream);
        } catch (Exception ignored) {
        }

        // 3. Last resort: internal "fd" field
        try {
            Field field = stream.getClass().getDeclaredField("fd");
            field.setAccessible(true);
            return (FileDescriptor) field.get(stream);
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract FileDescriptor via reflection", e);
            return null;
        }
    }
}
