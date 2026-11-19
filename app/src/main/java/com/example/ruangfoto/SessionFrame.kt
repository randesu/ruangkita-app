package com.ruangfoto.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal fun framedPhotoDirectory(sessionDirectory: File): File = File(sessionDirectory, "Framed")

internal class SessionFrame(directory: File) {
    private val designs = FrameDesignStore(directory)
    val outputDirectory = framedPhotoDirectory(directory)

    fun outputFor(photo: File): File = File(outputDirectory, photo.name)

    /** Returns false if any copy could not be saved. Original photos are never edited. */
    fun renderNewPhotos(photos: List<File>): Boolean {
        if (photos.isEmpty()) return true
        val export = try {
            designs.activeExport() ?: return true
        } catch (_: IOException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (!outputDirectory.isDirectory && !outputDirectory.mkdirs()) return false
        val frame = BitmapFactory.decodeFile(export.overlay.path) ?: return false
        try {
            var saved = true
            for (photo in photos) {
                val output = outputFor(photo)
                if (output.exists()) continue
                try {
                    renderPhoto(photo, frame, output, export.printDpi)
                } catch (_: IOException) {
                    saved = false
                }
            }
            return saved
        } finally {
            frame.recycle()
        }
    }

    private fun renderPhoto(file: File, frame: Bitmap, output: File, printDpi: Int?) {
        val orientation = ExifInterface(file.path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        val bounds = imageBounds(file)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Cannot decode JPEG")
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_PIXELS) sample *= 2
        val photo = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Cannot decode JPEG")
        try {
            val result = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            try {
                drawFramedPhoto(result, photo, frame, orientation)
                writeAtomically(output) { staged ->
                    staged.outputStream().use { stream ->
                        if (!result.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw IOException("Cannot write JPEG")
                    }
                    if (printDpi != null) {
                        ExifInterface(staged.path).apply {
                            setAttribute(ExifInterface.TAG_X_RESOLUTION, "$printDpi/1")
                            setAttribute(ExifInterface.TAG_Y_RESOLUTION, "$printDpi/1")
                            setAttribute(ExifInterface.TAG_RESOLUTION_UNIT, "2") // EXIF: inches
                            saveAttributes()
                        }
                    }
                }
            } finally {
                result.recycle()
            }
        } finally {
            photo.recycle()
        }
    }

    private fun drawFramedPhoto(result: Bitmap, photo: Bitmap, frame: Bitmap, orientation: Int) {
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSVERSE -> 90f
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180f
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE -> 270f
            else -> 0f
        }
        val flip = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL, ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> -1f
            else -> 1f
        }
        val scale = frameCropScale(photo.width, photo.height, frame.width, frame.height, rotation)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val halfWidth = photo.width / 2f
        val halfHeight = photo.height / 2f
        canvas.save()
        canvas.translate(frame.width / 2f, frame.height / 2f)
        canvas.scale(scale, scale)
        canvas.rotate(rotation)
        canvas.scale(flip, 1f)
        canvas.drawBitmap(photo, null, RectF(-halfWidth, -halfHeight, halfWidth, halfHeight), paint)
        canvas.restore()
        canvas.drawBitmap(frame, null, RectF(0f, 0f, frame.width.toFloat(), frame.height.toFloat()), paint)
    }

    private fun imageBounds(file: File) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.path, this)
    }

    private fun writeAtomically(target: File, write: (File) -> Unit) {
        val staged = File.createTempFile(".ruangfoto-", ".part", target.parentFile)
        try {
            write(staged)
            Files.move(staged.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            staged.delete()
        }
    }

    private companion object {
        const val MAX_PIXELS = 6_000_000L
    }
}

internal fun frameCropScale(photoWidth: Int, photoHeight: Int, frameWidth: Int, frameHeight: Int, rotation: Float): Float {
    val quarterTurn = rotation == 90f || rotation == 270f
    val width = if (quarterTurn) photoHeight else photoWidth
    val height = if (quarterTurn) photoWidth else photoHeight
    return maxOf(frameWidth.toFloat() / width, frameHeight.toFloat() / height)
}
