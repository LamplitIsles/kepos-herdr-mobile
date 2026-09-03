# Herdr Mobile

Herdr Mobile is a deliberately narrow Android client for operating Herdr on
the current Mac: one app-generated Device Key, one direct SSH Connection
Target, explicit host-key trust, and one foreground `herdr` terminal. It is not
a companion service and does not implement Herdr's remote bridge protocol.

## Prerequisites

- Bun 1.3+ (the checked-in `bun.lock` is the JavaScript dependency lockfile)
- Android Studio or the Android SDK with API 36 and an emulator or device
- JDK 21 (`JAVA_HOME` must point at it for the Android build)
- The current Mac on the same LAN, with SSH enabled and `herdr` installed

## Build and install

```sh
bun install
bun run check
bun test
bun run build
bun run android:apk
bun run android:install   # optional: install through adb
```

`bun run android:apk` builds the WebView assets before Capacitor syncs them, so
the APK does not depend on a development server. The debug APK is written to
`android/app/build/outputs/apk/debug/app-debug.apk`.

## The one-target user route

1. Open the app. It creates one non-exportable RSA-3072 Device Key in Android
   Keystore. Use **Copy key** or **Share** to move only the OpenSSH public-key
   line out of the app.
2. On the Mac, manually append that line to the target account's
   `~/.ssh/authorized_keys`. The app never installs a key and never exposes a
   private-key byte or handle.
3. The app makes a best-effort scan for Android NSD/mDNS `_ssh._tcp` services on
   the LAN. Choose the Mac when it appears, or enter its `.local` hostname/LAN
   IP manually when discovery is unavailable, then enter the Mac user and SSH
   port (normally 22). There is only one saved Connection Target; saving a
   changed host or port clears its old trust decision.
4. Choose the saved Host. On first contact, compare the displayed
   `SHA256:...` fingerprint with an independent check on the Mac, then choose
   **Trust & connect**. The Host Trust Record is persisted only after that
   action.
5. The app opens one `xterm-256color` SSH PTY and writes `herdr`. The active
   terminal occupies the phone viewport; tap controls normally and drag
   vertically to send Herdr mouse-wheel input. Leave the terminal or
   background/close the app to release the native session.
6. If a later connection reports a changed fingerprint, stop and verify the
   Mac. Only the explicit **Replace & connect** action can replace the Host
   Trust Record; there is no silent override.

For a disposable Mac-side check, `ssh-keygen -lf
/etc/ssh/ssh_host_ed25519_key.pub -E sha256` is one way to inspect the host
fingerprint. Use the actual host key algorithm reported by SSH, compare it
carefully, and remove the disposable account/key after validation.

## Native boundary

The Svelte/TypeScript layer receives one target's metadata, a public-key string,
discovered service metadata, session identifiers, base64 terminal frames, and
plain actionable errors. The Kotlin Capacitor plugin owns Android Keystore
access, the JSch signing identity, Android NSD discovery, direct TCP SSH,
fingerprint comparison, PTY I/O, resize, and lifecycle release. Password and
keyboard-interactive authentication are not configured.

The native SSH client is `com.github.mwiede:jsch:2.28.7`. The terminal uses
`@xterm/xterm` 6 and `@xterm/addon-fit`; Bun/Vite/Svelte build bundled WebView
assets for Android.

## Verification

Automated checks use test-owned fakes and never read a real Android Keystore,
live app state, or production host:

```sh
bun run check
bun test
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest assembleDebug
```

The remaining manual acceptance check is device- and host-specific: install
the debug APK on an Android phone, discover or manually enter the Mac, deploy
the public key, verify first-use trust independently, start `herdr`, exchange
terminal input/output, resize it, background the app, and repeat with a changed
host fingerprint to confirm rejection.

## Deliberate exclusions

This release is Android-only and has exactly one Connection Target. It does not
promise iOS, web/PWA distribution, Kotlin Multiplatform, profile/host lists,
multi-target CRUD, action/activity/project dashboards, settings screens,
companion services, non-Android discovery libraries, VPN/Tailscale setup,
remote bridge parity, `herdr --remote`, key import/export/rotation/passphrases,
Ed25519, password or keyboard-interactive login, SFTP/SCP, forwarding, agent
forwarding, SSH config parsing, background sessions, notifications, or speech
transcription.

See [the architecture decision](docs/architecture.md),
[the accepted ADR](docs/adr/0001-android-capacitor-direct-ssh.md), and the
[ubiquitous language](CONTEXT.md) for the boundary and terms.
