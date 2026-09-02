# Android architecture route

## Decision

Build the initial Android client as a Capacitor + Svelte application with a
small Kotlin Capacitor plugin. Svelte owns the profile and session UI. Kotlin
owns the SSH connection, host-key verification, private-key lifecycle, PTY
stream, resize, and cancellation.

This is deliberately not a Kotlin Multiplatform application: cross-platform
delivery is not a current requirement. The native plugin keeps security- and
lifecycle-sensitive work out of the WebView while preserving Svelte's fast UI
iteration.

## Session boundary

```text
Svelte UI / terminal view
  ↕ session ID, terminal frames, input, resize, release
Kotlin Capacitor plugin
  ↕ public-key SSH, host-key verification, PTY byte stream
remote herdr
```

Private-key material must never cross into JavaScript. Kotlin imports it into
app-private encrypted storage, with an AES wrapping key protected by Android
Keystore. A Connection Profile stores only non-secret destination data and a
reference to the Private Key. On first contact, the app presents the server
host-key fingerprint for explicit trust; a changed key fails closed.

Password and keyboard-interactive authentication are unsupported. The native
SSH implementation must decline both callbacks rather than merely hide their
fields in the UI.

## Herdr interaction

The smallest end-to-end route is an interactive SSH PTY that starts `herdr` on
the remote host. This follows Herdr's documented phone/tablet workflow and
retains its built-in narrow-screen shell: at the mobile width threshold, its
right-side **Switch** action opens workspace/tab/agent navigation, and
**Close** returns to the terminal without ending the session.

This is not exact parity with:

```sh
herdr --remote nuc-kep --remote-keybindings server
```

That command is a non-PTY SSH thin client that executes
`remote-client-bridge` and carries Herdr's framed bincode endpoint protocol.
Implementing that cross-language protocol is a separate future milestone, not
an MVP dependency.

## First technical slice

1. Spike `cbssh` in an Android Capacitor plugin against a disposable SSH host.
2. Verify public-key authentication, first-use/changing-host behaviour, PTY
   resize, ANSI I/O, and lifecycle cancellation.
3. Build Connection Profile setup, key import, host trust, and one remote
   `herdr` terminal session.
4. Validate xterm.js/WebView touch, IME, scrolling, and rendering on a phone.
   If this layer is unsuitable, replace only the terminal renderer with a
   native view; keep the Kotlin transport and key boundary unchanged.

## Evidence and adoption decision

No existing project can be adopted without a substantial fork. In particular,
`herdr-connect` is an Expo/Go LAN/Tailscale bearer-token companion and does
not currently support Android or direct private-key SSH. Apple-platform
references such as Heeler, herdrm, and Multiplex are useful design evidence,
not Android bases.

The complete research record, sources, comparisons, and open questions are in
FlickNote research note [#2085](https://flicknote.local/notes/2085),
“Android Herdr：采用现有项目还是走 Capacitor/Svelte 原生 SSH 路线”.
