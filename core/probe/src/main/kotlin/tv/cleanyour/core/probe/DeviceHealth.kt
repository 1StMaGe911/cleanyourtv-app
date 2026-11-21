package tv.cleanyour.core.probe

import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.RetryClass
import tv.cleanyour.core.session.TvSession

/** A point-in-time, read-only health sample. Null means the TV did not expose that metric. */
data class DeviceHealth(
    val totalRamMb: Int?,
    val availableRamMb: Int?,
    val totalStorageMb: Int?,
    val availableStorageMb: Int?,
    val cpuLoadAverage: Double?,
    val temperatureCelsius: Double?,
    val hasDefaultRoute: Boolean?,
    val internetReachable: Boolean?,
    val score: Int,
    val warnings: List<String>,
)

object DeviceHealthParser {
    private val MARK = ProbeScripts.MARK + " "
    private val MEM_TOTAL = Regex("^MemTotal:\\s+(\\d+) kB$")
    private val MEM_AVAILABLE = Regex("^MemAvailable:\\s+(\\d+) kB$")
    private val INTEGER = Regex("^-?\\d{1,9}$")

    fun parse(stdout: String): DeviceHealth {
        val expected = listOf("meminfo", "storage", "cpu", "thermal", "network")
        val sections = linkedMapOf("" to mutableListOf<String>())
        var current = ""
        for (raw in stdout.lineSequence()) {
            val line = raw.removeSuffix(13.toChar().toString())
            if (line.startsWith(MARK)) {
                val name = line.removePrefix(MARK)
                if (name !in expected || name in sections) throw ProbeException(ProbeStage.FACTS, "unexpected health section")
                current = name
                sections[current] = mutableListOf()
            } else sections.getValue(current) += line
        }
        if ((expected - sections.keys).isNotEmpty()) throw ProbeException(ProbeStage.FACTS, "health sample is incomplete")

        val mem = sections.getValue("meminfo")
        fun kb(pattern: Regex) = mem.firstNotNullOfOrNull { pattern.matchEntire(it.trim())?.groupValues?.get(1)?.toLongOrNull() }
        val totalRam = kb(MEM_TOTAL)?.div(1024)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        val availableRam = kb(MEM_AVAILABLE)?.div(1024)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        val storageLines = sections.getValue("storage")
        val storage = runCatching {
            val all = skipLong(storageLines.asSequence()).map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val header = all.firstOrNull { it.startsWith("Filesystem", true) }
            if (header == null || !Regex("(?i)^Filesystem\\s+(1K|1024)-blocks\\s").containsMatchIn(header)) null
            else {
                val row = all.map { it.split(Regex("\\s+")) }.lastOrNull { it.size >= 5 && it.last() == "/data" }
                    ?: return@runCatching null
                val n = row.size
                val values = row.subList(n - 5, n - 2)
                if (values.any { !Regex("^\\d{1,15}$").matches(it) }) null
                else values.map { (it.toLong() / 1024).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
            }
        }.getOrNull()
        val load = sections.getValue("cpu").firstOrNull()?.trim()?.substringBefore(' ')?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 && it < 1_000_000.0 }
        val temps = sections.getValue("thermal").mapNotNull { line ->
            val value = line.trim().takeIf { INTEGER.matches(it) }?.toLongOrNull() ?: return@mapNotNull null
            val c = if (kotlin.math.abs(value) >= 1000) value / 1000.0 else value.toDouble()
            c.takeIf { it in 0.0..150.0 }
        }
        val temperature = temps.maxOrNull()
        val networkLines = sections.getValue("network").map(String::trim).filter(String::isNotEmpty)
        val route = networkLines.firstOrNull()?.let { it != "0" && it != "1" }
        val ping = networkLines.lastOrNull()?.takeIf { it == "0" || it == "1" }?.toIntOrNull()?.let { it == 0 }

        val warnings = buildList {
            if (availableRam != null && totalRam != null && totalRam > 0 && availableRam.toDouble() / totalRam < 0.10) add("Low available memory")
            if (storage != null && storage[0] > 0 && storage[2].toDouble() / storage[0] < 0.10) add("Low free storage")
            if (temperature != null && temperature >= 75.0) add("High reported temperature")
            if (ping == false) add("Internet probe failed")
        }
        var score = 100
        if (availableRam != null && totalRam != null && totalRam > 0) {
            val ratio = availableRam.toDouble() / totalRam
            if (ratio < 0.10) score -= 30 else if (ratio < 0.20) score -= 15
        }
        if (storage != null && storage[0] > 0) {
            val ratio = storage[2].toDouble() / storage[0]
            if (ratio < 0.10) score -= 25 else if (ratio < 0.20) score -= 10
        }
        if (temperature != null && temperature >= 85.0) score -= 25 else if (temperature != null && temperature >= 75.0) score -= 10
        if (ping == false) score -= 10
        return DeviceHealth(totalRam, availableRam, storage?.get(0), storage?.get(2), load, temperature, route, ping, score.coerceIn(0, 100), warnings)
    }
}

/** Reads only standard Android/Linux diagnostic files and one bounded connectivity probe. */
class AdbDeviceHealthReader {
    suspend fun read(session: TvSession): DeviceHealth {
        val result = session.exec(
            ProbeScripts.DEVICE_HEALTH,
            ExecSpec(timeoutMs = 12_000, stdoutCap = 64 * 1024, retry = RetryClass.IDEMPOTENT_READ),
        )
        if (result.truncated) throw ProbeException(ProbeStage.FACTS, "health output exceeded limit")
        return DeviceHealthParser.parse(result.stdout)
    }
}
