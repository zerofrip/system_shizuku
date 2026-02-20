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
├── aidl/moe/shizuku/server/
│   ├── IShizukuService.aidl            # Compatibility interface (v13)
│   └── IRemoteProcess.aidl             # Process streams/lifecycle interface
│
├── external/Shizuku-API/               # Git submodule for client-side library
│
├── sepolicy/
│   ├── system_shizuku.te               # Hardened SELinux domain
│   ├── service_contexts                # Service labels ("shizuku")
│   └── file_contexts                   # File labels
│
├── service/
│   ├── src/com/android/systemshizuku/
│   │   ├── SystemShizukuServer.java    # Entrypoint
│   │   ├── ShizukuCompatServiceImpl.java # compatibility layer
│   │   ├── RemoteProcessImpl.java      # Process binder implementation
│   │   └── SystemShizukuServiceImpl.java # Internal logic
│
└── settings-integration/               # Settings app Special Access components
```

---

## Features

### 1. Shizuku Compatibility Layer
- **Interface**: Implements `moe.shizuku.server.IShizukuService` exactly as the official Shizuku app.
- **Binder Name**: Registered as `"shizuku"` in `ServiceManager`.
- **Version**: Returns protocol version `13` (Modern Shizuku support).

### 2. Full Process Execution (`newProcess`)
- Executes shell commands as the `system` user (UID 1000).
- **Binary Compatibility**: Apps like SAI, LSPosed, and Swift Backup can use `newProcess()` to execute commands.
- **IRemoteProcess**: Transparent stream access via `ParcelFileDescriptor` for `stdin`, `stdout`, and `stderr`.

### 3. Production Hardening
- **Client-Death Cleanup**: Uses `DeathRecipient` to automatically kill child processes if the client app dies.
- **Resource Limits**: Enforces global (64) and per-UID (8) concurrent process limits.
- **Hardened I/O**: Multi-stage `FileDescriptor` extraction (Direct -> Reflected -> Field).
- **Security Auditing**: Structured `Slog` logging of all executed commands (cmd, UID, pkg).

### 4. SELinux Security
- Runs in a dedicated `system_shizuku` domain.
- **Enforcing Ready**: Includes `neverallow` rules to prevent privilege escalation or unauthorized file access.
- **Modern Logging**: Uses `logd` macros instead of direct device access.

---

## Build & Integration

### 1. AOSP Tree Placement
Place this repository at `packages/apps/SystemShizuku/`.

### 2. Device Configuration
Add the following to your `device.mk`:
```makefile
PRODUCT_PACKAGES += \
    SystemShizuku \
    com.android.systemshizuku.xml \
    privapp-permissions-systemshizuku.xml \
    init.system_shizuku.rc
```

### 3. Submodule Sync
Ensure the Shizuku-API submodule is initialized:
```bash
git submodule update --init --recursive
```

---

## Client Connection (Shizuku-API)

The included `external/Shizuku-API` is patched for direct connection. Client apps can connect via `ServiceManager` without requiring ADB or a Background Provider:

```java
// Example for client apps built with this library
if (Shizuku.pingService()) {
    // Service found directly via ServiceManager
}
```

---

## Security Invariants

- **User Consent**: permissions are only granted via the system-rendered `PermissionConsentActivity`.
- **Signature Protection**: `MANAGE_SYSTEM_SHIZUKU` is restricted to platform-signed apps (Settings).
- **UID Validation**: Every call is verified using `Binder.getCallingUid()`.
