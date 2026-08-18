package com.reptar.fishreel.data

import org.jsoup.Jsoup
import java.net.URI
import java.net.URL

/** A one-time snapshot of a URL's Open Graph metadata, ready to store on a link post. */
data class LinkMetadata(
    val title: String,
    val description: String,
    val imageURL: String,
    val domain: String
)

/**
 * Fetches a URL's HTML and pulls out Open Graph tags (og:title, og:description, og:image) for
 * link-post preview cards, matching what iOS's LPMetadataProvider does automatically. Falls
 * back to the page's <title> and just the bare host if OG tags are missing -- most sites have
 * at least one of these, but none are guaranteed.
 *
 * Must be called off the main thread (does blocking network I/O).
 */
object LinkMetadataFetcher {
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024 // 2MB is plenty for an HTML <head>

    fun fetch(rawUrl: String): LinkMetadata? {
        val normalizedUrl = normalize(rawUrl) ?: return null

        return try {
            val document = Jsoup.connect(normalizedUrl)
                .userAgent("Mozilla/5.0 (compatible; FishreelBot/1.0)")
                .timeout(TIMEOUT_MS)
                .maxBodySize(MAX_BODY_BYTES)
                .followRedirects(true)
                .get()

            val ogTitle = document.select("meta[property=og:title]").attr("content")
            val ogDescription = document.select("meta[property=og:description]").attr("content")
            // "abs:content" resolves a relative og:image path against the page's URL, so we
            // always end up with a fully-qualified image URL to store.
            val ogImage = document.select("meta[property=og:image]").attr("abs:content")

            val title = ogTitle.ifBlank { document.title() }.ifBlank { normalizedUrl }
            val domain = URI(normalizedUrl).host?.removePrefix("www.") ?: normalizedUrl

            LinkMetadata(
                title = title.trim(),
                description = ogDescription.trim(),
                imageURL = ogImage.trim(),
                domain = domain
            )
        } catch (_: Exception) {
            // Network failure, timeout, malformed HTML, etc. -- not fatal, the post can still
            // go out with just the bare URL and no preview card.
            null
        }
    }

    /** Adds a scheme if the user typed a bare domain (e.g. "example.com/article"). */
    private fun normalize(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "https://$trimmed"
        } else {
            trimmed
        }
        return try {
            URL(withScheme).toString()
        } catch (_: Exception) {
            null
        }
    }
}
