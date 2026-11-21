package tv.cleanyour.core.probe

import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.RetryClass
import tv.cleanyour.core.session.TvSession

/**
 * Reads the installed APK and split-APK byte sizes for one package.
 * This is deliberately on-demand and read-only; it does not include private app data.
 */
class AdbInstalledAppSizeReader {
    suspend fun read(session: TvSession, pkg: PackageName): Long? {
        val line = ProbeScripts.installedAppApkSizes(pkg)
        val result = session.exec(
            line,
            ExecSpec(timeoutMs = 5_000, stdoutCap = 4 * 1024, retry = RetryClass.IDEMPOTENT_READ),
        )
        return InstalledAppSizeParser.parse(result.stdout, result.exit, result.truncated)
    }
}


/** Accepts only numeric byte counts; null means the TV did not expose a usable APK size. */
object InstalledAppSizeParser {
    fun parse(stdout: String, exit: Int = 0, truncated: Boolean = false): Long? {
        if (truncated || exit != 0) return null
        val sizes = stdout.lineSequence().map(String::trim)
            .filter { it.matches(Regex("^[0-9]+$")) }
            .mapNotNull(String::toLongOrNull)
            .toList()
        if (sizes.isEmpty()) return null
        return sizes.fold(0L) { total, size -> if (Long.MAX_VALUE - total < size) Long.MAX_VALUE else total + size }
    }
}
