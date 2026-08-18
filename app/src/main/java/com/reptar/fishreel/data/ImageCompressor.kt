package com.reptar.fishreel.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

private const val MAX_IMAGE_DIMENSION = 1600
private const val WEBP_QUALITY = 78

/**
 * Downscales and WebP-compresses a picked image before upload, applying its EXIF orientation
 * first. Camera photos taken in portrait are very often stored as landscape pixel data plus an
 * EXIF rotation tag telling viewers to rotate it on display -- BitmapFactory ignores that tag,
 * and re-compressing bakes in whatever orientation the raw pixels are already in and drops the
 * tag, so skipping this step means the uploaded photo comes out sideways for everyone.
 *
 * WebP (lossy) typically comes out 25-35% smaller than JPEG at comparable visual quality.
 * Android has decoded it natively since API 14, and iOS (deployment target 14+, loading images
 * via standard AsyncImage/UIImage) decodes it natively too via ImageIO -- confirmed compatible
 * with the iOS app before switching this from JPEG.
 *
 * Shared by post uploads (FeedViewModel) and profile photo uploads (AuthViewModel/ProfileScreen).
 */
object ImageCompressor {
    fun compress(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver

        val rotationDegrees = resolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).rotationDegrees
        } ?: 0

        val decoded = resolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input)
        } ?: error("Couldn't read the selected image")

        val upright = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } else {
            decoded
        }

        val scale = minOf(
            1f,
            MAX_IMAGE_DIMENSION.toFloat() / maxOf(upright.width, upright.height)
        )
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                upright,
                (upright.width * scale).toInt().coerceAtLeast(1),
                (upright.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            upright
        }

        // WEBP_LOSSY was only added in API 30; the plain WEBP constant it replaced is deprecated
        // but still functions identically (lossy encode) on the API 24-29 devices minSdk allows.
        val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

        return ByteArrayOutputStream().use { stream ->
            scaled.compress(webpFormat, WEBP_QUALITY, stream)
            stream.toByteArray()
        }
    }
}
