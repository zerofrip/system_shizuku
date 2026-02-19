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
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceFragmentCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settings.R;
import com.android.systemshizuku.ShizukuPermission;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Settings › Privacy & Security › Special App Access › System Shizuku
 *
 * <p>
 * Shows two sections:
 * <ol>
 * <li><b>Granted</b> — apps that currently have an active, non-expired grant
 * <li><b>Denied</b> — apps whose permission has been revoked by the user
 * </ol>
 *
 * <p>
 * Tapping any entry opens {@link SystemShizukuDetailFragment} where the
 * user can revoke the permission or view the audit log.
 *
 * <p>
 * There is deliberately no "Add" / "Grant" action in this UI.
 */
public class SystemShizukuListFragment extends PreferenceFragmentCompat {

    private static final String TAG = "ShizukuList";

    private SystemShizukuController mController;
    private RecyclerView mRecyclerView;
    private ShizukuAppAdapter mAdapter;

    @Override
    public void onCreatePreferences(Bundle savedState, String rootKey) {
        // No XML-backed preference screen; we drive the UI via RecyclerView.
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedState) {
        // Inflate a simple RecyclerView layout; this should be defined in
        // Settings' res/layout/system_shizuku_list.xml
        View view = inflater.inflate(
                R.layout.system_shizuku_list, container, false);
        mRecyclerView = view.findViewById(R.id.recycler_view);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedState) {
        super.onActivityCreated(savedState);
        mController = new SystemShizukuController(requireContext());
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    // -----------------------------------------------------------------------
    // Data loading
    // -----------------------------------------------------------------------

    private void refresh() {
        int userId = getCurrentUserId();
        List<ShizukuPermission> all = mController.getGrantedPackages(userId);

        List<ShizukuPermission> granted = new ArrayList<>();
        List<ShizukuPermission> denied = new ArrayList<>();

        for (ShizukuPermission p : all) {
            if (p.granted)
                granted.add(p);
            else
                denied.add(p);
        }

        if (mAdapter == null) {
            mAdapter = new ShizukuAppAdapter(requireContext(), granted, denied,
                    this::onItemClick);
            mRecyclerView.setAdapter(mAdapter);
        } else {
            mAdapter.update(granted, denied);
        }
    }

    private void onItemClick(ShizukuPermission permission) {
        Bundle args = new Bundle();
        args.putString(SystemShizukuDetailFragment.ARG_PACKAGE_NAME,
                permission.packageName);
        args.putInt(SystemShizukuDetailFragment.ARG_USER_ID, permission.userId);

        SystemShizukuDetailFragment detail = new SystemShizukuDetailFragment();
        detail.setArguments(args);

        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, detail)
                .addToBackStack(null)
                .commit();
    }

    private int getCurrentUserId() {
        UserManager um = (UserManager) requireContext()
                .getSystemService(Context.USER_SERVICE);
        return UserHandle.myUserId();
    }

    // -----------------------------------------------------------------------
    // Adapter
    // -----------------------------------------------------------------------

    /**
     * RecyclerView adapter with two sections: Granted and Denied.
     *
     * <p>
     * Item types:
     * <ul>
     * <li>0 — section header
     * <li>1 — app row
     * </ul>
     */
    private static class ShizukuAppAdapter
            extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_HEADER = 0;
        private static final int TYPE_APP = 1;

        // Flat display list: each item is either a String header or a ShizukuPermission
        private final List<Object> mItems = new ArrayList<>();
        private final Context mContext;
        private final java.util.function.Consumer<ShizukuPermission> mClickListener;

        ShizukuAppAdapter(
                Context context,
                List<ShizukuPermission> granted,
                List<ShizukuPermission> denied,
                java.util.function.Consumer<ShizukuPermission> clickListener) {
            mContext = context;
            mClickListener = clickListener;
            buildItems(granted, denied);
        }

        void update(List<ShizukuPermission> granted, List<ShizukuPermission> denied) {
            mItems.clear();
            buildItems(granted, denied);
            notifyDataSetChanged();
        }

        private void buildItems(
                List<ShizukuPermission> granted, List<ShizukuPermission> denied) {
            if (!granted.isEmpty()) {
                mItems.add(mContext.getString(R.string.system_shizuku_granted_header));
                mItems.addAll(granted);
            }
            if (!denied.isEmpty()) {
                mItems.add(mContext.getString(R.string.system_shizuku_denied_header));
                mItems.addAll(denied);
            }
        }

        @Override
        public int getItemViewType(int position) {
            return mItems.get(position) instanceof String ? TYPE_HEADER : TYPE_APP;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(
                @NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                View v = inflater.inflate(R.layout.system_shizuku_header_item,
                        parent, false);
                return new HeaderVH(v);
            } else {
                View v = inflater.inflate(R.layout.system_shizuku_app_item,
                        parent, false);
                return new AppVH(v);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).title.setText((String) mItems.get(pos));
            } else if (holder instanceof AppVH) {
                ShizukuPermission perm = (ShizukuPermission) mItems.get(pos);
                AppVH vh = (AppVH) holder;
                PackageManager pm = mContext.getPackageManager();
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(perm.packageName, 0);
                    vh.icon.setImageDrawable(pm.getApplicationIcon(ai));
                    vh.label.setText(pm.getApplicationLabel(ai));
                } catch (PackageManager.NameNotFoundException e) {
                    vh.icon.setImageResource(android.R.drawable.sym_def_app_icon);
                    vh.label.setText(perm.packageName);
                }
                vh.grantedAt.setText(
                        perm.granted
                                ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                        .format(new Date(perm.grantedAt))
                                : mContext.getString(R.string.system_shizuku_revoked));
                vh.itemView.setOnClickListener(v -> mClickListener.accept(perm));
                vh.itemView.setAlpha(perm.granted ? 1.0f : 0.5f);
            }
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        static class HeaderVH extends RecyclerView.ViewHolder {
            TextView title;

            HeaderVH(View v) {
                super(v);
                title = v.findViewById(R.id.tv_section_header);
            }
        }

        static class AppVH extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView label;
            TextView grantedAt;

            AppVH(View v) {
                super(v);
                icon = v.findViewById(R.id.img_app_icon);
                label = v.findViewById(R.id.tv_app_label);
                grantedAt = v.findViewById(R.id.tv_granted_at);
            }
        }
    }
}
