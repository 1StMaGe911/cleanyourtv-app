package tv.cleanyour.core.probe

import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.RetryClass
import tv.cleanyour.core.session.TvSession

/** Read-only network diagnostics collected on the TV itself. Unknown means the firmware lacks a usable probe. */
data class NetworkDiagnostics(
    val hasDefaultRoute: Boolean?,
    val dnsResolved: Boolean?,
    val internetReachable: Boolean?,
    val packetLossPercent: Int?,
    val averageLatencyMs: Double?,
)

object NetworkDiagnosticsParser {
    private const val MARK = "@@cyt-net@@ "
    private val loss = Regex("(\\d{1,3})%\\s*packet loss", RegexOption.IGNORE_CASE)
    private val latency = Regex("(?:rtt|round-trip).*?=\\s*[\\d.]+/([\\d.]+)/[\\d.]+", RegexOption.IGNORE_CASE)

    fun parse(stdout: String): NetworkDiagnostics {
        val sections = linkedMapOf("" to mutableListOf<String>())
        var current = ""
        for (raw in stdout.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.startsWith(MARK)) {
                val name = line.removePrefix(MARK)
                if (name !in setOf("route", "ping", "dns") || name in sections) {
                    throw ProbeException(ProbeStage.FACTS, "unexpected network diagnostic section")
                }
                current = name
                sections[current] = mutableListOf()
            } else sections.getValue(current) += line
        }
        if (!sections.keys.containsAll(listOf("route", "ping", "dns"))) {
            throw ProbeException(ProbeStage.FACTS, "network diagnostic output is incomplete")
        }

        val routeLines = sections.getValue("route").map(String::trim).filter(String::isNotEmpty)
        val route = when {
            routeLines.any { it.startsWith("default ") } -> true
            routeLines.any { it == "__cyt_no_default_route__" } -> false
            else -> null
        }

        val pingText = sections.getValue("ping").joinToString("\n")
        val lossPercent = loss.find(pingText)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }
        val avg = latency.find(pingText)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        val pingExit = sections.getValue("ping").lastOrNull { it.startsWith("__cyt_ping_exit__=") }
            ?.substringAfter('=')?.toIntOrNull()
        val reachable = when {
            lossPercent != null -> lossPercent < 100
            pingExit == 0 -> true
            pingExit != null -> false
            else -> null
        }

        val dnsText = sections.getValue("dns").joinToString("\n")
        val dnsResolved = when {
            Regex("(?i)unknown host|bad address|name or service not known|temporary failure in name resolution").containsMatchIn(dnsText) -> false
            Regex("(?i)PING\\s+example\\.com\\s+\\(").containsMatchIn(dnsText) -> true
            else -> null
        }
        return NetworkDiagnostics(route, dnsResolved, reachable, lossPercent, avg)
    }
}

/**
 * Runs bounded, read-only probes from the TV: default route, four ICMP packets to a numeric
 * address, then a hostname probe to distinguish DNS failure from general connectivity failure.
 * No settings, routes, or device state are changed.
 */
@OptIn(RawShell::class)
class AdbNetworkDiagnosticsReader {
    suspend fun read(session: TvSession): NetworkDiagnostics {
        val result = session.exec(
            SCRIPT,
            ExecSpec(timeoutMs = 18_000, stdoutCap = 32 * 1024, retry = RetryClass.IDEMPOTENT_READ),
        )
        if (result.truncated) throw ProbeException(ProbeStage.FACTS, "network diagnostic output exceeded limit")
        return NetworkDiagnosticsParser.parse(result.stdout)
    }

    companion object {
        internal val SCRIPT = ShellLine(
            "echo '@@cyt-net@@ route'; " +
                "if command -v ip >/dev/null 2>&1; then ip route 2>/dev/null | grep -m 1 '^default ' || echo '__cyt_no_default_route__'; else echo '__cyt_route_probe_unsupported__'; fi; " +
                "echo '@@cyt-net@@ ping'; ping -c 4 -W 2 1.1.1.1 2>&1; echo \"__cyt_ping_exit__=\\$?\"; " +
                "echo '@@cyt-net@@ dns'; ping -c 1 -W 2 example.com 2>&1",
        )
    }
}
