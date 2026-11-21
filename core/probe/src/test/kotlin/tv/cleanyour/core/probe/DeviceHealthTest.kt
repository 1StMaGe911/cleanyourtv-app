package tv.cleanyour.core.probe

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceHealthTest {
    private fun sample(availableKb: Int = 512_000, freeStorageKb: Int = 1_000_000, temperature: String = "52000", pingExit: Int = 0) = """
@@cyt@@ meminfo
MemTotal:       2048000 kB
MemAvailable:   ${availableKb} kB
@@cyt@@ storage
Filesystem     1K-blocks    Used Available Use% Mounted on
/dev/block/dm-1 4000000 3000000 ${freeStorageKb} 75% /data
@@cyt@@ cpu
0.42 0.31 0.22 1/234 567
@@cyt@@ thermal
$temperature
@@cyt@@ network
default via 192.0.2.1 dev wlan0
$pingExit
""".trimIndent()

    @Test fun parsesAllAvailableMetricsAndHealthyScore() {
        val result = DeviceHealthParser.parse(sample())
        assertEquals(2000, result.totalRamMb)
        assertEquals(500, result.availableRamMb)
        assertEquals(3906, result.totalStorageMb)
        assertEquals(976, result.availableStorageMb)
        assertEquals(0.42, result.cpuLoadAverage)
        assertEquals(52.0, result.temperatureCelsius)
        assertTrue(result.hasDefaultRoute == true)
        assertTrue(result.internetReachable == true)
        assertEquals(100, result.score)
        assertTrue(result.warnings.isEmpty())
    }

    @Test fun warnsAboutLowMemoryStorageAndHeat() {
        val result = DeviceHealthParser.parse(sample(availableKb = 100_000, freeStorageKb = 100_000, temperature = "91000", pingExit = 1))
        assertTrue((result.score ?: 100) < 60)
        assertTrue(result.warnings.contains("Low available memory"))
        assertTrue(result.warnings.contains("Low free storage"))
        assertTrue(result.warnings.contains("High reported temperature"))
        assertTrue(result.warnings.contains("Internet probe failed"))
        assertFalse(result.internetReachable == true)
    }

    @Test fun missingOptionalMetricsRemainUnknown() {
        val result = DeviceHealthParser.parse("""
@@cyt@@ meminfo
MemTotal: 1024000 kB
@@cyt@@ storage
df: /data: Permission denied
@@cyt@@ cpu
not available
@@cyt@@ thermal
@@cyt@@ network
""".trimIndent())
        assertNull(result.temperatureCelsius)
        assertNull(result.cpuLoadAverage)
        assertNull(result.totalStorageMb)
        assertNull(result.internetReachable)
    }

    @Test(expected = ProbeException::class)
    fun refusesIncompleteOutput() {
        DeviceHealthParser.parse("@@cyt@@ meminfo")
    }
}
