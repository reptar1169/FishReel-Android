package com.reptar.fishreel.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.reptar.fishreel.model.Post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * Builds a system Sharesheet intent for a post, mirroring iOS's ShareSheet
 * (UIActivityViewController). Photo posts download their image to cache and attach it as an
 * image stream so it previews inline in Messages/Instagram/etc, matching iOS's behavior of
 * sharing an actual image rather than just a link. Every post type also carries the post's
 * canonical fishreelapp.com/post/{id} link in its shared text, rather than the raw
 * Storage/video/external URL -- that link is what App Links (see the autoVerify intent-filter
 * in AndroidManifest.xml + MainActivity.extractSharedPostId) opens straight into the app for whoever
 * has it installed, and what the postLink Cloud Function (functions/index.js) unfurls into a
 * real photo + caption preview card on X/Facebook/etc. for whoever doesn't.
 */
object ShareHelper {
    private const val SITE_URL = "https://fishreelapp.com"

    suspend fun buildShareIntent(context: Context, post: Post): Intent = withContext(Dispatchers.IO) {
        val caption = post.caption.trim()
        val postLink = "$SITE_URL/post/${post.id}"
        val text = if (caption.isNotEmpty()) "$caption\n\n$postLink" else postLink

        if (post.postImage.isNotBlank()) {
            val downloaded = downloadImageToCache(context, post.postImage, post.id)
            if (downloaded != null) {
                return@withContext Intent(Intent.ACTION_SEND).apply {
                    type = downloaded.mimeType
                    putExtra(Intent.EXTRA_STREAM, downloaded.uri)
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            // Download failed (offline, broken URL, etc.) -- fall back to a text share of the
            // link below rather than leaving the share button silently doing nothing.
        }

        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }

    private class DownloadedImage(val uri: Uri, val mimeType: String)

    /**
     * Photo posts have been uploaded as either JPEG or WebP depending on when they were posted
     * (see ImageCompressor), so the real extension has to be read off the download URL rather
     * than assumed -- mislabeling a WebP file's bytes as image/jpeg breaks preview/decoding in
     * whatever app receives the share.
     */
    private fun downloadImageToCache(context: Context, imageUrl: String, postId: String): DownloadedImage? {
        return try {
            val extension = imageUrl.substringBefore("?")
                .substringAfterLast('.', missingDelimiterValue = "jpg")
                .lowercase()
            val mimeType = when (extension) {
                "webp" -> "image/webp"
                "png" -> "image/png"
                else -> "image/jpeg"
            }
            val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(sharedDir, "$postId.$extension")
            URL(imageUrl).openStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            DownloadedImage(uri, mimeType)
        } catch (_: Exception) {
            null
        }
    }
}
