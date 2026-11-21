package tv.cleanyour.core.probe

import tv.cleanyour.core.model.PackageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InstalledAppLastUsedReaderTest {
    @Test fun parsesAndroidLastTimeUsedMillis() {
        assertEquals(1_800_000_000_000L, InstalledAppLastUsedParser.parse("package=com.example.app\\nlastTimeUsed=1800000000000\\n"))
        assertEquals(1_800_000_000_000L, InstalledAppLastUsedParser.parse("lastTimeUsed: 1800000000000"))
    }

    @Test fun rejectsMissingMalformedZeroFailedAndTruncatedOutput() {
        assertNull(InstalledAppLastUsedParser.parse(""))
        assertNull(InstalledAppLastUsedParser.parse("lastTimeUsed=unknown"))
        assertNull(InstalledAppLastUsedParser.parse("lastTimeUsed=0"))
        assertNull(InstalledAppLastUsedParser.parse("lastTimeUsed=123", exit = 1))
        assertNull(InstalledAppLastUsedParser.parse("lastTimeUsed=123", truncated = true))
    }

    @Test fun commandIsPackageScopedAndReadOnly() {
        assertEquals(
            "dumpsys usagestats 2>/dev/null | grep -F -A 8 -- 'package=com.example.app' | grep -m 1 'lastTimeUsed'",
            ProbeScripts.installedAppLastUsed(PackageName("com.example.app")).text,
        )
    }

    @Test fun invalidPackageCannotEnterShellCommand() {
        assertFailsWith<IllegalArgumentException> {
            ProbeScripts.installedAppLastUsed(PackageName("com.example;reboot"))
        }
    }
}
