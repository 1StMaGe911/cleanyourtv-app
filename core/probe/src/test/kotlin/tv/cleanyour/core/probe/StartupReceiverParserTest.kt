package tv.cleanyour.core.probe

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartupReceiverParserTest {
    @Test fun parsesBootAndLockedBootReceiversWithoutDuplicates() {
        val result = StartupReceiverParser.parse("""
@@cyt-boot@@ boot
com.example.player/.BootReceiver
com.example.shared/com.example.shared.StartReceiver
@@cyt-boot@@ locked
com.example.player/.BootReceiver
com.example.secure/.LockedReceiver
""".trimIndent())
        assertEquals(3, result.receivers.size)
        assertEquals("com.example.player", result.receivers.first().packageName)
        assertFalse(result.receivers.first().lockedBoot)
        assertTrue(result.receivers.last().lockedBoot)
        assertEquals(true, result.bootQuerySupported)
        assertEquals(true, result.lockedBootQuerySupported)
    }

    @Test fun emptyResultIsUnknownRatherThanProofThereAreNoStartupApps() {
        val result = StartupReceiverParser.parse("""
@@cyt-boot@@ boot
@@cyt-boot@@ locked
""".trimIndent())
        assertTrue(result.receivers.isEmpty())
        assertNull(result.bootQuerySupported)
        assertNull(result.lockedBootQuerySupported)
    }

    @Test fun unsupportedQueryIsReportedSeparately() {
        val result = StartupReceiverParser.parse("""
@@cyt-boot@@ boot
__cyt_query_unsupported__
@@cyt-boot@@ locked
__cyt_query_unsupported__
""".trimIndent())
        assertEquals(false, result.bootQuerySupported)
        assertEquals(false, result.lockedBootQuerySupported)
    }

    @Test(expected = ProbeException::class)
    fun rejectsIncompleteOutput() {
        StartupReceiverParser.parse("@@cyt-boot@@ boot")
    }

    @Test fun ignoresUntrustedLinesAndKeepsBoundedValidComponents() {
        val result = StartupReceiverParser.parse("""
@@cyt-boot@@ boot
not a component
com.example.valid/.Receiver
@@cyt-boot@@ locked
malformed/../../bad
""".trimIndent())
        assertEquals(listOf("com.example.valid/.Receiver"), result.receivers.map { it.component })
    }
}
