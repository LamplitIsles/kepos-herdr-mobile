# Android one-target direct-SSH architecture

## Decision

The MVP is one Android APK built with Bun, Vite, Svelte, TypeScript, and
Capacitor 8. A small Kotlin Capacitor plugin is the only native boundary. The
product has one persisted Connection Target for the current Mac—not a profile
manager or a host list—and one foreground Remote Session.

```text
Svelte UI + xterm.js
  ↕ one target, public key, discovered services, session ID, terminal frames, errors
Kotlin HerdrSsh plugin
  ├─ AndroidKeyStoreDeviceKey (RSA-3072, non-exportable)
  ├─ SharedPreferencesMetadataStore (one target + one trust record only)
  ├─ AndroidNsdDiscovery (built-in _ssh._tcp mDNS/NSD, best effort)
  ├─ JschSshTransport (direct TCP, publickey only)
  └─ RemoteSessionController (trust, PTY, one-session lifecycle)
  ↕ SSH host key + public-key auth + PTY byte stream
current Mac running `herdr`
```

JavaScript never receives private-key bytes, a `PrivateKey`, a JSch identity,
or a socket/channel handle. It receives the public OpenSSH key line for manual
copy/share, optional NSD service metadata, the one target's metadata, base64
terminal frames, session IDs, and plain errors. `android:allowBackup="false"`
prevents Android backup from restoring metadata without the device Keystore
key.

## The single vertical route

1. `AndroidKeyStoreDeviceKey` lazily creates or reuses alias
   `herdr-device-rsa3072-v1` with RSA-3072 signing digests SHA-256/SHA-512.
   `OpenSshKeyCodec` exposes only the OpenSSH public blob/text.
2. `AndroidNsdDiscovery` asks Android's built-in `NsdManager` for `_ssh._tcp`
   services on the LAN for a short window. A resolved service fills host/port;
   manual hostname/IP entry remains available when the scan returns nothing or
   fails. There is no discovery dependency outside Android.
3. The user saves one `ConnectionTarget` (host, port, user). Saving a different
   host or port clears the old Host Trust Record so a key decision cannot be
   applied to the corrected destination.
4. `JschSshTransport` performs a host-key exchange with
   `StrictHostKeyChecking=yes` and compares the observed SHA-256 fingerprint
   with the one app-private Host Trust Record. Unknown keys return
   `trust-required`; mismatches return `fingerprint-mismatch`. Neither path
   persists anything or silently overrides a record.
5. Explicit first-use confirmation or the separate replacement action persists
   the exact observed fingerprint. A trusted connection configures
   `PreferredAuthentications=publickey` and
   `PubkeyAcceptedAlgorithms=rsa-sha2-512,rsa-sha2-256`, then authenticates with
   the Keystore-backed JSch `Identity`.
6. One `ChannelShell` requests a PTY of the current dimensions, sets
   `xterm-256color`, and writes `herdr`. A native reader streams bytes as
   terminal-frame events; input and window changes travel back through the
   controller. A second session is rejected.
7. `handleOnStop` and `handleOnDestroy`, plus an explicit release call, close
   the channel and session and emit a disconnected event.

## Dependencies and exclusions

- Native SSH: `com.github.mwiede:jsch:2.28.7`, selected for its custom
  `Identity#getSignature(data, algorithm)` seam and RSA SHA-2 support.
- Discovery: Android `NsdManager` only; no non-Android mDNS/NSD library.
- Terminal: `@xterm/xterm` 6 with `@xterm/addon-fit`.
- Build: Bun, Vite, Svelte, TypeScript, Capacitor 8, Kotlin/JVM 21.

Apache MINA SSHD is not selected because its Android compatibility is not
thoroughly tested for this use. `herdr --remote` is excluded because it launches
`remote-client-bridge` and exchanges a framed bincode protocol rather than this
MVP's direct interactive PTY. Profile CRUD, host lists, dashboards, settings,
iOS/KMP/PWA delivery, background services, passwords, key import/export, SFTP,
forwarding, discovery beyond Android NSD, VPN/Tailscale, notifications, and
speech are outside this one-target slice.

## Validation evidence

`bun run check`, `bun test`, and Android `testDebugUnitTest`/`assembleDebug` are
automated checks. The required real-device check is documented in the README:
use the current Mac or a disposable SSH account, manually deploy the public
key, independently verify first-use and changed fingerprints, exercise PTY
input/output and resize, then background the app and confirm release.
