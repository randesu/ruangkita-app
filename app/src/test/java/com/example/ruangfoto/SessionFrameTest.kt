package com.ruangfoto.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFrameTest {
    @Test fun centerCropFillsPortraitFrameWithExifOrientedDimensions() {
        assertEquals(2f, frameCropScale(600, 400, 600, 800, 0f), 0f)
        assertEquals(1.5f, frameCropScale(600, 400, 600, 800, 90f), 0f)
        assertEquals(1.5f, frameCropScale(600, 400, 600, 800, 270f), 0f)
    }

    @Test fun customCanvasUsesTheChosenPixelsAndKeepsMatchPngNative() {
        // 3:4 lands on the default export long edge.
        assertEquals(FrameSize(1800, 2400), customCanvasSizeFromRatio(3, 4, MAX_CUSTOM_SIDE))
        assertEquals(FrameSize(2400, 1350), customCanvasSizeFromRatio(16, 9, MAX_CUSTOM_SIDE))
        // Exact pixels are exported as typed, and the same aspect survives a smaller preview render.
        assertEquals(1800 to 2400, frameCanvasSize(FrameCanvas.CUSTOM, MAX_CUSTOM_SIDE, 0, 0, 1800, 2400))
        assertEquals(750 to 1000, frameCanvasSize(FrameCanvas.CUSTOM, 1000, 0, 0, 1800, 2400))
        assertEquals(1250 to 1000, frameCanvasSize(FrameCanvas.CUSTOM, MAX_CUSTOM_SIDE, 0, 0, 1250, 1000))
        // Match PNG stays on the artwork's own resolution, so existing exports keep their size.
        assertEquals(60 to 120, frameCanvasSize(FrameCanvas.MATCH_PNG, MAX_CUSTOM_SIDE, 60, 120, 1800, 2400))
    }

    @Test fun customSizeStaysInsideTheSixMegapixelCap() {
        assertTrue(isValidCustomSize(FrameSize(2400, 2400)))
        assertFalse(isValidCustomSize(FrameSize(2400, 2401)))
        assertFalse(isValidCustomSize(FrameSize(0, 100)))
        assertEquals(600, gcd(1800, 2400))
    }

    @Test fun physicalCanvasUnitsConvertAtTheChosenDpi() {
        val expected = FrameSize(1181, 1772)
        assertEquals(expected, FramePrintSize(10.0, 15.0, FrameUnit.CENTIMETERS, 300).toPixels())
        assertEquals(expected, FramePrintSize(100.0, 150.0, FrameUnit.MILLIMETERS, 300).toPixels())
        assertEquals(FrameSize(1200, 1800), FramePrintSize(4.0, 6.0, FrameUnit.INCHES, 300).toPixels())
        assertEquals(FrameSize(600, 900), FramePrintSize(4.0, 6.0, FrameUnit.INCHES, 150).toPixels())
        assertEquals(null, FramePrintSize(21.0, 29.7, FrameUnit.CENTIMETERS, 300).toPixels())
        assertEquals(null, FramePrintSize(4.0, 6.0, FrameUnit.INCHES, 0).toPixels())
        assertEquals(null, FramePrintSize(Double.NaN, 6.0, FrameUnit.INCHES, 300).toPixels())
    }

    @Test fun layerOrderKeepsTheImageBetweenTextLayers() {
        val design = FrameDesign(
            overlay = "overlay-1.png",
            labels = listOf(FrameLabel("bottom"), FrameLabel("top")),
            overlayZ = 1
        )
        // Bottom to top: bottom text, image, top text.
        assertEquals(3, design.layerCount)
        assertEquals(0, design.labelIndexAt(0))
        assertTrue(design.isImageLayer(1))
        assertEquals(1, design.labelIndexAt(2))

        // Moving the image down swaps it with the bottom text.
        val imageDown = design.layerMoved(1, -1)
        assertEquals(0, imageDown.overlayZ)
        assertTrue(imageDown.isImageLayer(0))
        assertEquals(0, imageDown.labelIndexAt(1))

        // Moving a text layer past the image pushes the image the other way.
        val imagePushedUp = design.layerMoved(2, -1)
        assertEquals(2, imagePushedUp.overlayZ)
        assertEquals(listOf("bottom", "top"), imagePushedUp.labels.map { it.text })

        // Two text layers next to each other swap in the label list itself.
        val stacked = FrameDesign(
            overlay = "overlay-1.png",
            labels = listOf(FrameLabel("A"), FrameLabel("B"), FrameLabel("C")),
            overlayZ = 0
        )
        val swapped = stacked.layerMoved(2, -1)
        assertEquals(listOf("B", "A", "C"), swapped.labels.map { it.text })
        assertEquals(0, swapped.overlayZ)

        // Removing a label below the image pulls the image down with it.
        assertEquals(0, design.withLabelRemoved(0).overlayZ)
        assertEquals(1, design.withLabelRemoved(1).overlayZ)

        val blank = design.copy(labels = listOf(FrameLabel(" "), FrameLabel("top")))
        assertEquals(listOf("top"), blank.withoutBlankLabels().labels.map { it.text })
        assertEquals(0, blank.withoutBlankLabels().overlayZ)
    }

    @Test fun hiddenLayersStayInTheStackButAreReportedInvisible() {
        val design = FrameDesign(
            overlay = "overlay-1.png",
            labels = listOf(FrameLabel("caption", visible = false)),
            overlayZ = 0,
            imageLayer = FramePlacement(visible = false)
        )
        assertEquals(2, design.layerCount)
        assertFalse(design.isVisible(0))
        assertFalse(design.isVisible(1))
        // Order is data, not visibility: the image is still the bottom layer.
        assertTrue(design.isImageLayer(0))
        assertEquals(0, design.labelIndexAt(1))
    }
}
