package com.lamplitisles.herdrmobile.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetDiscoveryContractTest {
    private val discovered = DiscoveredTarget("current-mac", "192.168.1.203", 22)

    @Test
    fun discoveredNsdResultReachesNativeContract() {
        assertEquals(
            DiscoveryContract.Available(listOf(discovered)),
            deliver(DiscoveryResult.Completed(listOf(discovered))),
        )
    }

    @Test
    fun emptyNsdResultReachesNativeContract() {
        assertEquals(DiscoveryContract.Available(emptyList()), deliver(DiscoveryResult.Completed(emptyList())))
    }

    @Test
    fun failedNsdResultReachesNativeContract() {
        val failure = DiscoveryResult.Failed(
            code = "discovery-unavailable",
            message = "LAN discovery is unavailable; enter the host manually",
        )
        assertEquals(DiscoveryContract.Unavailable(failure.code, failure.message), deliver(failure))
    }

    private fun deliver(result: DiscoveryResult): DiscoveryContract? {
        var observed: DiscoveryContract? = null
        FakeTargetDiscovery(result).discover { observed = it.toContract() }
        return observed
    }

    /** Test-owned fake for the normalized Android NSD callback contract. */
    private class FakeTargetDiscovery(private val result: DiscoveryResult) : TargetDiscovery {
        override fun discover(onComplete: (DiscoveryResult) -> Unit) = onComplete(result)

        override fun cancel() = Unit
    }
}
