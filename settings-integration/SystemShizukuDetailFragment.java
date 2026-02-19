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

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceFragmentCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settings.R;
import com.android.systemshizuku.ShizukuAuditEvent;
import com.android.systemshizuku.ShizukuPermission;

import java.util.Date;
import java.util.List;

/**
 * Detail screen for a single app's system_shizuku grant.
 *
 * <p>
 * Shows:
 * <ul>
 * <li>App icon + label
 * <li>Current grant status (granted / revoked / expired)
 * <li>Grant date and expiry (if applicable)
 * <li>"Revoke" button (only shown when the grant is currently active)
 * <li>Audit log: last 50 events for this package
 * </ul>
 *
 * <p>
 * There is deliberately no "Grant" button.
 *
 * <p>
 * Required arguments:
 * <ul>
 * <li>{@link #ARG_PACKAGE_NAME} — target package name
 * <li>{@link #ARG_USER_ID} — Android user ID
 * </ul>
 */
public class SystemShizukuDetailFragment extends PreferenceFragmentCompat {

    public static final String ARG_PACKAGE_NAME = "packageName";
    public static final String ARG_USER_ID = "userId";

    private SystemShizukuController mController;
    private String mPackageName;
    private int mUserId;

    private TextView mStatusText;
    private TextView mGrantedAtText;
    private Button mRevokeButton;
    private RecyclerView mAuditList;

    @Override
    public void onCreatePreferences(Bundle savedState, String rootKey) {
        // No XML preference screen.
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedState) {
        View view = inflater.inflate(
                R.layout.system_shizuku_detail, container, false);

        mStatusText = view.findViewById(R.id.tv_status);
        mGrantedAtText = view.findViewById(R.id.tv_granted_at);
        mRevokeButton = view.findViewById(R.id.btn_revoke);
        mAuditList = view.findViewById(R.id.recycler_audit);
        mAuditList.setLayoutManager(new LinearLayoutManager(getContext()));

        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedState) {
        super.onActivityCreated(savedState);

        Bundle args = requireArguments();
        mPackageName = args.getString(ARG_PACKAGE_NAME);
        mUserId = args.getInt(ARG_USER_ID, 0);
        mController = new SystemShizukuController(requireContext());

        populateHeader();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    // -----------------------------------------------------------------------
    // Population
    // -----------------------------------------------------------------------

    /** Sets the app icon and label in the fragment title area. */
    private void populateHeader() {
        View view = getView();
        if (view == null)
            return;
        ImageView icon = view.findViewById(R.id.img_app_icon);
        TextView label = view.findViewById(R.id.tv_app_label);
        TextView pkg = view.findViewById(R.id.tv_package_name);

        PackageManager pm = requireContext().getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(mPackageName, 0);
            icon.setImageDrawable(pm.getApplicationIcon(ai));
            label.setText(pm.getApplicationLabel(ai));
        } catch (PackageManager.NameNotFoundException e) {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
            label.setText(mPackageName);
        }
        pkg.setText(mPackageName);
    }

    /** Loads grant state and audit log from the service. */
    private void refresh() {
        ShizukuPermission perm = mController.getPermission(mPackageName, mUserId);
        bindGrantStatus(perm);

        List<ShizukuAuditEvent> events = mController.getAuditLog(mPackageName, mUserId);
        mAuditList.setAdapter(new AuditAdapter(events));
    }

    private void bindGrantStatus(@Nullable ShizukuPermission perm) {
        if (perm == null || !perm.granted) {
            mStatusText.setText(R.string.system_shizuku_status_not_granted);
            mGrantedAtText.setVisibility(View.GONE);
            mRevokeButton.setVisibility(View.GONE);
            return;
        }

        mStatusText.setText(R.string.system_shizuku_status_granted);
        mGrantedAtText.setVisibility(View.VISIBLE);
        mGrantedAtText.setText(requireContext().getString(
                R.string.system_shizuku_granted_at,
                DateFormat.getMediumDateFormat(requireContext())
                        .format(new Date(perm.grantedAt))));

        // Revoke button — only for active grants, not for already-revoked ones
        mRevokeButton.setVisibility(View.VISIBLE);
        mRevokeButton.setOnClickListener(v -> onRevokeClicked());
    }

    private void onRevokeClicked() {
        boolean ok = mController.revokePermission(mPackageName, mUserId);
        if (ok) {
            Toast.makeText(requireContext(),
                    R.string.system_shizuku_revoked_success, Toast.LENGTH_SHORT).show();
            refresh();
        } else {
            Toast.makeText(requireContext(),
                    R.string.system_shizuku_revoke_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // -----------------------------------------------------------------------
    // Audit log adapter
    // -----------------------------------------------------------------------

    private static class AuditAdapter
            extends RecyclerView.Adapter<AuditAdapter.VH> {

        private final List<ShizukuAuditEvent> mEvents;

        AuditAdapter(List<ShizukuAuditEvent> events) {
            mEvents = events;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.system_shizuku_audit_item, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ShizukuAuditEvent e = mEvents.get(position);
            holder.eventType.setText(eventTypeLabel(e.eventType));
            holder.eventAt.setText(
                    DateFormat.getMediumDateFormat(holder.itemView.getContext())
                            .format(new Date(e.eventAt)));
            holder.detail.setVisibility(
                    e.detail != null ? View.VISIBLE : View.GONE);
            holder.detail.setText(e.detail);
        }

        @Override
        public int getItemCount() {
            return mEvents.size();
        }

        private static String eventTypeLabel(int type) {
            switch (type) {
                case 1:
                    return "Granted";
                case 2:
                    return "Revoked";
                case 3:
                    return "Used";
                case 4:
                    return "Denied";
                case 5:
                    return "Expired";
                default:
                    return "Unknown (" + type + ")";
            }
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView eventType;
            TextView eventAt;
            TextView detail;

            VH(View v) {
                super(v);
                eventType = v.findViewById(R.id.tv_event_type);
                eventAt = v.findViewById(R.id.tv_event_at);
                detail = v.findViewById(R.id.tv_detail);
            }
        }
    }
}
