package io.sonora.tools.calibration.runner

/** A track the calibration may run on: its selection [path], file [format], reference [bpm] and whether its grid has tempo changes. */
data class SelectionCandidate(val path: String, val format: String, val bpm: Double, val variable: Boolean)

/**
 * Picks a calibration subset that covers the library: the variable-tempo tracks first when asked (they are the only
 * ground truth for the tempo segmentation), then one track per format in turn (largest format first) so every
 * container and decoder is measured, and inside a format the tempos farthest from the ones already taken (slowest,
 * fastest, then the middle, and so on) so the octave prior sees the whole range. Deterministic: the input order does
 * not matter (ties go to the path).
 */
object TrackSelection {

    fun spread(candidates: List<SelectionCandidate>, count: Int, includeVariable: Boolean): List<SelectionCandidate> {
        require(count >= 0) { "count must not be negative: $count" }
        val sorted = candidates.distinct().sortedWith(compareBy({ it.path }, { it.format }, { it.bpm }))
        val chosen = ArrayList<SelectionCandidate>()
        if (includeVariable) chosen += sorted.filter { it.variable }.take(count)

        val byFormat = sorted.filter { it !in chosen && !(includeVariable && it.variable) }
            .groupBy { it.format }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<SelectionCandidate>>> { it.value.size }.thenBy { it.key })
            .map { (_, tracks) -> spreadOrder(tracks.sortedWith(compareBy({ it.bpm }, { it.path }))).toMutableList() }

        while (chosen.size < count && byFormat.any { it.isNotEmpty() }) {
            for (queue in byFormat) {
                if (chosen.size >= count) break
                if (queue.isNotEmpty()) chosen += queue.removeAt(0)
            }
        }
        return chosen
    }

    /** [byBpm] (sorted by tempo) reordered farthest-first: slowest, fastest, then each time the rank farthest from those taken. */
    private fun spreadOrder(byBpm: List<SelectionCandidate>): List<SelectionCandidate> {
        val n = byBpm.size
        if (n <= 2) return byBpm
        val taken = BooleanArray(n)
        val order = ArrayList<Int>(n)
        fun take(i: Int) {
            taken[i] = true
            order += i
        }
        take(0)
        take(n - 1)
        while (order.size < n) {
            var best = -1
            var bestDistance = -1
            for (i in 0 until n) {
                if (taken[i]) continue
                val distance = order.minOf { kotlin.math.abs(it - i) }
                if (distance > bestDistance) {
                    best = i
                    bestDistance = distance
                }
            }
            take(best)
        }
        return order.map { byBpm[it] }
    }
}
