package tv.cleanyour.core.probe

import tv.cleanyour.core.model.PackageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InstalledAppSizeReaderTest {
    @Test fun sumsBaseAndSplitApkByteCounts() {
        assertEquals(3_500_000L, InstalledAppSizeParser.parse("2500000\n1000000\n"))
    }

    @Test fun rejectsMissingMalformedTruncatedAndFailedOutput() {
        assertNull(InstalledAppSizeParser.parse(""))
        assertNull(InstalledAppSizeParser.parse("not-a-size\n"))
        assertNull(InstalledAppSizeParser.parse("123\n", truncated = true))
        assertNull(InstalledAppSizeParser.parse("123\n", exit = 1))
    }

    @Test fun saturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, InstalledAppSizeParser.parse("9223372036854775807\n1\n"))
    }

    @Test fun commandQuotesValidatedPackageAndOnlyReadsApkFiles() {
        assertEquals(
            "for p in \$(pm path 'com.example.app' 2>/dev/null); do f=\${p#package:}; stat -c %s \"\$f\" 2>/dev/null; done",
            ProbeScripts.installedAppApkSizes(PackageName("com.example.app")).text,
        )
    }

    @Test fun invalidPackageCannotEnterShellCommand() {
        assertFailsWith<IllegalArgumentException> {
            ProbeScripts.installedAppApkSizes(PackageName("com.example;reboot"))
        }
    }
}
