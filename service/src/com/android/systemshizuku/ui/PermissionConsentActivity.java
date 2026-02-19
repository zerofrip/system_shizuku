/*
 * Copyright (C) 2026 The system_shizuku Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemshizuku.ui;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemshizuku.ISystemShizukuCallback;
import com.android.systemshizuku.R;
import com.android.systemshizuku.ShizukuPermission;
import com.android.systemshizuku.store.PermissionStore;

/**
 * System dialog displayed to the user when an app calls
 * {@link com.android.systemshizuku.ISystemShizukuService#requestPermission}.
 *
 * <h3>Launch contract</h3>
 * This activity is launched by
 * {@link com.android.systemshizuku.SystemShizukuServiceImpl}
 * via an explicit {@link android.content.Intent}. The following extras must
 * be supplied:
 * <ul>
 * <li>{@link #EXTRA_PACKAGE_NAME} — requesting package
 * <li>{@link #EXTRA_APP_ID} — requesting app ID
 * <li>{@link #EXTRA_USER_ID} — requesting user ID
 * <li>{@link #EXTRA_CALLBACK} — {@link ISystemShizukuCallback} Binder
 * </ul>
 *
 * <h3>Window type</h3>
 * Uses {@link WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY} so the
 * dialog appears above all other windows regardless of which app is in the
 * foreground. This requires {@code SYSTEM_ALERT_WINDOW} permission.
 *
 * <h3>Security</h3>
 * The activity has {@code android:exported="false"} — it can only be started
 * by processes within the {@code com.android.systemshizuku} package (i.e.
 * the service itself).
 */
public class PermissionConsentActivity extends Activity {

    private static final String TAG = "ShizukuConsent";

    /** Required string extra: requesting package name. */
    public static final String EXTRA_PACKAGE_NAME = "packageName";

    /** Required int extra: requesting app ID (uid without userId). */
    public static final String EXTRA_APP_ID = "appId";

    /** Required int extra: requesting Android user ID. */
    public static final String EXTRA_USER_ID = "userId";

    /**
     * Required IBinder extra: the {@link ISystemShizukuCallback} to invoke
     * once the user makes a decision.
     */
    public static final String EXTRA_CALLBACK = "callback";

    private String mPackageName;
    private int mAppId;
    private int mUserId;
    private ISystemShizukuCallback mCallback;
    private PermissionStore mStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Apply overlay window type so the dialog floats above everything.
        Window w = getWindow();
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.6f);

        setContentView(R.layout.activity_permission_consent);

        // Retrieve extras
        mPackageName = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
        mAppId = getIntent().getIntExtra(EXTRA_APP_ID, -1);
        mUserId = getIntent().getIntExtra(EXTRA_USER_ID, -1);
        IBinder rawCb = getIntent().getIBinderExtra(EXTRA_CALLBACK);

        if (mPackageName == null || mAppId < 0 || mUserId < 0 || rawCb == null) {
            Log.e(TAG, "Missing required extras — finishing");
            finish();
            return;
        }

        mCallback = ISystemShizukuCallback.Stub.asInterface(rawCb);
        mStore = new PermissionStore(getApplicationContext());

        // Populate UI
        populateAppInfo();

        // Wire up buttons
        Button allowBtn = findViewById(R.id.btn_allow);
        Button denyBtn = findViewById(R.id.btn_deny);

        allowBtn.setOnClickListener(v -> onUserAllow());
        denyBtn.setOnClickListener(v -> onUserDeny());
    }

    /**
     * Loads and displays the requesting app's icon and label.
     * Falls back to package name + generic icon on lookup failure.
     */
    private void populateAppInfo() {
        ImageView icon = findViewById(R.id.img_app_icon);
        TextView label = findViewById(R.id.tv_app_label);
        TextView pkg = findViewById(R.id.tv_package_name);

        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(mPackageName, 0);
            Drawable appIcon = pm.getApplicationIcon(ai);
            CharSequence appLabel = pm.getApplicationLabel(ai);
            icon.setImageDrawable(appIcon);
            label.setText(appLabel);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Package not found: " + mPackageName);
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
            label.setText(mPackageName);
        }
        pkg.setText(mPackageName);
    }

    // -----------------------------------------------------------------------
    // User decisions
    // -----------------------------------------------------------------------

    private void onUserAllow() {
        Log.i(TAG, "User ALLOWED " + mPackageName + " u" + mUserId);

        // Build and persist the grant record
        ShizukuPermission permission = new ShizukuPermission();
        permission.version = 1;
        permission.packageName = mPackageName;
        permission.appId = mAppId;
        permission.userId = mUserId;
        permission.granted = true;
        permission.grantedAt = System.currentTimeMillis();
        permission.expiresAt = 0; // persistent
        permission.flags = PermissionStore.FLAG_GRANT_PERSISTENT;
        permission.scope = null;

        mStore.putGrant(permission);

        // Create a session token as a plain Binder — the service will attach
        // a DeathRecipient to it in attachSession().
        IBinder sessionToken = new android.os.Binder();

        try {
            mCallback.onGranted(permission, sessionToken);
        } catch (RemoteException e) {
            Log.w(TAG, "onGranted callback failed (client died?)", e);
        }

        finish();
    }

    private void onUserDeny() {
        Log.i(TAG, "User DENIED " + mPackageName + " u" + mUserId);

        try {
            mCallback.onDenied(mPackageName, mUserId);
        } catch (RemoteException e) {
            Log.w(TAG, "onDenied callback failed (client died?)", e);
        }

        finish();
    }

    // -----------------------------------------------------------------------
    // Prevent accidental dismissal
    // -----------------------------------------------------------------------

    @Override
    public void onBackPressed() {
        // Back press counts as deny
        onUserDeny();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Swallow HOME / RECENTS so the dialog cannot be accidentally dismissed
        if (keyCode == KeyEvent.KEYCODE_HOME ||
                keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
