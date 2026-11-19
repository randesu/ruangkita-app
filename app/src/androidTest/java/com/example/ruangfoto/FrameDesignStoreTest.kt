package com.ruangfoto.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FrameDesignStoreTest {
    @Test fun savesRevisionsAndDisablesWithoutLosingHistory() {
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "design-test-${UUID.randomUUID()}"
        ).apply { check(mkdir()) }
        try {
            val design = FrameDesign(
                canvas = FrameCanvas.SQUARE,
                labels = listOf(FrameLabel("Emoji 😀\nsecond line", x = 0.5f, y = 0.5f, size = 0.1f, color = Color.WHITE))
            )
            FrameDesignStore(directory).save(design)

            // A fresh store instance simulates reopening the session in another process.
            val reopened = FrameDesignStore(directory)
            assertEquals(design, reopened.activeDesign())
            val activeOverlay = checkNotNull(reopened.activeOverlay())
            val overlayBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(activeOverlay.path, overlayBounds)
            assertEquals("image/png", overlayBounds.outMimeType)
            assertEquals(2400, overlayBounds.outHeight)

            val revisions = reopened.revisions()
            assertEquals(1, revisions.size)
            assertEquals(design, reopened.loadRevision(revisions.single().id))
            try {
                reopened.loadRevision("../../escape")
                fail("An invalid revision id was accepted")
            } catch (_: IllegalArgumentException) {
            }

            reopened.disable()
            assertNull(FrameDesignStore(directory).activeDesign())
            assertNull(FrameDesignStore(directory).activeOverlay())
            assertEquals(1, FrameDesignStore(directory).revisions().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun rendersRedTextWithoutATemplateAndFallsBackOnMalformedDuration() {
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "design-test-${UUID.randomUUID()}"
        ).apply { check(mkdir()) }
        try {
            val store = FrameDesignStore(directory)
            assertEquals(15, store.previewDurationSeconds())
            store.setPreviewDurationSeconds(0)
            assertEquals(0, store.previewDurationSeconds())
            store.setPreviewDurationSeconds(30)
            assertEquals(30, store.previewDurationSeconds())
            File(directory, ".ruangfoto-design/preview-seconds").writeText("not-a-number")
            assertEquals(15, FrameDesignStore(directory).previewDurationSeconds())

            val design = FrameDesign(
                canvas = FrameCanvas.LANDSCAPE,
                labels = listOf(FrameLabel("R", x = 0.5f, y = 0.5f, size = 0.3f, color = Color.RED))
            )
            val preview = store.renderPreview(design, 300)
            try {
                assertEquals(300, preview.width)
                assertEquals(200, preview.height)
                assertTrue(hasRedPixelNearCenter(preview))
            } finally {
                preview.recycle()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun keepsLayerOrderVisibilityAndCustomCanvasInTheRevision() {
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "design-test-${UUID.randomUUID()}"
        ).apply { check(mkdir()) }
        try {
            val store = FrameDesignStore(directory)
            val overlay = store.importOverlay(topBandPng().inputStream())
            val design = FrameDesign(
                overlay = overlay,
                labels = listOf(FrameLabel("Caption", visible = false), FrameLabel("Title")),
                canvas = FrameCanvas.CUSTOM,
                imageLayer = FramePlacement(x = 0.25f, y = 0.75f, scale = 1.5f, visible = false),
                overlayZ = 1,
                customSize = FrameSize(1200, 1600)
            )
            store.save(design)
            assertEquals(design, FrameDesignStore(directory).activeDesign())

            val overlayFile = checkNotNull(FrameDesignStore(directory).activeOverlay())
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(overlayFile.path, bounds)
            // The saved frame is the exact canvas that was chosen, not a resampled guess.
            assertEquals(1200, bounds.outWidth)
            assertEquals(1600, bounds.outHeight)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun fitsTheImageInsideTheCanvasAndSkipsHiddenLayers() {
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "design-test-${UUID.randomUUID()}"
        ).apply { check(mkdir()) }
        try {
            val store = FrameDesignStore(directory)
            val design = FrameDesign(
                overlay = store.importOverlay(topBandPng().inputStream()),
                labels = listOf(FrameLabel("R", x = 0.5f, y = 0.5f, size = 0.3f,
                    color = Color.RED, visible = false)),
                canvas = FrameCanvas.CUSTOM,
                customSize = FrameSize(200, 200)
            )
            val preview = store.renderPreview(design, 200)
            try {
                assertEquals(200, preview.width)
                assertEquals(200, preview.height)
                // Pixel (100, 5) is inside the artwork only when the image is fitted, not cropped.
                val band = preview.getPixel(100, 5)
                assertTrue(Color.red(band) > 200 && Color.blue(band) < 60)
                // The hidden text layer contributes nothing to the centre.
                assertFalse(hasRedPixelNearCenter(preview))
            } finally {
                preview.recycle()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun topBandPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 120, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawRect(0f, 0f, 60f, 16f, Paint().apply { color = Color.RED })
            return ByteArrayOutputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun hasRedPixelNearCenter(bitmap: Bitmap): Boolean {
        for (y in bitmap.height / 3 until bitmap.height * 2 / 3) {
            for (x in bitmap.width / 3 until bitmap.width * 2 / 3) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 0 && Color.red(pixel) > 200 &&
                    Color.green(pixel) < 80 && Color.blue(pixel) < 80) return true
            }
        }
        return false
    }
}
