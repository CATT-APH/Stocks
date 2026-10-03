package org.aph.stocks.graphics

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rasterizes a fixed set of daily closes as a tactile line graph. Ported
 * from PlantWave's LiveGraphRenderer -- same Bresenham segment-drawing and
 * min/max-range scaling, generalized here for a dynamic price range
 * (PlantWave's was a fixed 1700-2400 mV constant) and a small, complete
 * dataset (7 or 30 points) instead of a live, indefinitely-growing stream.
 *
 * Because the dataset is small relative to the plot width, points are
 * spread evenly across the full width (first point at column 0, last at
 * column width-1) rather than compressed one-per-column -- the opposite
 * direction from PlantWave's live view, but the same underlying algorithm.
 */
object StockChartRenderer {
    private const val PIN_UP: Byte = 1

    fun render(closes: List<Double>, minValue: Double, maxValue: Double, width: Int, height: Int): Array<ByteArray> {
        val buffer = Array(height) { ByteArray(width) }
        if (closes.isEmpty()) return buffer

        val points = closes.mapIndexed { i, value -> columnFor(i, closes.size, width) to scaleY(value, minValue, maxValue, height) }

        if (points.size == 1) {
            val (x, y) = points[0]
            if (x in 0 until width && y in 0 until height) buffer[y][x] = PIN_UP
        } else {
            for (i in 0 until points.size - 1) {
                drawSegment(buffer, points[i], points[i + 1], width, height)
            }
        }
        return buffer
    }

    /** Column for data point [index] of [count], spread evenly across [width]. */
    fun columnFor(index: Int, count: Int, width: Int): Int =
        if (count <= 1) 0 else ((index * (width - 1).toDouble()) / (count - 1)).roundToInt()

    /** Nearest data-point index to a tapped column -- for double-tap-to-speak. */
    fun nearestIndexForColumn(column: Int, count: Int, width: Int): Int {
        if (count <= 1) return 0
        return (0 until count).minBy { abs(columnFor(it, count, width) - column) }
    }

    /** Row a given price lands on for a plot of this height -- same scale render() uses. */
    fun rowForValue(value: Double, minValue: Double, maxValue: Double, height: Int): Int = scaleY(value, minValue, maxValue, height)

    /** Fixed-range scale into [0, height-1]. Higher value -> higher on screen (lower row index). Degenerate flat range (min==max) lands mid-height rather than dividing by zero. */
    private fun scaleY(value: Double, minValue: Double, maxValue: Double, height: Int): Int {
        if (maxValue <= minValue) return (height - 1) / 2
        val normalized = (value.coerceIn(minValue, maxValue) - minValue) / (maxValue - minValue)
        return ((height - 1) * (1.0 - normalized)).roundToInt().coerceIn(0, height - 1)
    }

    /** Bresenham line algorithm, with bounds guards since DotsMatrix.include() does none -- identical to PlantWave's LiveGraphRenderer.drawSegment. */
    private fun drawSegment(
        buffer: Array<ByteArray>, p0: Pair<Int, Int>, p1: Pair<Int, Int>, width: Int, height: Int
    ) {
        var x0 = p0.first
        var y0 = p0.second
        val dx = abs(p1.first - x0)
        val sx = if (x0 < p1.first) 1 else -1
        val dy = -abs(p1.second - y0)
        val sy = if (y0 < p1.second) 1 else -1
        var err = dx + dy

        while (true) {
            if (x0 in 0 until width && y0 in 0 until height) buffer[y0][x0] = PIN_UP
            if (x0 == p1.first && y0 == p1.second) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sx }
            if (e2 <= dx) { err += dx; y0 += sy }
        }
    }
}
