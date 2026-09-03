package com.lamplitisles.herdrmobile.ssh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/** Best-effort LAN discovery using Android's built-in mDNS/NSD implementation. */
class AndroidNsdDiscovery(
    context: Context,
    private val timeoutMs: Long = 2_500L,
    private val main: Handler = Handler(Looper.getMainLooper())
) : TargetDiscovery {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val found = LinkedHashMap<String, DiscoveredTarget>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var completed = false
    private var completion: ((DiscoveryResult) -> Unit)? = null
    private var timeoutTask: Runnable? = null
    private var generation = 0L

    @Synchronized
    override fun discover(onComplete: (DiscoveryResult) -> Unit) {
        cancelLocked()
        found.clear()
        completed = false
        val scanGeneration = ++generation
        completion = onComplete
        if (manager == null) {
            finishOnMain(scanGeneration, unavailable())
            return
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val serviceType = serviceInfo.serviceType.orEmpty().lowercase()
                if (!serviceType.contains("_ssh._tcp")) return
                try {
                    manager.resolveService(serviceInfo, resolveListener(scanGeneration))
                } catch (_: RuntimeException) {
                    // NSD is optional on some LANs; manual entry remains available.
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(this@AndroidNsdDiscovery) { found.remove(serviceInfo.serviceName) }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                finishOnMain(scanGeneration, unavailable())

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            timeoutTask = Runnable { finishOnMain(scanGeneration) }.also { main.postDelayed(it, timeoutMs) }
        } catch (_: RuntimeException) {
            finishOnMain(scanGeneration, unavailable())
        }
    }

    private fun resolveListener(scanGeneration: Long) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: serviceInfo.host?.hostName ?: return
            val target = DiscoveredTarget(serviceInfo.serviceName, host, serviceInfo.port)
            synchronized(this@AndroidNsdDiscovery) {
                if (!completed && generation == scanGeneration) found[target.serviceName] = target
            }
        }
    }

    @Synchronized
    override fun cancel() {
        cancelLocked()
        completed = true
        found.clear()
        completion = null
    }

    private fun cancelLocked() {
        timeoutTask?.let(main::removeCallbacks)
        timeoutTask = null
        discoveryListener?.let { listener ->
            try { manager?.stopServiceDiscovery(listener) } catch (_: RuntimeException) { }
        }
        discoveryListener = null
    }

    private fun finishOnMain(expectedGeneration: Long? = null, result: DiscoveryResult? = null) {
        main.post {
            val callback: ((DiscoveryResult) -> Unit)?
            val values: List<DiscoveredTarget>
            synchronized(this) {
                if (completed || (expectedGeneration != null && generation != expectedGeneration)) return@post
                completed = true
                cancelLocked()
                values = found.values.toList()
                callback = completion
                completion = null
            }
            callback?.invoke(result ?: DiscoveryResult.Completed(values))
        }
    }

    private fun unavailable(): DiscoveryResult.Failed =
        DiscoveryResult.Failed("discovery-unavailable", "LAN discovery is unavailable; enter the host manually")

    private companion object {
        const val SERVICE_TYPE = "_ssh._tcp"
    }
}
