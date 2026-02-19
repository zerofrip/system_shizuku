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

import android.app.ActivityThread;
import android.content.Context;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
import android.util.Log;

import com.android.systemshizuku.store.PermissionStore;

/**
 * Entry point for the system_shizuku standalone process.
 *
 * <p>
 * Launched by init.rc:
 * 
 * <pre>
 *   /system/bin/app_process /system/priv-app/SystemShizuku \
 *       com.android.systemshizuku.SystemShizukuServer
 * </pre>
 *
 * <p>
 * Registers two Binder services:
 * <ul>
 * <li>{@code "system_shizuku"} — public interface
 * ({@link ISystemShizukuService})
 * <li>{@code "system_shizuku_mgr"} — management interface
 * ({@link ISystemShizukuManager})
 * </ul>
 *
 * <p>
 * A single {@link PermissionStore} instance is shared between both
 * service implementations to ensure consistent reads/writes.
 */
public class SystemShizukuServer {

    private static final String TAG = "SystemShizuku";

    public static final String SERVICE_NAME = "shizuku";
    public static final String MGR_SERVICE_NAME = "shizuku_mgr";

    public static void main(String[] args) {
        Log.i(TAG, "Starting system_shizuku service");

        Looper.prepareMainLooper();

        // ------------------------------------------------------------------
        // Obtain a system Context via ActivityThread (available to app_process
        // processes that share android.uid.system).
        // ------------------------------------------------------------------
        Context context = ActivityThread.systemMain().getSystemContext();

        // ------------------------------------------------------------------
        // Shared store — one instance for both services.
        // ------------------------------------------------------------------
        PermissionStore store = new PermissionStore(context);

        // ------------------------------------------------------------------
        // Instantiate service implementations
        // ------------------------------------------------------------------
        SystemShizukuServiceImpl serviceImpl = new SystemShizukuServiceImpl(context);
        SystemShizukuManagerImpl managerImpl = new SystemShizukuManagerImpl(context, store);

        // ------------------------------------------------------------------
        // Register with ServiceManager
        // ------------------------------------------------------------------
        try {
            ServiceManager.addService(SERVICE_NAME, serviceImpl,
                    /* allowIsolated= */ false,
                    ServiceManager.DUMP_FLAG_PRIORITY_DEFAULT);
            Log.i(TAG, "Registered: " + SERVICE_NAME);
        } catch (Exception e) {
            Log.wtf(TAG, "Failed to register " + SERVICE_NAME, e);
            System.exit(1);
        }

        try {
            ServiceManager.addService(MGR_SERVICE_NAME, managerImpl,
                    /* allowIsolated= */ false,
                    ServiceManager.DUMP_FLAG_PRIORITY_DEFAULT);
            Log.i(TAG, "Registered: " + MGR_SERVICE_NAME);
        } catch (Exception e) {
            Log.wtf(TAG, "Failed to register " + MGR_SERVICE_NAME, e);
            System.exit(1);
        }

        Log.i(TAG, "system_shizuku ready — entering event loop");
        Looper.loop();
    }
}
