/*
 * Copyright (C) 2026 The system_shizuku Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemshizuku.store;

import android.content.Context;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.Log;

import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKey;

import com.android.systemshizuku.ShizukuAuditEvent;
import com.android.systemshizuku.ShizukuPermission;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Persistent, encrypted storage of {@link ShizukuPermission} grant records
 * and {@link ShizukuAuditEvent} log entries.
 *
 * <h3>Storage layout</h3>
 * Each Android user gets its own file:
 * 
 * <pre>
 *   /data/system/system_shizuku/grants_u{userId}.json   (encrypted)
 *   /data/system/system_shizuku/audit_u{userId}.json    (encrypted)
 * </pre>
 * 
 * Files are stored in credential-encrypted storage (/data/system/) which is
 * only accessible after the user's first unlock. The service must defer
 * operations that require the store until {@code ACTION_USER_UNLOCKED}.
 *
 * <h3>Encryption</h3>
 * Uses {@link EncryptedFile} from Jetpack Security with AES256-GCM content
 * encryption keyed by the platform's {@link MasterKey}.
 *
 * <h3>JSON schema (grants file)</h3>
 * 
 * <pre>
 * {
 *   "version": 1,
 *   "grants": [
 *     {
 *       "version": 1,
 *       "packageName": "com.example.app",
 *       "appId": 10042,
 *       "userId": 0,
 *       "granted": true,
 *       "grantedAt": 1708326000000,
 *       "expiresAt": 0,
 *       "flags": 1,
 *       "scope": null
 *     }
 *   ]
 * }
 * </pre>
 *
 * <h3>Thread safety</h3>
 * A per-userId {@link ReentrantReadWriteLock} guards all I/O.
 * Multiple users may be read concurrently; writes are exclusive per user.
 */
public final class PermissionStore {

    private static final String TAG = "ShizukuStore";

    /** Maximum audit-log entries retained per user (oldest trimmed first). */
    private static final int MAX_AUDIT_ENTRIES = 200;

    /** File-schema version written to every grants / audit JSON file. */
    private static final int FILE_FORMAT_VERSION = 1;

    // Flags duplicated here for readability (keep in sync with ShizukuPermission
    // docs)
    public static final int FLAG_GRANT_PERSISTENT = 0x1;
    public static final int FLAG_GRANT_SESSION_ONLY = 0x2;
    public static final int FLAG_REVOKED_BY_USER = 0x4;
    public static final int FLAG_REVOKED_BY_POLICY = 0x8;

    // Audit event types (keep in sync with ShizukuAuditEvent docs)
    public static final int EVENT_GRANT = 1;
    public static final int EVENT_REVOKE = 2;
    public static final int EVENT_USE = 3;
    public static final int EVENT_DENY = 4;
    public static final int EVENT_EXPIRE = 5;

    private final Context mContext;
    private final File mBaseDir;

    // Per-userId read-write locks. Access to this map itself is synchronized.
    private final java.util.HashMap<Integer, ReentrantReadWriteLock> mLocks = new java.util.HashMap<>();

    public PermissionStore(Context context) {
        mContext = context;
        mBaseDir = new File("/data/system/system_shizuku");
        // noinspection ResultOfMethodCallIgnored
        mBaseDir.mkdirs();
    }

    // -----------------------------------------------------------------------
    // Public API — grants
    // -----------------------------------------------------------------------

    /**
     * Returns all grant records for {@code userId}.
     * Returns an empty list if the file does not yet exist or decryption fails.
     */
    public List<ShizukuPermission> getGrants(int userId) {
        ReentrantReadWriteLock.ReadLock lock = getLock(userId).readLock();
        lock.lock();
        try {
            JSONObject root = readJson(grantsFile(userId));
            if (root == null)
                return Collections.emptyList();
            return parseGrants(root);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the grant record for {@code packageName} in {@code userId},
     * or {@code null} if none exists.
     */
    public ShizukuPermission getGrant(String packageName, int userId) {
        for (ShizukuPermission p : getGrants(userId)) {
            if (packageName.equals(p.packageName))
                return p;
        }
        return null;
    }

    /**
     * Writes (insert or update) a grant record.
     *
     * @param permission the record to persist; must have a non-null packageName
     */
    public void putGrant(ShizukuPermission permission) {
        int userId = permission.userId;
        ReentrantReadWriteLock.WriteLock lock = getLock(userId).writeLock();
        lock.lock();
        try {
            List<ShizukuPermission> list = getGrantsLocked(userId);
            // Remove existing record for the same package
            list.removeIf(p -> permission.packageName.equals(p.packageName));
            list.add(permission);
            writeGrants(userId, list);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks the grant for {@code packageName} as revoked (sets
     * {@code granted = false} and ORs in {@code FLAGS_REVOKED_BY_USER}).
     * No-op if no record exists.
     *
     * @return the updated record, or {@code null} if no record existed
     */
    public ShizukuPermission revokeGrant(String packageName, int userId) {
        ReentrantReadWriteLock.WriteLock lock = getLock(userId).writeLock();
        lock.lock();
        try {
            List<ShizukuPermission> list = getGrantsLocked(userId);
            for (int i = 0; i < list.size(); i++) {
                ShizukuPermission p = list.get(i);
                if (packageName.equals(p.packageName)) {
                    p.granted = false;
                    p.flags |= FLAG_REVOKED_BY_USER;
                    list.set(i, p);
                    writeGrants(userId, list);
                    return p;
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Revokes all grants for {@code userId} in a single write.
     */
    public List<ShizukuPermission> revokeAll(int userId) {
        ReentrantReadWriteLock.WriteLock lock = getLock(userId).writeLock();
        lock.lock();
        try {
            List<ShizukuPermission> list = getGrantsLocked(userId);
            for (ShizukuPermission p : list) {
                p.granted = false;
                p.flags |= FLAG_REVOKED_BY_USER;
            }
            writeGrants(userId, list);
            return Collections.unmodifiableList(list);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes ALL records for {@code userId} (e.g. on user deletion).
     */
    public void deleteUser(int userId) {
        ReentrantReadWriteLock.WriteLock lock = getLock(userId).writeLock();
        lock.lock();
        try {
            // noinspection ResultOfMethodCallIgnored
            grantsFile(userId).delete();
            // noinspection ResultOfMethodCallIgnored
            auditFile(userId).delete();
        } finally {
            lock.unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Public API — audit log
    // -----------------------------------------------------------------------

    /**
     * Appends an audit event. Oldest entries are trimmed if the log exceeds
     * {@link #MAX_AUDIT_ENTRIES}.
     */
    public void appendAudit(ShizukuAuditEvent event) {
        int userId = event.userId;
        ReentrantReadWriteLock.WriteLock lock = getLock(userId).writeLock();
        lock.lock();
        try {
            List<ShizukuAuditEvent> list = getAuditLocked(userId);
            list.add(0, event); // newest first
            if (list.size() > MAX_AUDIT_ENTRIES) {
                list = list.subList(0, MAX_AUDIT_ENTRIES);
            }
            writeAudit(userId, list);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns audit events for {@code userId}, newest first.
     * If {@code packageName} is non-null, only events for that package are
     * returned.
     */
    public List<ShizukuAuditEvent> getAudit(String packageName, int userId) {
        ReentrantReadWriteLock.ReadLock lock = getLock(userId).readLock();
        lock.lock();
        try {
            List<ShizukuAuditEvent> all = getAuditLocked(userId);
            if (packageName == null)
                return Collections.unmodifiableList(all);
            List<ShizukuAuditEvent> filtered = new ArrayList<>();
            for (ShizukuAuditEvent e : all) {
                if (packageName.equals(e.packageName))
                    filtered.add(e);
            }
            return Collections.unmodifiableList(filtered);
        } finally {
            lock.unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers — lock management
    // -----------------------------------------------------------------------

    private synchronized ReentrantReadWriteLock getLock(int userId) {
        return mLocks.computeIfAbsent(userId, k -> new ReentrantReadWriteLock());
    }

    // -----------------------------------------------------------------------
    // Helpers — read (must be called with appropriate lock held)
    // -----------------------------------------------------------------------

    private List<ShizukuPermission> getGrantsLocked(int userId) {
        JSONObject root = readJson(grantsFile(userId));
        if (root == null)
            return new ArrayList<>();
        return parseGrants(root);
    }

    private List<ShizukuAuditEvent> getAuditLocked(int userId) {
        JSONObject root = readJson(auditFile(userId));
        if (root == null)
            return new ArrayList<>();
        return parseAudit(root);
    }

    // -----------------------------------------------------------------------
    // Helpers — write
    // -----------------------------------------------------------------------

    private void writeGrants(int userId, List<ShizukuPermission> list) {
        try {
            JSONArray arr = new JSONArray();
            for (ShizukuPermission p : list)
                arr.put(toJson(p));
            JSONObject root = new JSONObject();
            root.put("version", FILE_FORMAT_VERSION);
            root.put("grants", arr);
            writeJson(grantsFile(userId), root);
        } catch (JSONException e) {
            Log.e(TAG, "writeGrants failed", e);
        }
    }

    private void writeAudit(int userId, List<ShizukuAuditEvent> list) {
        try {
            JSONArray arr = new JSONArray();
            for (ShizukuAuditEvent e : list)
                arr.put(toJson(e));
            JSONObject root = new JSONObject();
            root.put("version", FILE_FORMAT_VERSION);
            root.put("events", arr);
            writeJson(auditFile(userId), root);
        } catch (JSONException e) {
            Log.e(TAG, "writeAudit failed", e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers — EncryptedFile I/O
    // -----------------------------------------------------------------------

    private JSONObject readJson(File file) {
        if (!file.exists())
            return null;
        try {
            EncryptedFile ef = buildEncryptedFile(file);
            InputStream in = ef.openFileInput();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) != -1)
                buf.write(tmp, 0, n);
            in.close();
            return new JSONObject(buf.toString(StandardCharsets.UTF_8.name()));
        } catch (IOException | GeneralSecurityException | JSONException e) {
            Log.e(TAG, "readJson failed for " + file, e);
            return null;
        }
    }

    private void writeJson(File file, JSONObject root) {
        // Delete first: EncryptedFile will not overwrite an existing file.
        // noinspection ResultOfMethodCallIgnored
        file.delete();
        try {
            EncryptedFile ef = buildEncryptedFile(file);
            OutputStream out = ef.openFileOutput();
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (IOException | GeneralSecurityException e) {
            Log.e(TAG, "writeJson failed for " + file, e);
        }
    }

    private EncryptedFile buildEncryptedFile(File file)
            throws GeneralSecurityException, IOException {
        MasterKey masterKey = new MasterKey.Builder(mContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return new EncryptedFile.Builder(
                mContext,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
                .build();
    }

    // -----------------------------------------------------------------------
    // Helpers — JSON ↔ parcelable
    // -----------------------------------------------------------------------

    private static List<ShizukuPermission> parseGrants(JSONObject root) {
        List<ShizukuPermission> result = new ArrayList<>();
        try {
            JSONArray arr = root.optJSONArray("grants");
            if (arr == null)
                return result;
            for (int i = 0; i < arr.length(); i++) {
                result.add(grantFromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            Log.e(TAG, "parseGrants failed", e);
        }
        return result;
    }

    private static List<ShizukuAuditEvent> parseAudit(JSONObject root) {
        List<ShizukuAuditEvent> result = new ArrayList<>();
        try {
            JSONArray arr = root.optJSONArray("events");
            if (arr == null)
                return result;
            for (int i = 0; i < arr.length(); i++) {
                result.add(auditFromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            Log.e(TAG, "parseAudit failed", e);
        }
        return result;
    }

    private static ShizukuPermission grantFromJson(JSONObject o) throws JSONException {
        ShizukuPermission p = new ShizukuPermission();
        p.version = o.optInt("version", 1);
        p.packageName = o.getString("packageName");
        p.appId = o.getInt("appId");
        p.userId = o.getInt("userId");
        p.granted = o.getBoolean("granted");
        p.grantedAt = o.getLong("grantedAt");
        p.expiresAt = o.optLong("expiresAt", 0);
        p.flags = o.optInt("flags", FLAG_GRANT_PERSISTENT);
        p.scope = o.isNull("scope") ? null : o.getString("scope");
        return p;
    }

    private static JSONObject toJson(ShizukuPermission p) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("version", p.version);
        o.put("packageName", p.packageName);
        o.put("appId", p.appId);
        o.put("userId", p.userId);
        o.put("granted", p.granted);
        o.put("grantedAt", p.grantedAt);
        o.put("expiresAt", p.expiresAt);
        o.put("flags", p.flags);
        o.put("scope", p.scope != null ? p.scope : JSONObject.NULL);
        return o;
    }

    private static ShizukuAuditEvent auditFromJson(JSONObject o) throws JSONException {
        ShizukuAuditEvent e = new ShizukuAuditEvent();
        e.version = o.optInt("version", 1);
        e.eventType = o.getInt("eventType");
        e.packageName = o.getString("packageName");
        e.appId = o.getInt("appId");
        e.userId = o.getInt("userId");
        e.eventAt = o.getLong("eventAt");
        e.detail = o.isNull("detail") ? null : o.getString("detail");
        return e;
    }

    private static JSONObject toJson(ShizukuAuditEvent e) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("version", e.version);
        o.put("eventType", e.eventType);
        o.put("packageName", e.packageName);
        o.put("appId", e.appId);
        o.put("userId", e.userId);
        o.put("eventAt", e.eventAt);
        o.put("detail", e.detail != null ? e.detail : JSONObject.NULL);
        return o;
    }

    // -----------------------------------------------------------------------
    // Helpers — file paths
    // -----------------------------------------------------------------------

    private File grantsFile(int userId) {
        return new File(mBaseDir, "grants_u" + userId + ".json");
    }

    private File auditFile(int userId) {
        return new File(mBaseDir, "audit_u" + userId + ".json");
    }
}
