package com.reptar.fishreel.data

import android.content.Intent
import android.net.Uri
import com.reptar.fishreel.model.Post

/**
 * Builds an email report for a post, addressed to the abuse/CSAM reporting contact published in
 * Fishreel's Terms of Use. Deliberately ACTION_SENDTO (not ACTION_SEND/a general Sharesheet) so
 * this only ever opens an email app, and pre-fills enough detail -- post id, author, a direct
 * content link -- that a report is actionable without the reporter needing to dig anything up.
 * Keep the address here in sync with the Terms of Use's reporting section if it ever changes.
 */
object ReportHelper {
    private const val REPORT_EMAIL = "fishreelapp@gmail.com"

    fun buildReportIntent(post: Post): Intent {
        val contentUrl = when {
            post.postImage.isNotBlank() -> post.postImage
            post.postVideo.isNotBlank() -> post.postVideo
            post.postURL.isNotBlank() -> post.postURL
            else -> "(no media URL)"
        }
        val body = buildString {
            appendLine("Please describe the issue with this post below this line:")
            appendLine("----------")
            appendLine()
            appendLine("Post ID: ${post.id}")
            appendLine("Posted by: ${post.username.ifBlank { "Unknown" }} (userID: ${post.userID ?: "unknown"})")
            appendLine("Content link: $contentUrl")
            if (post.caption.isNotBlank()) {
                appendLine("Caption: ${post.caption}")
            }
        }
        return Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "Fishreel report: post ${post.id}")
            putExtra(Intent.EXTRA_TEXT, body)
        }
    }
}
