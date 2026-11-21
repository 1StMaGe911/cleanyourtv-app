package tv.cleanyour.core.probe

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkDiagnosticsTest {
    private fun output(route: String = "default via 192.0.2.1 dev wlan0", loss: Int = 0, avg: String? = "12.5", dns: String = "PING example.com (93.184.216.34) 56(84) bytes of data") = """
@@cyt-net@@ route
$route
@@cyt-net@@ ping
4 packets transmitted, 4 received, $loss% packet loss, time 3004ms
${avg?.let { "rtt min/avg/max/mdev = 10.0/$it/15.0/1.0 ms" }.orEmpty()}
__cyt_ping_exit__=0
@@cyt-net@@ dns
$dns
""".trimIndent()

    @Test fun parsesRouteDnsPacketLossAndLatency() {
        val result = NetworkDiagnosticsParser.parse(output())
        assertEquals(true, result.hasDefaultRoute)
        assertEquals(true, result.dnsResolved)
        assertEquals(true, result.internetReachable)
        assertEquals(0, result.packetLossPercent)
        assertEquals(12.5, result.averageLatencyMs)
    }

    @Test fun distinguishesNoRouteAndPacketLoss() {
        val result = NetworkDiagnosticsParser.parse(output(route = "__cyt_no_default_route__", loss = 100, dns = "ping: unknown host example.com"))
        assertEquals(false, result.hasDefaultRoute)
        assertEquals(false, result.dnsResolved)
        assertEquals(false, result.internetReachable)
        assertEquals(100, result.packetLossPercent)
    }

    @Test fun unsupportedOrMissingMetricsStayUnknown() {
        val result = NetworkDiagnosticsParser.parse(output(route = "__cyt_route_probe_unsupported__", avg = null, dns = "ping failed"))
        assertNull(result.hasDefaultRoute)
        assertNull(result.dnsResolved)
        assertNull(result.averageLatencyMs)
    }

    @Test(expected = ProbeException::class)
    fun rejectsIncompleteOutput() {
        NetworkDiagnosticsParser.parse("@@cyt-net@@ route")
    }
}
