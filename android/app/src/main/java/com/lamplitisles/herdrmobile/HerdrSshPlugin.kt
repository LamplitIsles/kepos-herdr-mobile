package com.lamplitisles.herdrmobile

import android.content.Context
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.lamplitisles.herdrmobile.ssh.AndroidKeyStoreDeviceKey
import com.lamplitisles.herdrmobile.ssh.AndroidNsdDiscovery
import com.lamplitisles.herdrmobile.ssh.ConnectOutcome
import com.lamplitisles.herdrmobile.ssh.ConnectionTarget
import com.lamplitisles.herdrmobile.ssh.DiscoveredTarget
import com.lamplitisles.herdrmobile.ssh.JschSshTransport
import com.lamplitisles.herdrmobile.ssh.OpenSshKeyCodec
import com.lamplitisles.herdrmobile.ssh.RemoteSessionController
import com.lamplitisles.herdrmobile.ssh.SessionCommandOutcome
import com.lamplitisles.herdrmobile.ssh.SessionEventSink
import com.lamplitisles.herdrmobile.ssh.SharedPreferencesMetadataStore
import com.lamplitisles.herdrmobile.ssh.TargetValidation
import com.lamplitisles.herdrmobile.ssh.TargetValidator
import com.lamplitisles.herdrmobile.ssh.TerminalSize
import com.lamplitisles.herdrmobile.ssh.TrustOutcome
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@CapacitorPlugin(name = "HerdrSsh")
class HerdrSshPlugin : Plugin() {
    private lateinit var executor: ExecutorService
    private lateinit var discovery: AndroidNsdDiscovery
    private var controller: RemoteSessionController? = null

    override fun load() {
        discovery = AndroidNsdDiscovery(getContext())
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "herdr-native-plugin").apply { isDaemon = true }
        }
    }

    @PluginMethod
    fun getDeviceKey(call: PluginCall) = runNative(call) {
        val identity = ensureController().deviceKey()
        call.resolve(JSObject().apply {
            put("publicKey", identity.publicKeyText)
            put("algorithm", "RSA")
            put("bits", 3072)
        })
    }

    @PluginMethod
    fun getTarget(call: PluginCall) = runNative(call) {
        val output = JSObject()
        ensureController().target()?.let { output.put("target", targetJson(it)) }
        call.resolve(output)
    }

    @PluginMethod
    fun saveTarget(call: PluginCall) = runNative(call) {
        val validation = TargetValidator.validate(
            host = call.getString("host") ?: "",
            port = call.getInt("port", -1) ?: -1,
            user = call.getString("user") ?: ""
        )
        when (validation) {
            is TargetValidation.Invalid -> call.reject(validation.message, "invalid-target")
            is TargetValidation.Valid -> {
                ensureController().saveTarget(validation.target)
                call.resolve(JSObject().apply { put("target", targetJson(validation.target)) })
            }
        }
    }

    @PluginMethod
    fun discoverTargets(call: PluginCall) {
        if (!::discovery.isInitialized) {
            call.reject("The native SSH bridge is not ready.", "bridge-not-ready")
            return
        }
        getActivity().runOnUiThread {
            discovery.discover { targets ->
                call.resolve(JSObject().apply {
                    val array = JSArray()
                    targets.forEach { array.put(discoveredJson(it)) }
                    put("targets", array)
                })
            }
        }
    }

    @PluginMethod
    fun connect(call: PluginCall) = runNative(call) {
        val result = ensureController().connect(terminalSize(call))
        when (result) {
            is ConnectOutcome.Connected -> call.resolve(JSObject().apply {
                put("status", "connected")
                put("sessionId", result.sessionId)
                put("fingerprint", result.fingerprint)
            })
            is ConnectOutcome.TrustRequired -> call.resolve(JSObject().apply {
                put("status", "trust-required")
                put("fingerprint", result.fingerprint)
            })
            is ConnectOutcome.FingerprintMismatch -> call.resolve(JSObject().apply {
                put("status", "fingerprint-mismatch")
                put("expectedFingerprint", result.expectedFingerprint)
                put("observedFingerprint", result.observedFingerprint)
            })
            is ConnectOutcome.Failed -> call.resolve(JSObject().apply {
                put("status", "failed")
                put("code", result.code)
                put("message", result.message)
            })
        }
    }

    @PluginMethod
    fun confirmHostTrust(call: PluginCall) = runNative(call) {
        trustCall(call, replacing = false)
    }

    @PluginMethod
    fun replaceHostTrust(call: PluginCall) = runNative(call) {
        trustCall(call, replacing = true)
    }

    @PluginMethod
    fun sendInput(call: PluginCall) = runNative(call) {
        val sessionId = call.getString("sessionId")
        val data = call.getString("data")
        if (sessionId.isNullOrBlank() || data == null) {
            call.reject("A live session and terminal input are required.", "invalid-session")
            return@runNative
        }
        commandResult(call, ensureController().sendInput(sessionId, data.toByteArray(Charsets.UTF_8)))
    }

    @PluginMethod
    fun resize(call: PluginCall) = runNative(call) {
        val sessionId = call.getString("sessionId")
        if (sessionId.isNullOrBlank()) {
            call.reject("A live session is required.", "invalid-session")
            return@runNative
        }
        commandResult(call, ensureController().resize(sessionId, terminalSize(call)))
    }

    @PluginMethod
    fun release(call: PluginCall) = runNative(call) {
        val sessionId = call.getString("sessionId")
        if (sessionId.isNullOrBlank()) {
            call.reject("A live session is required.", "invalid-session")
            return@runNative
        }
        commandResult(call, ensureController().release(sessionId))
    }

    override fun handleOnStop() {
        controller?.releaseAll()
        if (::discovery.isInitialized) discovery.cancel()
        super.handleOnStop()
    }

    override fun handleOnDestroy() {
        controller?.releaseAll()
        if (::discovery.isInitialized) discovery.cancel()
        if (::executor.isInitialized) executor.shutdownNow()
        super.handleOnDestroy()
    }

    private fun ensureController(): RemoteSessionController {
        controller?.let { return it }
        val metadata = SharedPreferencesMetadataStore(
            getContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        )
        val identity = AndroidKeyStoreDeviceKey()
        val sink = object : SessionEventSink {
            override fun onFrame(sessionId: String, data: ByteArray) {
                notifyListeners("terminalFrame", JSObject().apply {
                    put("sessionId", sessionId)
                    put("data", OpenSshKeyCodec.base64(data))
                })
            }

            override fun onClosed(sessionId: String, reason: String?) {
                notifyListeners("sessionState", JSObject().apply {
                    put("sessionId", sessionId)
                    put("status", "disconnected")
                    if (!reason.isNullOrBlank()) put("reason", reason)
                })
            }
        }
        return RemoteSessionController(metadata, metadata, identity, JschSshTransport(), sink).also {
            controller = it
        }
    }

    private fun trustCall(call: PluginCall, replacing: Boolean) {
        val fingerprint = call.getString("fingerprint")
        if (fingerprint.isNullOrBlank()) {
            call.reject("The observed fingerprint is required.", "invalid-trust")
            return
        }
        when (val result = if (replacing) ensureController().replaceHostTrust(fingerprint)
        else ensureController().confirmHostTrust(fingerprint)) {
            is TrustOutcome.Accepted -> call.resolve(trustJson("accepted", result.fingerprint))
            is TrustOutcome.Replaced -> call.resolve(trustJson("replaced", result.fingerprint))
            is TrustOutcome.Failed -> call.reject(result.message, result.code)
        }
    }

    private fun commandResult(call: PluginCall, result: SessionCommandOutcome) {
        when (result) {
            SessionCommandOutcome.Accepted -> call.resolve()
            is SessionCommandOutcome.Failed -> call.reject(result.message, result.code)
        }
    }

    private fun terminalSize(call: PluginCall): TerminalSize = TerminalSize(
        columns = call.getInt("columns", 80) ?: 80,
        rows = call.getInt("rows", 24) ?: 24,
        pixelWidth = call.getInt("pixelWidth", 0) ?: 0,
        pixelHeight = call.getInt("pixelHeight", 0) ?: 0
    )

    private fun targetJson(target: ConnectionTarget): JSObject = JSObject().apply {
        put("host", target.host)
        put("port", target.port)
        put("user", target.user)
    }

    private fun discoveredJson(target: DiscoveredTarget): JSObject = JSObject().apply {
        put("serviceName", target.serviceName)
        put("host", target.host)
        put("port", target.port)
    }

    private fun trustJson(status: String, fingerprint: String): JSObject = JSObject().apply {
        put("status", status)
        put("fingerprint", fingerprint)
    }

    private fun runNative(call: PluginCall, block: () -> Unit) {
        if (!::executor.isInitialized) {
            call.reject("The native SSH bridge is not ready.", "bridge-not-ready")
            return
        }
        executor.execute {
            try {
                block()
            } catch (_: Exception) {
                call.reject("The native SSH operation failed.", "native-failure")
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "herdr_metadata"
    }
}
