package com.reptar.fishreel.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream

private const val MAX_THUMBNAIL_DIMENSION = 800
private const val THUMBNAIL_WEBP_QUALITY = 75

/**
 * Extracts a poster-frame thumbnail from a picked video, mirroring iOS's VideoUploader.upload()
 * thumbnail generation -- lets the feed show a static image instantly instead of waiting on the
 * video itself to load (see VideoPlayer). Grabs the frame nearest the very start of the clip,
 * since that's almost always representative and needs no guessing about the "best" frame.
 *
 * Downscaled well below ImageCompressor's 1600px cap since this is only ever shown at feed-row
 * size, and WebP-encoded for the same size-savings reasoning as photo posts.
 */
object VideoThumbnailExtractor {
    fun extractThumbnail(context: Context, videoUri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, videoUri)
            val frame = retriever.frameAtTime ?: return null

            val scale = minOf(1f, MAX_THUMBNAIL_DIMENSION.toFloat() / maxOf(frame.width, frame.height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                frame
            }

            val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            ByteArrayOutputStream().use { stream ->
                scaled.compress(webpFormat, THUMBNAIL_WEBP_QUALITY, stream)
                stream.toByteArray()
            }
        } catch (_: Exception) {
            // Best-effort -- a failed extraction just means this post falls back to the
            // no-thumbnail video display, not a blocked upload.
            null
        } finally {
            retriever.release()
        }
    }
}
