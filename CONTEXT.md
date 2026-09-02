# Ubiquitous Language

## Device Key

The single RSA-3072 SSH signing key generated inside Android Keystore for this
app installation. Its private material is non-exportable and never crosses the
native boundary. The user-facing artifact is its OpenSSH public-key text,
which the user manually adds to the current Mac account.

## Generated Key

The product term for the one Device Key created by the app. This release does
not import, rotate, delete, or manage additional keys.

## Connection Target

The one saved, non-secret direct SSH destination for the current Mac: hostname
or LAN IP, port, and user. Android NSD may suggest its host and port, but manual
entry is always available. Saving a changed host or port clears its trust
decision; there is no target list or multi-target CRUD surface.

## Host Trust Record

The SHA-256 fingerprint explicitly approved for the one Connection Target.
First-use approval and replacement are separate visible actions. A changed
fingerprint fails closed until deliberate replacement.

## Remote Session

The one foreground interactive SSH PTY opened from the trusted Connection
Target. It requests `xterm-256color`, starts `herdr`, streams terminal frames,
forwards input and resize, and is released when the app is backgrounded, closed,
or the user leaves the terminal.

## Android LAN discovery

Best-effort resolution of `_ssh._tcp` through Android's built-in
`NsdManager`. Discovery is a convenience, not a trust decision or a required
service; manual hostname/IP entry is the fallback.

## Direct SSH boundary

The plugin connects to the target host and port over TCP. There is no companion
service, remote bridge protocol, VPN/Tailscale setup, proxy, port forwarding,
SFTP/SCP, agent forwarding, host discovery beyond Android NSD, dashboard, or
background session.
