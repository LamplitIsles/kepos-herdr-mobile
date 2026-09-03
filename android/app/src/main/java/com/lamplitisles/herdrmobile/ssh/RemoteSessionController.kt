package com.lamplitisles.herdrmobile.ssh

import java.util.UUID

/** Native boundary for one Connection Target and one foreground Remote Session. */
class RemoteSessionController(
    private val targetRepository: TargetRepository,
    private val trust: HostTrustRepository,
    private val identity: DeviceKeyIdentity,
    private val transport: SshTransport,
    private val events: SessionEventSink = NoopSessionEventSink
) {
    private data class ActiveSession(val id: String, val connection: SshConnection)
    private var active: ActiveSession? = null
    private var pendingFingerprint: String? = null

    @Synchronized
    fun deviceKey(): DeviceKeyIdentity = identity

    @Synchronized
    fun target(): ConnectionTarget? = targetRepository.get()

    @Synchronized
    fun saveTarget(target: ConnectionTarget) {
        val previous = targetRepository.get()
        if (previous != null && (previous.host != target.host || previous.port != target.port)) {
            trust.clear()
            pendingFingerprint = null
        }
        targetRepository.save(target)
    }

    @Synchronized
    fun connect(size: TerminalSize): ConnectOutcome {
        if (!size.isUsable()) return ConnectOutcome.Failed("invalid-terminal-size", "The terminal size is invalid.")
        if (active != null) {
            return ConnectOutcome.Failed("session-active", "A terminal session is already open. Leave it before connecting again.")
        }
        // A trust prompt belongs to one concrete handshake. Never carry an
        // observation from an earlier or failed attempt into a new connect.
        pendingFingerprint = null
        val target = targetRepository.get()
            ?: return ConnectOutcome.Failed("target-not-found", "Add a Connection Target before connecting.")
        val expected = trust.fingerprint()
        val sessionId = UUID.randomUUID().toString()
        val result = try {
            transport.connect(
                target = target,
                identity = identity,
                expectedFingerprint = expected,
                size = size,
                callbacks = object : TerminalCallbacks {
                    override fun onFrame(data: ByteArray) = events.onFrame(sessionId, data)
                    override fun onClosed(reason: String?) = onTransportClosed(sessionId, reason)
                }
            )
        } catch (_: Exception) {
            TransportResult.Failed("connection-failed", "Could not connect to the SSH host.")
        }
        return when (result) {
            is TransportResult.Connected -> {
                pendingFingerprint = null
                active = ActiveSession(sessionId, result.connection)
                ConnectOutcome.Connected(sessionId, result.fingerprint)
            }
            is TransportResult.TrustRequired -> {
                pendingFingerprint = result.fingerprint
                ConnectOutcome.TrustRequired(result.fingerprint)
            }
            is TransportResult.FingerprintMismatch -> {
                pendingFingerprint = result.observed
                ConnectOutcome.FingerprintMismatch(result.expected, result.observed)
            }
            is TransportResult.Failed -> ConnectOutcome.Failed(result.code, result.message)
        }
    }

    @Synchronized
    fun confirmHostTrust(fingerprint: String): TrustOutcome = savePendingTrust(fingerprint, replacing = false)

    @Synchronized
    fun replaceHostTrust(fingerprint: String): TrustOutcome = savePendingTrust(fingerprint, replacing = true)

    @Synchronized
    fun activate(sessionId: String): SessionCommandOutcome {
        val current = active ?: return SessionCommandOutcome.Failed("not-connected", "The terminal is disconnected.")
        if (current.id != sessionId) return SessionCommandOutcome.Failed("stale-session", "That terminal session is no longer active.")
        return try {
            current.connection.start()
            SessionCommandOutcome.Accepted
        } catch (_: Exception) {
            releaseLocked(sessionId)
            SessionCommandOutcome.Failed("start-failed", "The terminal could not be started.")
        }
    }

    @Synchronized
    fun sendInput(sessionId: String, data: ByteArray): SessionCommandOutcome {
        val current = active ?: return SessionCommandOutcome.Failed("not-connected", "The terminal is disconnected.")
        if (current.id != sessionId) return SessionCommandOutcome.Failed("stale-session", "That terminal session is no longer active.")
        return try {
            current.connection.sendInput(data)
            SessionCommandOutcome.Accepted
        } catch (_: Exception) {
            SessionCommandOutcome.Failed("write-failed", "The terminal is disconnected.")
        }
    }

    @Synchronized
    fun resize(sessionId: String, size: TerminalSize): SessionCommandOutcome {
        if (!size.isUsable()) return SessionCommandOutcome.Failed("invalid-terminal-size", "The terminal size is invalid.")
        val current = active ?: return SessionCommandOutcome.Failed("not-connected", "The terminal is disconnected.")
        if (current.id != sessionId) return SessionCommandOutcome.Failed("stale-session", "That terminal session is no longer active.")
        return try {
            current.connection.resize(size)
            SessionCommandOutcome.Accepted
        } catch (_: Exception) {
            SessionCommandOutcome.Failed("resize-failed", "The terminal could not be resized.")
        }
    }

    @Synchronized
    fun release(sessionId: String): SessionCommandOutcome {
        val current = active ?: return SessionCommandOutcome.Accepted
        if (current.id != sessionId) return SessionCommandOutcome.Failed("stale-session", "That terminal session is no longer active.")
        releaseLocked(sessionId)
        return SessionCommandOutcome.Accepted
    }

    @Synchronized
    fun releaseAll() {
        active?.let { releaseLocked(it.id) }
    }

    private fun savePendingTrust(fingerprint: String, replacing: Boolean): TrustOutcome {
        val pending = pendingFingerprint
            ?: return TrustOutcome.Failed("no-pending-fingerprint", "Connect again to observe a current server fingerprint.")
        if (pending != fingerprint) {
            return TrustOutcome.Failed("fingerprint-changed", "The observed fingerprint changed; connect again before saving trust.")
        }
        val existing = trust.fingerprint()
        if (!replacing && existing != null) {
            return TrustOutcome.Failed("replacement-required", "Replacing an existing Host Trust Record is a deliberate action.")
        }
        if (replacing && existing == null) {
            return TrustOutcome.Failed("first-use-required", "Use first-use confirmation for a new Host Trust Record.")
        }
        trust.save(fingerprint)
        pendingFingerprint = null
        return if (replacing) TrustOutcome.Replaced(fingerprint) else TrustOutcome.Accepted(fingerprint)
    }

    private fun releaseLocked(sessionId: String) {
        val current = active ?: return
        if (current.id != sessionId) return
        active = null
        try {
            current.connection.close()
        } finally {
            events.onClosed(sessionId, "released")
        }
    }

    private fun onTransportClosed(sessionId: String, reason: String?) {
        synchronized(this) {
            if (active?.id != sessionId) return
            active = null
            events.onClosed(sessionId, reason ?: "Remote host closed the session.")
        }
    }

    @Synchronized
    fun activeSessionId(): String? = active?.id

    private object NoopSessionEventSink : SessionEventSink {
        override fun onFrame(sessionId: String, data: ByteArray) = Unit
        override fun onClosed(sessionId: String, reason: String?) = Unit
    }
}
