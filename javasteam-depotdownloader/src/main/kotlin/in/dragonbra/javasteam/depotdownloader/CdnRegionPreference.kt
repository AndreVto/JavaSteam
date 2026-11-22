package `in`.dragonbra.javasteam.depotdownloader

internal object CdnRegionPreference {
    fun <T> prioritize(
        ranked: List<T>,
        clientCellId: Int,
        preferredHostCount: Int,
        cellId: (T) -> Int,
    ): List<T> {
        if (clientCellId <= 0 || ranked.isEmpty()) return ranked
        val window = minOf(ranked.size, maxOf(8, preferredHostCount * 2))
        val candidates = ranked.take(window)
        if (candidates.none { cellId(it) == clientCellId }) return ranked
        return candidates.filter { cellId(it) == clientCellId } +
            candidates.filter { cellId(it) != clientCellId } + ranked.drop(window)
    }
}
