package `in`.dragonbra.javasteam.depotdownloader

import `in`.dragonbra.javasteam.depotdownloader.data.GlobalDownloadCounter
import `in`.dragonbra.javasteam.depotdownloader.data.DepotDownloadCounter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DownloadProgressCounterTest {
    @Test
    fun `installed work tracks previously validated files and newly written chunks`() {
        val counter = GlobalDownloadCounter()
        counter.totalInstallBytes.set(1_000)

        assertEquals(0L to 1_000L, counter.currentProgress())
        assertEquals(300L to 1_000L, counter.recordCompleted(300))
        assertEquals(900L to 1_000L, counter.recordCompleted(600))
        assertEquals(1_000L to 1_000L, counter.recordCompleted(100))
    }

    @Test
    fun `depot progress compares completed installed bytes against selected installed bytes`() {
        val counter = DepotDownloadCounter()
        counter.completeDownloadSize.set(1_000)
        counter.sizeDownloaded.set(700)
        assertEquals(0.7f, counter.progressFraction())
        counter.sizeDownloaded.set(1_000)
        assertEquals(1f, counter.progressFraction())
    }

    @Test
    fun `reported completion stays bounded to the selected manifest total`() {
        val counter = GlobalDownloadCounter()
        counter.totalInstallBytes.set(100)

        assertEquals(100L to 100L, counter.recordCompleted(120))
        assertEquals(100L to 100L, counter.currentProgress())
        assertFailsWith<IllegalArgumentException> { counter.recordCompleted(-1) }
    }
}
