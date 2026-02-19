# system_shizuku

> A **built-in, root-free, Shizuku-compatible system service** for custom ROMs.
> Provides elevated IPC to authorised apps via a user-consent dialog — no ADB,
> Magisk, or Zygisk required.

---

## Repository Layout

```
system_shizuku/
│
├── Android.bp                          # Soong build rules
├── init.system_shizuku.rc              # init.rc service definition
│
├── aidl/
│   └── com/android/systemshizuku/
│       ├── ShizukuPermission.aidl      # Grant-record parcelable
│       ├── ShizukuAuditEvent.aidl      # Audit-log entry parcelable
│       ├── ISystemShizukuCallback.aidl # One-shot grant/deny callback
│       ├── ISystemShizukuService.aidl  # Public app interface
│       └── ISystemShizukuManager.aidl  # System-only management interface
│
├── permissions/
│   ├── com.android.systemshizuku.xml              # Platform permission declaration
│   └── privapp-permissions-systemshizuku.xml      # Priv-app permission whitelist
│
├── service/
│   ├── AndroidManifest.xml
│   └── src/com/android/systemshizuku/
│       ├── SystemShizukuServer.java        # Process entrypoint (main())
│       ├── SystemShizukuServiceImpl.java   # ISystemShizukuService impl stub
│       └── SystemShizukuManagerImpl.java   # ISystemShizukuManager impl stub
│
└── settings-integration/
    └── SystemShizukuController.java       # Settings Special App Access controller
```

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  App Process                                                 │
│  ServiceManager.getService("shizuku")                        │
│  → ISystemShizukuService (public)                            │
│    requestPermission()  getMyPermission()  attachSession()   │
└───────────────────────────────┬──────────────────────────────┘
                                │ Binder IPC
                                ▼
┌──────────────────────────────────────────────────────────────┐
│  system_shizuku process (uid=system)                         │
│                                                              │
│  SystemShizukuServiceImpl  ──call──▶  PermissionConsentActivity│
│  SystemShizukuManagerImpl                                    │
│                                                              │
│  ServiceManager.getService("shizuku_mgr")                    │
└───────────────────────────────┬──────────────────────────────┘
                                │ Binder IPC
                                ▼
┌──────────────────────────────────────────────────────────────┐
│  Settings Process (priv-app, holds MANAGE_SYSTEM_SHIZUKU)    │
│  ISystemShizukuManager (read-only + revoke — no grant)       │
│  → SystemShizukuController → list / revoke UI                │
└──────────────────────────────────────────────────────────────┘
```

---

## AIDL Design Summary

| Interface | Registered name | Callers | Capabilities |
|---|---|---|---|
| `ISystemShizukuService` | `shizuku` | Any installed app | Request permission, self-query, session attach |
| `ISystemShizukuManager` | `shizuku_mgr` | `MANAGE_SYSTEM_SHIZUKU` holders only | List, query, revoke, audit log |

**Key design decisions:**

- **No grant API in Settings** — permission granting is exclusively via the service's system dialog.
- **Split interfaces** — strong trust-domain separation between the app-facing and management APIs.
- **Session tokens** — the callback delivers an `IBinder` the service holds a `DeathRecipient` on; `GRANT_SESSION_ONLY` grants auto-revoke when the app process dies.
- **userId everywhere** — `appId` (uid without userId) is stored separately from `userId` so records survive user re-creation.
- **Versioned parcelables** — `ShizukuPermission.version` allows fields to be added without breaking old clients.

---

## Build Integration

1. Place this directory at `packages/apps/SystemShizuku/` in your AOSP tree.
2. Add to your device's `PRODUCT_PACKAGES` in `device.mk`:
   ```makefile
   PRODUCT_PACKAGES += \
       SystemShizuku \
       com.android.systemshizuku.xml \
       privapp-permissions-systemshizuku.xml \
       init.system_shizuku.rc
   ```
3. Add to your Settings app's build dependencies:
   ```
   static_libs: ["system_shizuku_aidl"]
   ```
4. Add SELinux policy entries (see `sepolicy/` — to be added).

---

## Settings Integration

Wire the preference in Settings' `special_access.xml`:

```xml
<Preference
    android:key="system_shizuku"
    android:title="@string/system_shizuku_title"
    android:fragment="com.android.settings.applications.specialaccess.systemshizuku.SystemShizukuListFragment" />
```

`SystemShizukuController` (in `settings-integration/`) handles all data
loading and revocation. Add it to the Settings module build.

---

## Security Notes

- `MANAGE_SYSTEM_SHIZUKU` is `signature|privileged` — only platform-signed or
  explicitly whitelisted priv-apps can hold it.
- The service enforces `Binder.getCallingUid()` on every API call.
- Rate limiting on `requestPermission` prevents dialog-spam attacks.
- Permanent deny (REVOKED_BY_USER) blocks future dialogs without removing the record.
