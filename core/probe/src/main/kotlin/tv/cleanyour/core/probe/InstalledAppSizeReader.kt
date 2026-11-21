package tv.cleanyour.core.probe

import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.RetryClass
import tv.cleanyour.core.session.TvSession

/**
 * Reads the installed APK and split-APK byte sizes for one package.
 * This is deliberately on-demand and read-only; it does not include private app data.
 */
class AdbInstalledAppSizeReader {
    @OptIn(RawShell::class)
    suspend fun read(session: TvSession, pkg: PackageName): Long? {
        val line = ShellLine(
            "for p in \$(pm path '\${pkg.value}' 2>/dev/null); do f=\${p#package:}; stat -c %s \"\$f\" 2>/dev/null; done",
        )
        val result = session.exec(
            line,
            ExecSpec(timeoutMs = 5_000, stdoutCap = 4 * 1024, retry = RetryClass.IDEMPOTENT_READ),
        )
        if (result.truncated || result.exit != 0) return null
        val sizes = result.stdout.lineSequence().map(String::trim)
            .filter { it.matches(Regex("^[0-9]{1,15}$")) }
            .mapNotNull(String::toLongOrNull)
            .toList()
        if (sizes.isEmpty()) return null
        return sizes.fold(0L) { total, size -> if (Long.MAX_VALUE - total < size) Long.MAX_VALUE else total + size }
    }
}
