package com.lamplitisles.herdrmobile.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSessionControllerTest {
    @Test
    fun targetValidationRejectsInvalidDestinationAndNormalizesWhitespace() {
        val invalid = TargetValidator.validate(host = "host", port = 0, user = "neil")
        assertTrue(invalid is TargetValidation.Invalid)

        val valid = TargetValidator.validate(host = " host.example ", port = 2222, user = " neil ")
        assertEquals(ConnectionTarget("host.example", 2222, "neil"), (valid as TargetValidation.Valid).target)
    }

    @Test
    fun firstUseTrustIsPendingUntilExplicitConfirmation() {
        val fixture = Fixture()
        fixture.transport.next = TransportResult.TrustRequired("SHA256:first")
        val outcome = fixture.controller.connect(TerminalSize(80, 24))
        assertEquals(ConnectOutcome.TrustRequired("SHA256:first"), outcome)
        assertNull(fixture.trust.fingerprint())

        val accepted = fixture.controller.confirmHostTrust("SHA256:first")
        assertEquals(TrustOutcome.Accepted("SHA256:first"), accepted)
        assertEquals("SHA256:first", fixture.trust.fingerprint())
    }

    @Test
    fun failedReconnectCannotReuseOldTrustObservation() {
        val fixture = Fixture()
        fixture.transport.next = TransportResult.TrustRequired("SHA256:first")
        assertEquals(ConnectOutcome.TrustRequired("SHA256:first"), fixture.controller.connect(TerminalSize(80, 24)))

        fixture.transport.next = TransportResult.Failed("connection-failed", "unreachable")
        assertEquals(ConnectOutcome.Failed("connection-failed", "unreachable"), fixture.controller.connect(TerminalSize(80, 24)))
        assertTrue(fixture.controller.confirmHostTrust("SHA256:first") is TrustOutcome.Failed)
        assertNull(fixture.trust.fingerprint())
    }

    @Test
    fun changedFingerprintCannotBeSilentlyOverridden() {
        val fixture = Fixture()
        fixture.trust.save("SHA256:old")
        fixture.transport.next = TransportResult.FingerprintMismatch("SHA256:old", "SHA256:new")

        val outcome = fixture.controller.connect(TerminalSize(80, 24))
        assertEquals(ConnectOutcome.FingerprintMismatch("SHA256:old", "SHA256:new"), outcome)
        assertEquals("SHA256:old", fixture.trust.fingerprint())
        assertTrue(fixture.controller.confirmHostTrust("SHA256:new") is TrustOutcome.Failed)
        assertEquals(TrustOutcome.Replaced("SHA256:new"), fixture.controller.replaceHostTrust("SHA256:new"))
        assertEquals("SHA256:new", fixture.trust.fingerprint())
    }

    @Test
    fun transportReceivesOnlyDeviceIdentityAndExpectedTrust() {
        val fixture = Fixture()
        fixture.trust.save("SHA256:known")
        fixture.transport.next = TransportResult.Failed("auth-failed", "Authentication failed")

        fixture.controller.connect(TerminalSize(80, 24))

        assertSame(fixture.identity, fixture.transport.identity)
        assertEquals("SHA256:known", fixture.transport.expectedFingerprint)
        assertEquals("ssh-rsa AAAA", fixture.identity.publicKeyText)
    }

    @Test
    fun terminalInputResizeAndForegroundReleaseAreForwarded() {
        val fixture = Fixture()
        fixture.trust.save("SHA256:known")
        val connection = FakeConnection()
        fixture.transport.next = TransportResult.Connected(connection, "SHA256:known")
        val connected = fixture.controller.connect(TerminalSize(80, 24)) as ConnectOutcome.Connected

        assertEquals(SessionCommandOutcome.Accepted, fixture.controller.sendInput(connected.sessionId, "pwd\n".toByteArray()))
        assertEquals(SessionCommandOutcome.Accepted, fixture.controller.resize(connected.sessionId, TerminalSize(100, 30)))
        assertEquals("pwd\n", connection.input.toString(Charsets.UTF_8))
        assertEquals(TerminalSize(100, 30), connection.lastSize)

        fixture.controller.releaseAll()
        assertTrue(connection.closed)
        assertEquals("released", fixture.events.closedReason)
        assertNull(fixture.controller.activeSessionId())
    }

    @Test
    fun terminalFramesBeginOnlyAfterTheSessionIsActive() {
        val fixture = Fixture()
        fixture.trust.save("SHA256:known")
        fixture.transport.next = TransportResult.Connected(
            FakeConnection { fixture.transport.callbacks?.onFrame("ready".toByteArray()) },
            "SHA256:known"
        )

        val connected = fixture.controller.connect(TerminalSize(80, 24)) as ConnectOutcome.Connected
        assertNull(fixture.events.frame)
        fixture.controller.activate(connected.sessionId)

        assertEquals("ready", fixture.events.frame?.toString(Charsets.UTF_8))
    }

    @Test
    fun secondConnectionIsRejectedUntilTheForegroundSessionIsReleased() {
        val fixture = Fixture()
        fixture.trust.save("SHA256:known")
        fixture.transport.next = TransportResult.Connected(FakeConnection(), "SHA256:known")
        fixture.controller.connect(TerminalSize(80, 24))

        assertEquals(
            ConnectOutcome.Failed("session-active", "A terminal session is already open. Leave it before connecting again."),
            fixture.controller.connect(TerminalSize(80, 24))
        )
        fixture.controller.releaseAll()
    }

    @Test
    fun editingDestinationClearsItsOldHostTrustRecord() {
        val fixture = Fixture()
        fixture.trust.save("SHA256:known")
        fixture.controller.saveTarget(fixture.target.copy(host = "new.example"))
        assertNull(fixture.trust.fingerprint())
    }

    private class Fixture {
        val target = ConnectionTarget("lab.example", 22, "neil")
        val targets = FakeTargets(target)
        val trust = FakeTrust()
        val identity = FakeIdentity()
        val transport = FakeTransport()
        val events = FakeEvents()
        val controller = RemoteSessionController(targets, trust, identity, transport, events)
    }

    private class FakeTargets(private var value: ConnectionTarget) : TargetRepository {
        override fun get() = value
        override fun save(target: ConnectionTarget) { value = target }
    }

    private class FakeTrust : HostTrustRepository {
        private var value: String? = null
        override fun fingerprint() = value
        override fun save(fingerprint: String) { value = fingerprint }
        override fun clear() { value = null }
    }

    private class FakeIdentity : DeviceKeyIdentity {
        override val publicKeyText = "ssh-rsa AAAA"
        override val publicBlob = byteArrayOf(1, 2, 3)
        override fun sign(data: ByteArray, algorithm: String) = byteArrayOf(4, 5, 6)
    }

    private class FakeTransport : SshTransport {
        var next: TransportResult = TransportResult.Failed("test", "test")
        var identity: DeviceKeyIdentity? = null
        var expectedFingerprint: String? = null
        var callbacks: TerminalCallbacks? = null
        override fun connect(target: ConnectionTarget, identity: DeviceKeyIdentity, expectedFingerprint: String?, size: TerminalSize, callbacks: TerminalCallbacks): TransportResult {
            this.identity = identity
            this.expectedFingerprint = expectedFingerprint
            this.callbacks = callbacks
            return next
        }
    }

    private class FakeConnection(private val onStart: (() -> Unit)? = null) : SshConnection {
        val input = java.io.ByteArrayOutputStream()
        var lastSize: TerminalSize? = null
        var closed = false
        override fun start() { onStart?.invoke() }
        override fun sendInput(data: ByteArray) { input.write(data) }
        override fun resize(size: TerminalSize) { lastSize = size }
        override fun close() { closed = true }
    }

    private class FakeEvents : SessionEventSink {
        var closedReason: String? = null
        var frame: ByteArray? = null
        override fun onFrame(sessionId: String, data: ByteArray) { frame = data }
        override fun onClosed(sessionId: String, reason: String?) { closedReason = reason }
    }
}
