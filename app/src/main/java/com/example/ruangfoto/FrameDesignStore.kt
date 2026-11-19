package com.ruangfoto.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Layers are drawn bottom to top. Text labels keep their own order in [labels]; the imported
 * PNG sits at [overlayZ] among them, so one integer fully describes the stacking order.
 */
internal data class FrameDesign(
    val overlay: String? = null,
    val labels: List<FrameLabel> = emptyList(),
    val canvas: FrameCanvas = FrameCanvas.MATCH_PNG,
    val imageLayer: FramePlacement = FramePlacement(),
    val overlayZ: Int = 0,
    val customSize: FrameSize = FrameSize(1800, 2400),
    val printSize: FramePrintSize? = null
) {
    val layerCount: Int get() = labels.size + if (overlay == null) 0 else 1
    val topZ: Int get() = layerCount - 1

    fun isImageLayer(z: Int): Boolean = overlay != null && z == overlayZ

    /** Label index shown at stack position [z], or null when that position holds the PNG. */
    fun labelIndexAt(z: Int): Int? = when {
        overlay == null -> z.takeIf { it in labels.indices }
        z == overlayZ -> null
        z > overlayZ -> (z - 1).takeIf { it in labels.indices }
        else -> z.takeIf { it in labels.indices }
    }

    fun zForLabel(index: Int): Int = if (overlay != null && index >= overlayZ) index + 1 else index

    fun isVisible(z: Int): Boolean {
        val index = labelIndexAt(z)
        return if (index == null) isImageLayer(z) && imageLayer.visible else labels[index].visible
    }

    /** Swaps stack position [z] with its neighbour; +1 moves the layer up (later, on top). */
    fun layerMoved(z: Int, delta: Int): FrameDesign {
        val target = z + delta
        if (z !in 0 until layerCount || target !in 0 until layerCount) return this
        if (overlay != null && (z == overlayZ || target == overlayZ)) {
            return copy(overlayZ = if (z == overlayZ) target else z)
        }
        val from = labelIndexAt(z) ?: return this
        val to = labelIndexAt(target) ?: return this
        val reordered = labels.toMutableList()
        reordered[from] = labels[to]
        reordered[to] = labels[from]
        return copy(labels = reordered)
    }

    /** Keeps [overlayZ] inside the current label list after labels are added or removed. */
    fun withClampedOrder(): FrameDesign =
        copy(overlayZ = if (overlay == null) 0 else overlayZ.coerceIn(0, labels.size))

    /** Removes one text label while keeping the PNG at the same visual position in the stack. */
    fun withLabelRemoved(index: Int): FrameDesign {
        if (index !in labels.indices) return this
        val shift = if (overlay != null && index < overlayZ) 1 else 0
        return copy(
            labels = labels.filterIndexed { i, _ -> i != index },
            overlayZ = overlayZ - shift
        ).withClampedOrder()
    }

    /** Drops labels that were never given text, without moving the PNG relative to the rest. */
    fun withoutBlankLabels(): FrameDesign {
        if (labels.none { it.text.isBlank() }) return this
        val removedBelow = labels.take(overlayZ.coerceIn(0, labels.size)).count { it.text.isBlank() }
        val kept = labels.filter { it.text.isNotBlank() }
        return copy(
            labels = kept,
            overlayZ = if (overlay == null) 0 else (overlayZ - removedBelow).coerceIn(0, kept.size)
        )
    }
}

internal data class FrameLabel(
    val text: String,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val size: Float = 0.06f,
    val color: Int = -1,
    val font: String? = null,
    val visible: Boolean = true
)

/** Placement of the imported PNG: centre in canvas coordinates, [scale] multiplies the fit size. */
internal data class FramePlacement(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val scale: Float = 1f,
    val visible: Boolean = true
)

internal data class FrameSize(val width: Int, val height: Int)

internal enum class FrameUnit(val symbol: String, private val unitsPerInch: Double) {
    PIXELS("px", 0.0),
    CENTIMETERS("cm", 2.54),
    MILLIMETERS("mm", 25.4),
    INCHES("in", 1.0);

    fun pixelsPerUnit(dpi: Int): Double = if (this == PIXELS) 1.0 else dpi / unitsPerInch
}

internal const val DEFAULT_PRINT_DPI = 300
internal const val MAX_PRINT_DPI = 1200

internal data class FramePrintSize(val width: Double, val height: Double, val unit: FrameUnit, val dpi: Int) {
    fun toPixels(): FrameSize? {
        if (unit == FrameUnit.PIXELS || dpi !in 1..MAX_PRINT_DPI) return null
        val factor = unit.pixelsPerUnit(dpi)
        val widthPx = width * factor
        val heightPx = height * factor
        if (!widthPx.isFinite() || !heightPx.isFinite() ||
            widthPx < 0.5 || widthPx >= MAX_CUSTOM_SIDE + 0.5 ||
            heightPx < 0.5 || heightPx >= MAX_CUSTOM_SIDE + 0.5
        ) return null
        return FrameSize(widthPx.roundToInt(), heightPx.roundToInt())
    }
}

internal enum class FrameCanvas(val title: String) {
    MATCH_PNG("Match PNG"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    SQUARE("Square"),
    CUSTOM("Custom")
}

internal const val MAX_CUSTOM_SIDE = 2400
internal const val MAX_CUSTOM_PIXELS = 5_760_000L

internal fun isValidCustomSize(size: FrameSize): Boolean =
    size.width in 1..MAX_CUSTOM_SIDE && size.height in 1..MAX_CUSTOM_SIDE &&
        size.width.toLong() * size.height <= MAX_CUSTOM_PIXELS

/** Fits [widthRatio]:[heightRatio] inside [longEdge]; the long side lands exactly on it. */
internal fun customCanvasSizeFromRatio(widthRatio: Int, heightRatio: Int, longEdge: Int): FrameSize {
    val long = maxOf(widthRatio, heightRatio).coerceAtLeast(1)
    return FrameSize(
        (longEdge.toLong() * widthRatio / long).toInt().coerceIn(1, MAX_CUSTOM_SIDE),
        (longEdge.toLong() * heightRatio / long).toInt().coerceIn(1, MAX_CUSTOM_SIDE)
    )
}

internal fun gcd(first: Int, second: Int): Int {
    var a = first
    var b = second
    while (b != 0) {
        val next = a % b
        a = b
        b = next
    }
    return if (a == 0) 1 else a
}

/** Canvas pixels for a render of at most [longSide]; the artwork keeps its own resolution. */
internal fun frameCanvasSize(
    canvas: FrameCanvas,
    longSide: Int,
    artworkWidth: Int,
    artworkHeight: Int,
    customWidth: Int,
    customHeight: Int
): Pair<Int, Int> {
    val shortSide = (longSide * 2 / 3).coerceAtLeast(1)
    return when (canvas) {
        FrameCanvas.MATCH_PNG -> if (artworkWidth <= 0 || artworkHeight <= 0) {
            shortSide to longSide
        } else {
            val scale = minOf(1f, longSide.toFloat() / maxOf(artworkWidth, artworkHeight))
            (artworkWidth * scale).toInt().coerceAtLeast(1) to
                (artworkHeight * scale).toInt().coerceAtLeast(1)
        }
        FrameCanvas.PORTRAIT -> shortSide to longSide
        FrameCanvas.LANDSCAPE -> longSide to shortSide
        FrameCanvas.SQUARE -> longSide to longSide
        FrameCanvas.CUSTOM -> {
            val width = customWidth.coerceIn(1, MAX_CUSTOM_SIDE)
            val height = customHeight.coerceIn(1, MAX_CUSTOM_SIDE)
            val scale = minOf(1f, longSide.toFloat() / maxOf(width, height))
            (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
        }
    }
}

internal data class FrameRevision(val id: String, val createdAt: Long, val title: String)

internal data class FrameExport(val overlay: File, val printDpi: Int?)

/** Capture and editor read the same immutable revision through an atomically replaced pointer. */
internal class FrameDesignStore(directory: File) {
    private val root = File(directory, STORE_DIRECTORY)
    private val assets = File(root, ASSETS_DIRECTORY)
    private val revisionsDirectory = File(root, REVISIONS_DIRECTORY)
    private val activeFile = File(root, ACTIVE_NAME)
    private val previewSecondsFile = File(root, PREVIEW_SECONDS_NAME)
    private val typefaces = ConcurrentHashMap<String, Typeface>()
    private val artworkSizes = ConcurrentHashMap<String, Pair<Int, Int>>()

    fun activeDesign(): FrameDesign? = activeRevisionId()?.let(::loadRevision)

    fun activeOverlay(): File? = activeRevisionId()?.let(::overlayForRevision)

    fun activeExport(): FrameExport? {
        val id = activeRevisionId() ?: return null
        val overlay = overlayForRevision(id)
        val printDpi = try {
            val metadata = JSONObject(File(overlay.parentFile, REVISION_NAME).readText())
            metadata.optInt("printDpi").takeIf { it in 1..MAX_PRINT_DPI }
        } catch (e: JSONException) {
            throw IOException("The active frame revision is unreadable.", e)
        }
        return FrameExport(overlay, printDpi)
    }

    private fun overlayForRevision(id: String): File {
        val overlay = File(File(revisionsDirectory, id), OVERLAY_NAME)
        if (!overlay.isFile) throw IOException("The active frame overlay is missing.")
        return overlay
    }

    private fun activeRevisionId(): String? {
        val id = try {
            String(Files.readAllBytes(activeFile.toPath()), Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            return null
        }
        if (!SAFE_ID.matches(id)) throw IOException("The active frame revision is unreadable.")
        return id
    }

    fun revisions(): List<FrameRevision> {
        return revisionsDirectory.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { revisionDirectory ->
                runCatching {
                    val metadata = JSONObject(File(revisionDirectory, REVISION_NAME).readText())
                    val id = metadata.getString("id")
                    require(id == revisionDirectory.name)
                    require(File(revisionDirectory, DESIGN_NAME).isFile)
                    FrameRevision(id, metadata.getLong("createdAt"), metadata.getString("title"))
                }.getOrNull()
            }
            .sortedWith(compareByDescending<FrameRevision> { it.createdAt }.thenByDescending { it.id })
    }

    fun loadRevision(id: String): FrameDesign {
        require(SAFE_ID.matches(id)) { "Unknown frame revision." }
        val revisionDirectory = File(revisionsDirectory, id)
        if (!revisionDirectory.isDirectory) throw IOException("That frame revision is no longer available.")
        val recorded = runCatching {
            JSONObject(File(revisionDirectory, REVISION_NAME).readText()).getString("id")
        }.getOrNull()
        if (recorded != id) throw IOException("That frame revision is unreadable.")
        return decodeDesign(File(revisionDirectory, DESIGN_NAME))
    }

    @Synchronized
    fun save(design: FrameDesign) {
        validate(design)
        require(design.overlay != null || design.labels.isNotEmpty()) {
            "Add a frame image or at least one text label."
        }
        require(design.labels.none { it.text.isBlank() }) {
            "Give every label some text or remove it."
        }
        ensureDirectory(root)
        ensureDirectory(assets)
        ensureDirectory(revisionsDirectory)

        val json = encodeDesign(design)
        val createdAt = System.currentTimeMillis()
        val id = "rev-$createdAt-${UUID.randomUUID().toString().take(8)}"
        val revisionDirectory = File(revisionsDirectory, id)
        val staging = Files.createTempDirectory(revisionsDirectory.toPath(), ".pending-").toFile()
        try {
            val preview = renderPreview(design, MAX_RENDER_SIDE)
            try {
                writePng(preview, File(staging, OVERLAY_NAME))
            } finally {
                preview.recycle()
            }
            File(staging, DESIGN_NAME).writeBytes(json)
            File(staging, REVISION_NAME).writeBytes(revisionMetadata(id, createdAt, design))
            Files.move(staging.toPath(), revisionDirectory.toPath(), ATOMIC_MOVE)
            writeAtomically(activeFile) { it.writeText(id) }
        } catch (error: Exception) {
            revisionDirectory.deleteRecursively()
            throw error
        } finally {
            staging.deleteRecursively()
        }
    }

    @Synchronized
    fun disable() {
        Files.deleteIfExists(activeFile.toPath())
    }

    /** Validates and stores a PNG frame; returns its internal asset reference. */
    fun importOverlay(input: InputStream): String =
        importAsset(input, MAX_OVERLAY_BYTES, "overlay-", ".png", ::validateImportedOverlay)

    /** Validates and stores a TrueType/OpenType font; returns its internal asset reference. */
    fun importFont(input: InputStream): String =
        importAsset(input, MAX_FONT_BYTES, "font-", ".ttf", ::validateImportedFont)

    fun renderPreview(design: FrameDesign, maxSide: Int = 1000): Bitmap {
        validate(design)
        val longSide = maxSide.coerceIn(MIN_PREVIEW_SIDE, MAX_RENDER_SIDE)
        val artwork = design.overlay?.let(::decodeArtwork)
        try {
            val (width, height) = frameCanvasSize(
                design.canvas, longSide, artwork?.width ?: 0, artwork?.height ?: 0,
                design.customSize.width, design.customSize.height
            )
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(result)
                for (z in 0..design.labels.size) {
                    if (artwork != null && z == design.overlayZ && design.imageLayer.visible) {
                        drawImageLayer(canvas, artwork, design.imageLayer)
                    }
                    val label = design.labelIndexAt(z)?.let(design.labels::get) ?: continue
                    if (label.visible) drawLabel(canvas, label)
                }
                return result
            } catch (error: Throwable) {
                result.recycle()
                throw error
            }
        } finally {
            artwork?.recycle()
        }
    }

    /** Bounds of one stack layer inside a [width] x [height] render, for the editor's selection box. */
    fun layerBounds(design: FrameDesign, z: Int, width: Int, height: Int): RectF? {
        if (z !in 0 until design.layerCount) return null
        val index = design.labelIndexAt(z) ?: return design.overlay?.let {
            imageLayerRect(design.imageLayer, artworkSize(it), width, height)
        }
        val label = design.labels[index]
        // A missing or broken font only loses the selection box here; rendering still reports it.
        return runCatching {
            val paint = textPaint(label, width, height)
            val lines = label.text.split('\n')
            val centerX = label.x * width
            val centerY = label.y * height
            val halfWidth = lines.maxOf { paint.measureText(it) } / 2f
            val halfHeight = paint.fontSpacing * lines.size / 2f
            RectF(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
        }.getOrNull()
    }

    /** Small preview of the imported PNG for the layer panel, decoded once per reference. */
    fun overlayThumbnail(reference: String, maxSide: Int = 96): Bitmap? {
        val file = File(assets, reference)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun previewDurationSeconds(): Int =
        runCatching { previewSecondsFile.readText().trim().toInt() }.getOrNull()
            ?.coerceIn(0, MAX_PREVIEW_SECONDS) ?: DEFAULT_PREVIEW_SECONDS

    @Synchronized
    fun setPreviewDurationSeconds(seconds: Int) {
        require(seconds in 0..MAX_PREVIEW_SECONDS) {
            "Preview duration must be between 0 and $MAX_PREVIEW_SECONDS seconds."
        }
        ensureDirectory(root)
        writeAtomically(previewSecondsFile) { it.writeText(seconds.toString()) }
    }

    private fun validate(design: FrameDesign) {
        require(design.overlay == null || SAFE_REF.matches(design.overlay)) { "Invalid frame artwork reference." }
        require(design.labels.size <= MAX_LABELS) { "Use no more than $MAX_LABELS text labels." }
        for (label in design.labels) {
            require(label.text.length <= MAX_LABEL_CHARS) {
                "Each label can use up to $MAX_LABEL_CHARS characters."
            }
            require(label.x.isFinite() && label.x in 0f..1f) { "Label position must stay inside the frame." }
            require(label.y.isFinite() && label.y in 0f..1f) { "Label position must stay inside the frame." }
            require(label.size.isFinite() && label.size in MIN_TEXT_SIZE..MAX_TEXT_SIZE) {
                "Label size must be between $MIN_TEXT_SIZE and $MAX_TEXT_SIZE of the shortest side."
            }
            require(label.font == null || SAFE_REF.matches(label.font)) { "Invalid font reference." }
        }
        require(design.overlayZ in 0..design.labels.size) { "Invalid layer order." }
        val placement = design.imageLayer
        require(placement.x.isFinite() && placement.x in 0f..1f) {
            "The frame image must stay inside the frame."
        }
        require(placement.y.isFinite() && placement.y in 0f..1f) {
            "The frame image must stay inside the frame."
        }
        require(placement.scale.isFinite() && placement.scale in MIN_IMAGE_SCALE..MAX_IMAGE_SCALE) {
            "The frame image size must be between ${(MIN_IMAGE_SCALE * 100).toInt()}% and " +
                "${(MAX_IMAGE_SCALE * 100).toInt()}%."
        }
        require(isValidCustomSize(design.customSize)) {
            "Choose a canvas up to $MAX_CUSTOM_SIDE pixels per side and 6 megapixels."
        }
        if (design.printSize != null) {
            require(design.printSize.toPixels() == design.customSize) { "Invalid print size or DPI." }
        }
    }

    private fun encodeDesign(design: FrameDesign): ByteArray {
        val labels = JSONArray()
        for (label in design.labels) {
            labels.put(JSONObject().apply {
                put("text", label.text)
                put("x", label.x.toDouble())
                put("y", label.y.toDouble())
                put("size", label.size.toDouble())
                put("color", label.color)
                put("font", label.font ?: JSONObject.NULL)
                put("visible", label.visible)
            })
        }
        val placement = design.imageLayer
        val json = JSONObject().apply {
            put("overlay", design.overlay ?: JSONObject.NULL)
            put("canvas", design.canvas.name)
            put("labels", labels)
            put("imageLayer", JSONObject().apply {
                put("x", placement.x.toDouble())
                put("y", placement.y.toDouble())
                put("scale", placement.scale.toDouble())
                put("visible", placement.visible)
            })
            put("overlayZ", design.overlayZ)
            put("customSize", JSONObject().apply {
                put("width", design.customSize.width)
                put("height", design.customSize.height)
            })
            design.printSize?.let { size ->
                put("printSize", JSONObject().apply {
                    put("width", size.width)
                    put("height", size.height)
                    put("unit", size.unit.name)
                    put("dpi", size.dpi)
                })
            }
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decodeDesign(file: File): FrameDesign {
        if (!file.isFile) throw IOException("No saved frame design.")
        if (file.length() > MAX_DESIGN_BYTES) throw IOException("Saved frame design is unreadable.")
        val json = try {
            JSONObject(file.readText())
        } catch (e: JSONException) {
            throw IOException("Saved frame design is unreadable.", e)
        }
        val design = try {
            val labelsJson = json.getJSONArray("labels")
            val labels = ArrayList<FrameLabel>(labelsJson.length())
            for (index in 0 until labelsJson.length()) {
                val label = labelsJson.getJSONObject(index)
                labels += FrameLabel(
                    text = label.getString("text"),
                    x = label.getDouble("x").toFloat(),
                    y = label.getDouble("y").toFloat(),
                    size = label.getDouble("size").toFloat(),
                    color = label.optInt("color", -1),
                    font = if (label.isNull("font")) null else label.optString("font").takeIf { it.isNotEmpty() },
                    visible = label.optBoolean("visible", true)
                )
            }
            FrameDesign(
                overlay = if (json.isNull("overlay")) null else json.optString("overlay").takeIf { it.isNotEmpty() },
                labels = labels,
                canvas = FrameCanvas.valueOf(json.getString("canvas")),
                imageLayer = json.optJSONObject("imageLayer")?.let { layer ->
                    FramePlacement(
                        x = layer.optDouble("x", 0.5).toFloat(),
                        y = layer.optDouble("y", 0.5).toFloat(),
                        scale = layer.optDouble("scale", 1.0).toFloat(),
                        visible = layer.optBoolean("visible", true)
                    )
                } ?: FramePlacement(),
                overlayZ = json.optInt("overlayZ", 0),
                customSize = json.optJSONObject("customSize")?.let { size ->
                    FrameSize(size.optInt("width", 1800), size.optInt("height", 2400))
                } ?: FrameSize(1800, 2400),
                printSize = json.optJSONObject("printSize")?.let { size ->
                    FramePrintSize(size.getDouble("width"), size.getDouble("height"),
                        FrameUnit.valueOf(size.getString("unit")), size.getInt("dpi"))
                }
            )
        } catch (e: JSONException) {
            throw IOException("Saved frame design is unreadable.", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Saved frame design is unreadable.", e)
        }
        try {
            validate(design)
        } catch (e: IllegalArgumentException) {
            throw IOException("Saved frame design is invalid: ${e.message}", e)
        }
        return design
    }

    private fun revisionMetadata(id: String, createdAt: Long, design: FrameDesign): ByteArray =
        JSONObject().apply {
            put("id", id)
            put("createdAt", createdAt)
            put("title", revisionTitle(design))
            if (design.canvas == FrameCanvas.CUSTOM) design.printSize?.let { put("printDpi", it.dpi) }
        }.toString().toByteArray(Charsets.UTF_8)

    private fun revisionTitle(design: FrameDesign): String = buildList {
        add(design.canvas.title)
        add(if (design.overlay != null) "frame" else "text")
        if (design.labels.isNotEmpty()) {
            add("${design.labels.size} label${if (design.labels.size == 1) "" else "s"}")
        }
    }.joinToString(" · ")

    private fun decodeArtwork(reference: String): Bitmap {
        val file = File(assets, reference)
        if (!file.isFile) throw IOException("The frame artwork is missing. Import the PNG again.")
        return BitmapFactory.decodeFile(file.path)
            ?: throw IOException("The frame artwork is unreadable. Import the PNG again.")
    }

    private fun drawImageLayer(canvas: Canvas, artwork: Bitmap, placement: FramePlacement) {
        val rect = imageLayerRect(placement, artwork.width to artwork.height, canvas.width, canvas.height)
        canvas.drawBitmap(artwork, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** The PNG is fitted inside the canvas (never cropped), then scaled around its centre. */
    private fun imageLayerRect(
        placement: FramePlacement,
        artworkSize: Pair<Int, Int>,
        width: Int,
        height: Int
    ): RectF {
        val (artworkWidth, artworkHeight) = artworkSize
        if (artworkWidth <= 0 || artworkHeight <= 0) return RectF()
        val fit = minOf(width / artworkWidth.toFloat(), height / artworkHeight.toFloat())
        val drawnWidth = artworkWidth * fit * placement.scale
        val drawnHeight = artworkHeight * fit * placement.scale
        val centerX = placement.x * width
        val centerY = placement.y * height
        return RectF(
            centerX - drawnWidth / 2f, centerY - drawnHeight / 2f,
            centerX + drawnWidth / 2f, centerY + drawnHeight / 2f
        )
    }

    private fun artworkSize(reference: String): Pair<Int, Int> =
        artworkSizes.getOrPut(reference) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(File(assets, reference).path, bounds)
            bounds.outWidth to bounds.outHeight
        }

    private fun textPaint(label: FrameLabel, width: Int, height: Int): Paint {
        val shortestSide = minOf(width, height).toFloat()
        return Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = label.color
            textSize = label.size * shortestSide
            textAlign = Paint.Align.CENTER
            typeface = label.font?.let(::typeface) ?: Typeface.DEFAULT
        }
    }

    private fun drawLabel(canvas: Canvas, label: FrameLabel) {
        val paint = textPaint(label, canvas.width, canvas.height)
        val lines = label.text.split('\n')
        val lineHeight = paint.fontSpacing
        var baseline = label.y * canvas.height - lineHeight * (lines.size - 1) / 2f -
            (paint.ascent() + paint.descent()) / 2f
        for (line in lines) {
            canvas.drawText(line, label.x * canvas.width, baseline, paint)
            baseline += lineHeight
        }
    }

    private fun typeface(reference: String): Typeface =
        typefaces.getOrPut(reference) {
            val file = File(assets, reference)
            if (!file.isFile) throw IOException("The custom font is missing. Import it again.")
            try {
                Typeface.createFromFile(file)
            } catch (e: RuntimeException) {
                throw IOException("The custom font is unreadable. Import it again.", e)
            }
        }

    private fun importAsset(input: InputStream, maxBytes: Int, prefix: String, extension: String,
                            validate: (File) -> Unit): String {
        ensureDirectory(assets)
        val staging = File.createTempFile(".ruangfoto-", ".part", assets)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            staging.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes.toLong()) { "Choose a file smaller than ${maxBytes / (1024 * 1024)} MB." }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
                require(total > 0L) { "Choose a non-empty file." }
            }
            validate(staging)
            val hash = digest.digest().joinToString("") {
                ((it.toInt() and 0xff) + 0x100).toString(16).substring(1)
            }.take(16)
            val name = prefix + hash + extension
            val target = File(assets, name)
            if (!target.isFile) {
                try {
                    Files.move(staging.toPath(), target.toPath(), ATOMIC_MOVE)
                } catch (_: FileAlreadyExistsException) {
                    // An identical asset was stored by another import; the content is the same.
                }
            }
            return name
        } finally {
            staging.delete()
        }
    }

    private fun validateImportedOverlay(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outMimeType == "image/png" && bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Choose a valid PNG frame."
        }
        require(bounds.outWidth <= MAX_OVERLAY_SIDE && bounds.outHeight <= MAX_OVERLAY_SIDE &&
            bounds.outWidth.toLong() * bounds.outHeight <= MAX_OVERLAY_PIXELS) {
            "Choose a PNG up to $MAX_OVERLAY_SIDE pixels per side and 6 megapixels."
        }
        val artwork = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 })
            ?: throw IllegalArgumentException("Cannot read that PNG.")
        artwork.recycle()
    }

    private fun validateImportedFont(file: File) {
        val header = ByteArray(4)
        val read = file.inputStream().use { it.read(header) }
        val tag = String(header, Charsets.ISO_8859_1)
        val sfnt = header.contentEquals(byteArrayOf(0, 1, 0, 0)) || tag == "OTTO" || tag == "true" || tag == "ttcf"
        require(read == 4 && sfnt) { "Choose a valid TrueType or OpenType font." }
        try {
            Typeface.createFromFile(file)
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("Choose a valid TrueType or OpenType font.", e)
        }
    }

    private fun writePng(bitmap: Bitmap, target: File) {
        target.outputStream().use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IOException("Cannot save the frame preview.")
            }
        }
    }

    private fun writeAtomically(target: File, write: (File) -> Unit) {
        val staging = File.createTempFile(".ruangfoto-", ".part", target.parentFile)
        try {
            write(staging)
            Files.move(staging.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            staging.delete()
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create ${directory.name}.")
        }
    }

    internal companion object {
        const val DEFAULT_PREVIEW_SECONDS = 15
        const val MAX_PREVIEW_SECONDS = 60

        private const val STORE_DIRECTORY = ".ruangfoto-design"
        private const val ASSETS_DIRECTORY = "assets"
        private const val REVISIONS_DIRECTORY = "revisions"
        private const val DESIGN_NAME = "design.json"
        private const val ACTIVE_NAME = "active-revision"
        private const val OVERLAY_NAME = "overlay.png"
        private const val REVISION_NAME = "revision.json"
        private const val PREVIEW_SECONDS_NAME = "preview-seconds"
        private const val MAX_DESIGN_BYTES = 1_000_000L
        private const val MAX_OVERLAY_BYTES = 32 * 1024 * 1024
        private const val MAX_FONT_BYTES = 16 * 1024 * 1024
        private const val MAX_OVERLAY_PIXELS = 6_000_000L
        private const val MAX_OVERLAY_SIDE = 4096
        private const val MAX_LABELS = 50
        private const val MAX_LABEL_CHARS = 500
        private const val MIN_TEXT_SIZE = 0.005f
        private const val MAX_TEXT_SIZE = 0.5f
        private const val MIN_PREVIEW_SIDE = 64
        private const val MAX_RENDER_SIDE = MAX_CUSTOM_SIDE
        private const val MIN_IMAGE_SCALE = 0.1f
        private const val MAX_IMAGE_SCALE = 4f
        private val SAFE_REF = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    }
}
