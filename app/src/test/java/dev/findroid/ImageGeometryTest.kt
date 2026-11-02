package dev.findroid

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class ImageGeometryTest {
    @Test
    fun allEightExifTransformsAndTheirInversesMatchPixelEdges() {
        val expected =
            listOf(
                20.0 to 30.0,
                380.0 to 30.0,
                380.0 to 270.0,
                20.0 to 270.0,
                30.0 to 20.0,
                270.0 to 20.0,
                270.0 to 380.0,
                30.0 to 380.0,
            )
        (1..8).forEach { o ->
            val g = ImageGeometry(400, 300, o)
            val p = g.transform.map(ImagePoint(20.0, 30.0))
            assertEquals(expected[o - 1].first, p.x, 1e-9)
            assertEquals(expected[o - 1].second, p.y, 1e-9)
            val back = g.transform.inverse(p)
            assertEquals(20.0, back.x, 1e-9)
            assertEquals(30.0, back.y, 1e-9)
            assertEquals(if (o >= 5) 300 else 400, g.width)
            assertEquals(
                ImagePixels(0, 0, 400, 300),
                g.rawRegion(ImageRect(0.0, 0.0, g.width.toDouble(), g.height.toDouble())),
            )
        }
    }

    @Test
    fun temporaryRotationComposesAfterExifAndFourTurnsRestore() {
        for (o in 1..8) {
            val initial = ImageGeometry(401, 299, o)
            for (t in listOf(-4, 0, 4, 8)) {
                val g = ImageGeometry(401, 299, o, t)
                assertEquals(initial.transform, g.transform)
                assertEquals(initial.width, g.width)
            }
            val g = ImageGeometry(401, 299, o, 1)
            assertEquals(initial.height, g.width)
            assertEquals(initial.width, g.height)
            val p = initial.transform.map(ImagePoint(33.0, 77.0))
            val r = g.transform.map(ImagePoint(33.0, 77.0))
            assertEquals(initial.height - p.y, r.x, 1e-9)
            assertEquals(p.x, r.y, 1e-9)
        }
    }

    @Test
    fun zoomRetainsCursorAnchorAndPanCannotLoseTheImage() {
        val g = ImageGeometry(6000, 4000)
        val view = ImageViewport(g, 1200.0, 800.0, .5, ImagePoint(3000.0, 2000.0))
        val cursor = ImagePoint(150.0, 200.0)
        val p = view.imageAt(cursor)
        val next = view.zoom(1.0, cursor)
        val after = next.imageAt(cursor)
        assertEquals(p.x, after.x, 1e-9)
        assertEquals(p.y, after.y, 1e-9)
        val moved = next.pan(1e9, -1e9)
        assertEquals(600.0, moved.center.x, 1e-9)
        assertEquals(3600.0, moved.center.y, 1e-9)
        assertEquals(1.0, next.scale, 0.0)
    }

    @Test
    fun fitDoesNotEnlargeSmallFilesAndInvalidStateIsClamped() {
        val view =
            ImageViewport(
                    ImageGeometry(100, 80),
                    1000.0,
                    600.0,
                    Double.NaN,
                    ImagePoint(Double.NaN, Double.POSITIVE_INFINITY),
                )
                .clamped()
        assertEquals(1.0, view.fit, 0.0)
        assertEquals(1.0, view.scale, 0.0)
        assertEquals(ImagePoint(50.0, 40.0), view.center)
        assertEquals(ImageRect(0.0, 0.0, 100.0, 80.0), view.visible())
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry(1_000_001, 100) }
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry(100_000, 100_000) }
    }

    @Test
    fun sampleBudgetsHandlePanoramasOddDimensionsAndLargeSources() {
        for ((w, h) in
            listOf(16000 to 12000, 1_000_000 to 1, 1 to 1_000_000, 6001 to 4001, 1 to 1)) {
            for (preferred in listOf(1, 2, 16, 512)) {
                val sample = imageSample(w, h, preferred)
                val width = (w.toLong() + sample - 1) / sample
                val height = (h.toLong() + sample - 1) / sample
                assertEquals(0, sample and (sample - 1))
                assertTrue(sample >= preferred)
                assertTrue(width * height <= 4_194_304L)
                assertTrue(width <= 4096 && height <= 4096)
            }
        }
        assertEquals(8, imagePreferredSample(.1))
        assertEquals(1, imagePreferredSample(1.0))
        assertEquals(1, imagePreferredSample(8.0))
    }

    @Test
    fun visibleRegionUsesFullDetailWithoutInflatingTheWholeSource() {
        val g = ImageGeometry(16000, 12000, 6)
        val view =
            ImageViewport(g, 1920.0, 1080.0, 1.0, ImagePoint(g.width / 2.0, g.height / 2.0))
                .clamped()
        val region = g.rawRegion(view.visible(48.0))
        assertEquals(1, imageSample(region.width, region.height))
        assertTrue(region.width.toLong() * region.height < 3_000_000)
        assertTrue(region.width < g.rawWidth && region.height < g.rawHeight)
    }

    @Test
    fun variedViewportTransformsKeepRegionsWithinRawBounds() {
        val random = java.util.Random(17)
        repeat(600) {
            val g =
                ImageGeometry(
                    100 + random.nextInt(20000),
                    100 + random.nextInt(20000),
                    1 + random.nextInt(8),
                    random.nextInt(4),
                )
            val view =
                ImageViewport(
                        g,
                        300.0 + random.nextInt(2000),
                        200.0 + random.nextInt(1200),
                        10.0.pow(random.nextDouble() * 4 - 3),
                        ImagePoint(
                            random.nextDouble() * g.width * 2,
                            random.nextDouble() * g.height * 2,
                        ),
                    )
                    .clamped()
            val r = g.rawRegion(view.visible(48.0))
            assertTrue(
                r.left >= 0 &&
                    r.top >= 0 &&
                    r.right <= g.rawWidth &&
                    r.bottom <= g.rawHeight &&
                    r.width > 0 &&
                    r.height > 0,
            )
            for (p in
                listOf(
                    ImagePoint(r.left.toDouble(), r.top.toDouble()),
                    ImagePoint(r.right.toDouble(), r.bottom.toDouble()),
                )) {
                val oriented = g.transform.map(p)
                assertTrue(oriented.x in 0.0..g.width.toDouble())
                assertTrue(oriented.y in 0.0..g.height.toDouble())
            }
        }
    }

    @Test
    fun copiedMetadataIncludesCoordinatesOnlyWhenRevealed() {
        val details = PhotoDetails(listOf("Camera" to "Atlas X1"), 35.5 to 139.75)
        assertEquals("Camera: Atlas X1", details.text())
        assertTrue(details.text(true).contains("Location: 35.500000, 139.750000"))
    }

    @Test
    fun panoramaZoomLabelsDoNotRoundToZero() {
        assertEquals("100%", imageZoomPercent(1.0))
        assertEquals("25%", imageZoomPercent(.25))
        assertEquals("0.04%", imageZoomPercent(.0004))
        assertEquals("<0.01%", imageZoomPercent(.0000001))
    }
}
