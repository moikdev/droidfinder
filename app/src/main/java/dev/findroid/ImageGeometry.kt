package dev.findroid

import kotlin.math.*

internal data class ImagePoint(val x: Double, val y: Double)

internal data class ImageRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width
        get() = right - left

    val height
        get() = bottom - top
}

internal data class ImagePixels(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width
        get() = right - left

    val height
        get() = bottom - top
}

internal data class ImageAffine(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val x: Double,
    val y: Double,
) {
    fun map(p: ImagePoint) = ImagePoint(a * p.x + b * p.y + x, c * p.x + d * p.y + y)

    fun inverse(p: ImagePoint): ImagePoint {
        val determinant = a * d - b * c
        return ImagePoint(
            (d * (p.x - x) - b * (p.y - y)) / determinant,
            (-c * (p.x - x) + a * (p.y - y)) / determinant,
        )
    }

    fun then(next: ImageAffine) =
        ImageAffine(
            next.a * a + next.b * c,
            next.a * b + next.b * d,
            next.c * a + next.d * c,
            next.c * b + next.d * d,
            next.a * x + next.b * y + next.x,
            next.c * x + next.d * y + next.y,
        )
}

/** Coordinates describe pixel edges; all eight EXIF transforms preserve the full raw rectangle. */
internal class ImageGeometry(
    val rawWidth: Int,
    val rawHeight: Int,
    val orientation: Int = 1,
    turns: Int = 0,
) {
    val width: Int
    val height: Int
    val transform: ImageAffine

    init {
        require(
            rawWidth in 1..1_000_000 &&
                rawHeight in 1..1_000_000 &&
                rawWidth.toLong() * rawHeight <= 1_000_000_000L,
        ) {
            "Image dimensions exceed the preview limit."
        }
        val w = rawWidth.toDouble()
        val h = rawHeight.toDouble()
        var matrix =
            when (orientation) {
                2 -> ImageAffine(-1.0, 0.0, 0.0, 1.0, w, 0.0)
                3 -> ImageAffine(-1.0, 0.0, 0.0, -1.0, w, h)
                4 -> ImageAffine(1.0, 0.0, 0.0, -1.0, 0.0, h)
                5 -> ImageAffine(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
                6 -> ImageAffine(0.0, -1.0, 1.0, 0.0, h, 0.0)
                7 -> ImageAffine(0.0, -1.0, -1.0, 0.0, h, w)
                8 -> ImageAffine(0.0, 1.0, -1.0, 0.0, 0.0, w)
                else -> ImageAffine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            }
        var ow = if (orientation in 5..8) rawHeight else rawWidth
        var oh = if (orientation in 5..8) rawWidth else rawHeight
        repeat(((turns % 4) + 4) % 4) {
            matrix = matrix.then(ImageAffine(0.0, -1.0, 1.0, 0.0, oh.toDouble(), 0.0))
            val old = ow
            ow = oh
            oh = old
        }
        width = ow
        height = oh
        transform = matrix
    }

    fun rawRegion(rect: ImageRect): ImagePixels {
        val points =
            listOf(
                    ImagePoint(rect.left, rect.top),
                    ImagePoint(rect.right, rect.top),
                    ImagePoint(rect.left, rect.bottom),
                    ImagePoint(rect.right, rect.bottom),
                )
                .map(transform::inverse)
        val l = floor(points.minOf { it.x }).toInt().coerceIn(0, rawWidth - 1)
        val t = floor(points.minOf { it.y }).toInt().coerceIn(0, rawHeight - 1)
        return ImagePixels(
            l,
            t,
            ceil(points.maxOf { it.x }).toInt().coerceIn(l + 1, rawWidth),
            ceil(points.maxOf { it.y }).toInt().coerceIn(t + 1, rawHeight),
        )
    }
}

/** Absolute scale is display pixels per source pixel. A 100% view is genuinely 1:1. */
internal data class ImageViewport(
    val geometry: ImageGeometry,
    val viewWidth: Double,
    val viewHeight: Double,
    val scale: Double,
    val center: ImagePoint,
) {
    val fit
        get() =
            minOf(viewWidth / geometry.width, viewHeight / geometry.height, 1.0)
                .coerceAtLeast(0.000001)

    fun clamped(): ImageViewport {
        val s = scale.takeIf { it.isFinite() }?.coerceIn(fit / 4, 8.0) ?: fit
        fun axis(c: Double, size: Int, view: Double): Double {
            val half = view / (2 * s)
            return if (half * 2 >= size) size / 2.0
            else c.takeIf { it.isFinite() }?.coerceIn(half, size - half) ?: size / 2.0
        }
        return copy(
            scale = s,
            center =
                ImagePoint(
                    axis(center.x, geometry.width, viewWidth),
                    axis(center.y, geometry.height, viewHeight),
                ),
        )
    }

    fun imageAt(point: ImagePoint) =
        ImagePoint(
            center.x + (point.x - viewWidth / 2) / scale,
            center.y + (point.y - viewHeight / 2) / scale,
        )

    fun zoom(
        next: Double,
        anchor: ImagePoint = ImagePoint(viewWidth / 2, viewHeight / 2),
    ): ImageViewport {
        val s = next.coerceIn(fit / 4, 8.0)
        val source = imageAt(anchor)
        return copy(
                scale = s,
                center =
                    ImagePoint(
                        source.x - (anchor.x - viewWidth / 2) / s,
                        source.y - (anchor.y - viewHeight / 2) / s,
                    ),
            )
            .clamped()
    }

    fun pan(dx: Double, dy: Double) =
        copy(center = ImagePoint(center.x - dx / scale, center.y - dy / scale)).clamped()

    fun visible(margin: Double = 0.0): ImageRect {
        val halfW = (viewWidth / 2 + margin) / scale
        val halfH = (viewHeight / 2 + margin) / scale
        return ImageRect(
            (center.x - halfW).coerceIn(0.0, geometry.width.toDouble()),
            (center.y - halfH).coerceIn(0.0, geometry.height.toDouble()),
            (center.x + halfW).coerceIn(0.0, geometry.width.toDouble()),
            (center.y + halfH).coerceIn(0.0, geometry.height.toDouble()),
        )
    }
}

internal fun imageSample(
    width: Int,
    height: Int,
    preferred: Int = 1,
    pixels: Long = 4_194_304,
    maxEdge: Int = 4096,
): Int {
    require(width > 0 && height > 0 && pixels > 0 && maxEdge > 0)
    var sample = 1
    while (sample < preferred && sample < (1 shl 30)) sample *= 2
    fun edge(value: Int) = (value.toLong() + sample - 1) / sample
    while (edge(width) * edge(height) > pixels || edge(width) > maxEdge || edge(height) > maxEdge) {
        check(sample < (1 shl 30))
        sample *= 2
    }
    return sample
}

internal fun imagePreferredSample(scale: Double): Int {
    var result = 1
    while (result.toDouble() * 2 <= 1 / scale && result < (1 shl 29)) result *= 2
    return result
}

internal fun imageZoomPercent(scale: Double): String {
    if (!scale.isFinite() || scale <= 0) return "—"
    val percent = scale * 100
    return when {
        percent >= 10 -> "${percent.roundToInt()}%"
        percent < .01 -> "<0.01%"
        else ->
            java.lang.String.format(java.util.Locale.ROOT, "%.2f", percent)
                .trimEnd('0')
                .trimEnd('.') + "%"
    }
}
