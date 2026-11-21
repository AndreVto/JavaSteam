package `in`.dragonbra.javasteam.depotdownloader.data

import java.util.concurrent.atomic.AtomicLong

// https://kotlinlang.org/docs/coding-conventions.html#source-file-organization

/**
 * Tracks cumulative download statistics across all depots in a download session.
 * Used for overall progress reporting and final download summary. All fields are
 * accessed under synchronization to ensure thread-safe updates from concurrent downloads.
 *
 * @property completeDownloadSize Total bytes expected to download across all depots. Adjusted during validation when existing chunks are reused.
 * @property totalBytesCompressed Total compressed bytes transferred from CDN servers
 * @property totalBytesUncompressed Total uncompressed bytes written to disk
 * @property totalInstallBytes Total installed file bytes selected across all depots
 * @property completedInstallBytes Installed bytes already validated or successfully written
 *
 * @author Oxters
 * @author Lossy
 * @since Oct 29, 2024
 */
class GlobalDownloadCounter(
    val completeDownloadSize: AtomicLong = AtomicLong(0),
    val totalBytesCompressed: AtomicLong = AtomicLong(0),
    val totalBytesUncompressed: AtomicLong = AtomicLong(0),
    val totalInstallBytes: AtomicLong = AtomicLong(0),
    val completedInstallBytes: AtomicLong = AtomicLong(0),
) {
    fun recordCompleted(bytes: Long): Pair<Long, Long> {
        require(bytes >= 0L)
        val completed = completedInstallBytes.addAndGet(bytes)
        val total = totalInstallBytes.get()
        return completed.coerceAtMost(total) to total
    }

    fun currentProgress(): Pair<Long, Long> =
        completedInstallBytes.get().coerceAtMost(totalInstallBytes.get()) to totalInstallBytes.get()
}

/**
 * Tracks download statistics for a single depot.
 * Used for depot-level progress reporting. All fields are accessed under synchronization
 * to ensure thread-safe updates from concurrent chunk downloads.
 *
 * @property completeDownloadSize Total bytes expected to download for this depot. Calculated during file allocation phase.
 * @property sizeDownloaded Bytes successfully downloaded and written so far
 * @property depotBytesCompressed Compressed bytes transferred from CDN for this depot
 * @property depotBytesUncompressed Uncompressed bytes written to disk for this depot
 *
 * @author Oxters
 * @author Lossy
 * @since Oct 29, 2024
 */
data class DepotDownloadCounter(
    var completeDownloadSize: AtomicLong = AtomicLong(0),
    var sizeDownloaded: AtomicLong = AtomicLong(0),
    var depotBytesCompressed: AtomicLong = AtomicLong(0),
    var depotBytesUncompressed: AtomicLong = AtomicLong(0),
) {
    fun progressFraction(): Float {
        val total = completeDownloadSize.get()
        return if (total == 0L) 1f else
            (sizeDownloaded.get().toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }
}
