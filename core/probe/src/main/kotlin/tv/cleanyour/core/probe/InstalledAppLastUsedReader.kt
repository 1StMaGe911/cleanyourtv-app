package tv.cleanyour.core.probe

import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.RetryClass
import tv.cleanyour.core.session.TvSession

/**
 * Reads only the last-use timestamp for one package from Android usage statistics.
 * Some TV firmware does not expose this information; in that case the result is null.
 */
class AdbInstalledAppLastUsedReader {
    suspend fun read(session: TvSession, pkg: PackageName): Long? {
        val result = session.exec(
            ProbeScripts.installedAppLastUsed(pkg),
            ExecSpec(timeoutMs = 5_000, stdoutCap = 2 * 1024, retry = RetryClass.IDEMPOTENT_READ),
        )
        return InstalledAppLastUsedParser.parse(result.stdout, result.exit, result.truncated)
    }
}

/** Parses only Android's numeric lastTimeUsed value (Unix epoch milliseconds). */
object InstalledAppLastUsedParser {
    private val timestamp = Regex("""\\blastTimeUsed\\s*[=:]\\s*([0-9]{1,16})\\b""")

    fun parse(stdout: String, exit: Int = 0, truncated: Boolean = false): Long? {
        if (exit != 0 || truncated) return null
        val value = timestamp.find(stdout)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return null
        return value.takeIf { it > 0L }
    }
}
