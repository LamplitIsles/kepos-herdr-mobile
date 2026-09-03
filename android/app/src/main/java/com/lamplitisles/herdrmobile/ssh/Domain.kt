package com.lamplitisles.herdrmobile.ssh

data class ConnectionTarget(
    val host: String,
    val port: Int,
    val user: String
)

data class DiscoveredTarget(
    val serviceName: String,
    val host: String,
    val port: Int
)

sealed interface DiscoveryResult {
    data class Completed(val targets: List<DiscoveredTarget>) : DiscoveryResult
    data class Failed(val code: String, val message: String) : DiscoveryResult
}

internal sealed interface DiscoveryContract {
    data class Available(val targets: List<DiscoveredTarget>) : DiscoveryContract
    data class Unavailable(val code: String, val message: String) : DiscoveryContract
}

internal fun DiscoveryResult.toContract(): DiscoveryContract = when (this) {
    is DiscoveryResult.Completed -> DiscoveryContract.Available(targets)
    is DiscoveryResult.Failed -> DiscoveryContract.Unavailable(code, message)
}

data class TerminalSize(
    val columns: Int,
    val rows: Int,
    val pixelWidth: Int = 0,
    val pixelHeight: Int = 0
) {
    fun isUsable(): Boolean = columns in 1..500 && rows in 1..300 && pixelWidth >= 0 && pixelHeight >= 0
}

sealed interface TargetValidation {
    data class Valid(val target: ConnectionTarget) : TargetValidation
    data class Invalid(val message: String) : TargetValidation
}

object TargetValidator {
    fun validate(host: String, port: Int, user: String): TargetValidation {
        val cleanHost = host.trim()
        val cleanUser = user.trim()
        if (cleanHost.isEmpty()) return TargetValidation.Invalid("Add a host name or IP address.")
        if (cleanHost.length > 255 || cleanHost.any { it.isWhitespace() || it.isISOControl() } || cleanHost.contains('/')) {
            return TargetValidation.Invalid("Enter a valid direct SSH host.")
        }
        if (port !in 1..65535) return TargetValidation.Invalid("SSH port must be between 1 and 65535.")
        if (cleanUser.isEmpty()) return TargetValidation.Invalid("Add the remote SSH user.")
        if (cleanUser.length > 128 || cleanUser.any { it.isWhitespace() || it.isISOControl() }) {
            return TargetValidation.Invalid("Enter a valid SSH user name.")
        }
        return TargetValidation.Valid(ConnectionTarget(cleanHost, port, cleanUser))
    }
}

interface TargetRepository {
    fun get(): ConnectionTarget?
    fun save(target: ConnectionTarget)
}

interface HostTrustRepository {
    fun fingerprint(): String?
    fun save(fingerprint: String)
    fun clear()
}

interface DeviceKeyIdentity {
    val publicKeyText: String
    val publicBlob: ByteArray

    fun sign(data: ByteArray, algorithm: String): ByteArray
}

interface TerminalCallbacks {
    fun onFrame(data: ByteArray)
    fun onClosed(reason: String?)
}

interface SshConnection {
    /** Begin delivering remote terminal frames after the controller owns this session. */
    fun start() = Unit
    fun sendInput(data: ByteArray)
    fun resize(size: TerminalSize)
    fun close()
}

sealed interface TransportResult {
    data class Connected(val connection: SshConnection, val fingerprint: String) : TransportResult
    data class TrustRequired(val fingerprint: String) : TransportResult
    data class FingerprintMismatch(val expected: String, val observed: String) : TransportResult
    data class Failed(val code: String, val message: String) : TransportResult
}

interface SshTransport {
    fun connect(
        target: ConnectionTarget,
        identity: DeviceKeyIdentity,
        expectedFingerprint: String?,
        size: TerminalSize,
        callbacks: TerminalCallbacks
    ): TransportResult
}

interface SessionEventSink {
    fun onFrame(sessionId: String, data: ByteArray)
    fun onClosed(sessionId: String, reason: String?)
}

sealed interface ConnectOutcome {
    data class Connected(val sessionId: String, val fingerprint: String) : ConnectOutcome
    data class TrustRequired(val fingerprint: String) : ConnectOutcome
    data class FingerprintMismatch(val expectedFingerprint: String, val observedFingerprint: String) : ConnectOutcome
    data class Failed(val code: String, val message: String) : ConnectOutcome
}

sealed interface TrustOutcome {
    data class Accepted(val fingerprint: String) : TrustOutcome
    data class Replaced(val fingerprint: String) : TrustOutcome
    data class Failed(val code: String, val message: String) : TrustOutcome
}

sealed interface SessionCommandOutcome {
    data object Accepted : SessionCommandOutcome
    data class Failed(val code: String, val message: String) : SessionCommandOutcome
}

interface TargetDiscovery {
    fun discover(onComplete: (DiscoveryResult) -> Unit)
    fun cancel()
}
