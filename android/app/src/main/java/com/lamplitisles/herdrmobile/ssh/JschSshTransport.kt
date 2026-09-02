package com.lamplitisles.herdrmobile.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Direct TCP JSch transport. It deliberately configures public-key auth only. */
class JschSshTransport(
    private val connectTimeoutMs: Int = 15_000
) : SshTransport {
    override fun connect(
        target: ConnectionTarget,
        identity: DeviceKeyIdentity,
        expectedFingerprint: String?,
        size: TerminalSize,
        callbacks: TerminalCallbacks
    ): TransportResult {
        val jsch = JSch()
        val hostKeys = SingleHostTrustRepository(expectedFingerprint)
        var session: com.jcraft.jsch.Session? = null
        var channel: ChannelShell? = null
        try {
            val jschIdentity = identity as? com.jcraft.jsch.Identity
                ?: return TransportResult.Failed("identity-unavailable", "The Device Key is unavailable.")
            jsch.addIdentity(jschIdentity, null)
            session = jsch.getSession(target.user, target.host, target.port)
            session.setHostKeyRepository(hostKeys)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey")
            session.setConfig("PubkeyAcceptedAlgorithms", "rsa-sha2-512,rsa-sha2-256")
            session.connect(connectTimeoutMs)

            channel = session.openChannel("shell") as? ChannelShell
                ?: return failAndClose(session, "channel-unavailable", "The SSH host did not provide a shell channel.")
            channel.setPty(true)
            channel.setPtyType("xterm-256color", size.columns, size.rows, size.pixelWidth, size.pixelHeight)
            val remoteOutput = channel.inputStream
            val localInput = channel.outputStream
            channel.connect(connectTimeoutMs)
            localInput.write("herdr\n".toByteArray(Charsets.UTF_8))
            localInput.flush()
            return TransportResult.Connected(
                JschSshConnection(session, channel, remoteOutput, localInput, callbacks),
                hostKeys.observedFingerprint ?: ""
            )
        } catch (_: JSchException) {
            val observed = hostKeys.observedFingerprint
            val result = when {
                observed != null && expectedFingerprint == null -> TransportResult.TrustRequired(observed)
                observed != null && expectedFingerprint != observed -> TransportResult.FingerprintMismatch(expectedFingerprint ?: "", observed)
                else -> TransportResult.Failed("connection-failed", "Could not connect to the SSH host.")
            }
            closeTransport(channel, session)
            return result
        } catch (_: IOException) {
            closeTransport(channel, session)
            return TransportResult.Failed("connection-failed", "Could not connect to the SSH host.")
        } catch (_: Exception) {
            closeTransport(channel, session)
            return TransportResult.Failed("connection-failed", "Could not connect to the SSH host.")
        }
    }

    private fun failAndClose(session: com.jcraft.jsch.Session, code: String, message: String): TransportResult.Failed {
        closeTransport(null, session)
        return TransportResult.Failed(code, message)
    }
}

private fun closeTransport(channel: ChannelShell?, session: com.jcraft.jsch.Session?) {
    try {
        channel?.disconnect()
    } catch (_: Exception) {
        // Always attempt to close the parent session as well.
    } finally {
        try { session?.disconnect() } catch (_: Exception) { }
    }
}

private class SingleHostTrustRepository(private val expectedFingerprint: String?) : HostKeyRepository {
    @Volatile
    var observedFingerprint: String? = null
        private set

    @Volatile
    private var observedKey: ByteArray? = null

    override fun check(host: String, key: ByteArray): Int {
        observedKey = key.copyOf()
        observedFingerprint = OpenSshKeyCodec.sha256Fingerprint(key)
        return when {
            expectedFingerprint == null -> HostKeyRepository.NOT_INCLUDED
            expectedFingerprint == observedFingerprint -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey?, ui: com.jcraft.jsch.UserInfo?) = Unit

    override fun remove(host: String?, type: String?) = Unit

    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

    override fun getKnownHostsRepositoryID(): String = "app-private host trust records"

    override fun getHostKey(): Array<HostKey> = getHostKey(null, null)

    override fun getHostKey(host: String?, type: String?): Array<HostKey> {
        val key = observedKey ?: return emptyArray()
        if (expectedFingerprint == null || observedFingerprint != expectedFingerprint) return emptyArray()
        return try { arrayOf(HostKey(host ?: "", key)) } catch (_: JSchException) { emptyArray() }
    }
}

private class JschSshConnection(
    private val session: com.jcraft.jsch.Session,
    private val channel: ChannelShell,
    private val remoteOutput: InputStream,
    private val localInput: OutputStream,
    private val callbacks: TerminalCallbacks
) : SshConnection {
    private val closed = AtomicBoolean(false)
    private val reader: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "herdr-terminal-reader").apply { isDaemon = true }
    }

    init {
        reader.execute(::readFrames)
    }

    override fun sendInput(data: ByteArray) {
        check(!closed.get()) { "session is closed" }
        synchronized(localInput) {
            localInput.write(data)
            localInput.flush()
        }
    }

    override fun resize(size: TerminalSize) {
        check(!closed.get()) { "session is closed" }
        channel.setPtySize(size.columns, size.rows, size.pixelWidth, size.pixelHeight)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeTransport(channel, session)
        reader.shutdownNow()
    }

    private fun readFrames() {
        val buffer = ByteArray(8192)
        try {
            while (!closed.get()) {
                val count = remoteOutput.read(buffer)
                if (count < 0) break
                if (count > 0) callbacks.onFrame(buffer.copyOf(count))
            }
            if (closed.compareAndSet(false, true)) callbacks.onClosed("Remote host closed the session.")
        } catch (_: IOException) {
            if (closed.compareAndSet(false, true)) callbacks.onClosed("The SSH stream was interrupted.")
        } finally {
            closeTransport(channel, session)
        }
    }
}
