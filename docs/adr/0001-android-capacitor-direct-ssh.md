# ADR 0001: Android one-target Capacitor app with direct SSH

- Status: Accepted
- Date: 2026-09-02

## Context

The first mobile release needs one secure, narrow route from an Android phone
to the current Mac running Herdr. It must keep an app-generated private key out
of JavaScript, make host-key changes visible, discover the Mac when the LAN
advertises SSH, and release a live connection when Android backgrounds the app.
Cross-platform parity, general host management, and the existing remote bridge
are not requirements for this slice.

## Decision

Ship an Android-only Capacitor 8 app with a Svelte/TypeScript WebView and one
small Kotlin Capacitor plugin. The plugin owns Android Keystore RSA-3072
generation/signing, one app-private Connection Target and Host Trust Record,
best-effort Android `NsdManager` `_ssh._tcp` discovery, direct TCP SSH, host
fingerprint policy, one `xterm-256color` PTY shell, byte streaming, resize, and
lifecycle release.

Use `com.github.mwiede:jsch:2.28.7` for native SSH. Its custom JSch `Identity`
signature seam lets the plugin sign with Android Keystore without giving JSch
or the WebView private key bytes. Use `@xterm/xterm` 6 and
`@xterm/addon-fit` for the touch/IME-capable terminal renderer.

## Alternatives considered

- Apache MINA SSHD: rejected for this MVP because its own Android guidance says
  Android compatibility has not been thoroughly tested; it would add risk and
  a second key/signing integration seam.
- `herdr --remote` / `remote-client-bridge`: rejected because it is a
  non-PTY thin client with a framed bincode protocol and would broaden the
  boundary beyond an interactive terminal.
- A companion service, LAN/Tailscale/VPN setup, or a non-Android discovery
  library: rejected because direct TCP and Android's built-in NSD are sufficient
  for the current Mac route, with manual hostname/IP fallback.
- General profiles/host lists, dashboards, settings, or iOS/Kotlin
  Multiplatform: deferred because this release has one Connection Target and
  one focused terminal route.

## Consequences

The APK has a small auditable native boundary, convenient LAN discovery, and a
manual path when discovery is absent. The trade-off is exactly one active
target/session, no background work, no key import/export or rotation, and a
required disposable-host manual check in addition to fake-based JVM/TypeScript
tests.
