package tv.cleanyour.core.probe

import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.RetryClass
import tv.cleanyour.core.session.TvSession

/** A receiver declared for a boot broadcast. This is inventory only, not proof it will run. */
data class StartupReceiver(
    val component: String,
    val packageName: String,
    val lockedBoot: Boolean,
)

data class StartupReceiverInventory(
    val receivers: List<StartupReceiver>,
    val bootQuerySupported: Boolean?,
    val lockedBootQuerySupported: Boolean?,
)

object StartupReceiverParser {
    private const val MARK = "@@cyt-boot@@ "
    private val component = Regex("^([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/([A-Za-z0-9_.$]+)$")

    fun parse(stdout: String): StartupReceiverInventory {
        val sections = linkedMapOf<String, MutableList<String>>()
        var current: String? = null
        for (raw in stdout.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.startsWith(MARK)) {
                val name = line.removePrefix(MARK)
                if (name !in setOf("boot", "locked") || name in sections) {
                    throw ProbeException(ProbeStage.PACKAGES, "unexpected boot receiver section")
                }
                current = name
                sections[name] = mutableListOf()
            } else current?.let { sections.getValue(it) += line }
        }
        if (!sections.keys.containsAll(listOf("boot", "locked"))) {
            throw ProbeException(ProbeStage.PACKAGES, "boot receiver output is incomplete")
        }

        fun supported(name: String): Boolean? {
            val lines = sections.getValue(name)
            return when {
                lines.any { it.trim() == "__cyt_query_unsupported__" } -> false
                lines.any { component.matches(it.trim()) } -> true
                lines.any { it.trim().isNotEmpty() && !it.trim().startsWith("No activities found") } -> true
                else -> null
            }
        }
        val receivers = linkedMapOf<String, StartupReceiver>()
        for ((name, locked) in listOf("boot" to false, "locked" to true)) {
            for (line in sections.getValue(name)) {
                val value = line.trim()
                val match = component.matchEntire(value) ?: continue
                val pkg = match.groupValues[1]
                receivers.putIfAbsent(value, StartupReceiver(value, pkg, locked))
            }
        }
        return StartupReceiverInventory(
            receivers = receivers.values.take(300),
            bootQuerySupported = supported("boot"),
            lockedBootQuerySupported = supported("locked"),
        )
    }
}

/**
 * Read-only inventory of receivers registered for Android boot broadcasts.
 * Firmware without the package-manager query command reports unknown/unsupported; no app is changed.
 */
@OptIn(RawShell::class)
class AdbStartupReceiverReader {
    suspend fun read(session: TvSession): StartupReceiverInventory {
        val result = session.exec(
            SCRIPT,
            ExecSpec(timeoutMs = 12_000, stdoutCap = 48 * 1024, retry = RetryClass.IDEMPOTENT_READ),
        )
        if (result.truncated) throw ProbeException(ProbeStage.PACKAGES, "boot receiver output exceeded limit")
        return StartupReceiverParser.parse(result.stdout)
    }

    companion object {
        internal val SCRIPT = ShellLine(
            "echo '@@cyt-boot@@ boot'; " +
                "if cmd package query-receivers --brief -a android.intent.action.BOOT_COMPLETED 2>/dev/null; then :; " +
                "else echo '__cyt_query_unsupported__'; fi; " +
                "echo '@@cyt-boot@@ locked'; " +
                "if cmd package query-receivers --brief -a android.intent.action.LOCKED_BOOT_COMPLETED 2>/dev/null; then :; " +
                "else echo '__cyt_query_unsupported__'; fi",
        )
    }
}
