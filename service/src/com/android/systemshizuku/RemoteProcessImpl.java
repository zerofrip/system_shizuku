package com.android.systemshizuku;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import moe.shizuku.server.IRemoteProcess;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class RemoteProcessImpl extends IRemoteProcess.Stub {

    private static final String TAG = "ShizukuRemoteProcess";
    private final Process mProcess;

    public RemoteProcessImpl(Process process) {
        mProcess = process;
    }

    @Override
    public ParcelFileDescriptor getOutputStream() {
        try {
            return ParcelFileDescriptor.dup(
                    ParcelFileDescriptor.fromFd(getFileDescriptor(mProcess.getOutputStream())));
        } catch (IOException e) {
            Log.e(TAG, "failed to getOutputStream", e);
            return null;
        }
    }

    @Override
    public ParcelFileDescriptor getInputStream() {
        try {
            return ParcelFileDescriptor.dup(
                    ParcelFileDescriptor.fromFd(getFileDescriptor(mProcess.getInputStream())));
        } catch (IOException e) {
            Log.e(TAG, "failed to getInputStream", e);
            return null;
        }
    }

    @Override
    public ParcelFileDescriptor getErrorStream() {
        try {
            return ParcelFileDescriptor.dup(
                    ParcelFileDescriptor.fromFd(getFileDescriptor(mProcess.getErrorStream())));
        } catch (IOException e) {
            Log.e(TAG, "failed to getErrorStream", e);
            return null;
        }
    }

    @Override
    public int waitFor() {
        try {
            return mProcess.waitFor();
        } catch (InterruptedException e) {
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
            return false;
        }
    }

    private java.io.FileDescriptor getFileDescriptor(Object stream) {
        try {
            java.lang.reflect.Field field = stream.getClass().getDeclaredField("fd");
            field.setAccessible(true);
            return (java.io.FileDescriptor) field.get(stream);
        } catch (Exception e) {
            return null;
        }
    }
}
