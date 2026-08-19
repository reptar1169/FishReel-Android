package com.reptar.fishreel.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot

/**
 * One species' total count for a single day's report post -- see
 * [Post.speciesCountsPelagic]/[Post.speciesCountsCoastal]. Mirrors iOS's SpeciesCount and the
 * {species, count} shape written by the aggregateSpeciesCounts Cloud Function helper.
 */
data class SpeciesCount(val species: String = "", val count: Int = 0)

/** Parses a Firestore array-of-maps field into a list of [SpeciesCount], skipping malformed entries. */
private fun parseSpeciesCounts(raw: Any?): List<SpeciesCount> {
    val list = raw as? List<*> ?: return emptyList()
    return list.mapNotNull { entry ->
        val map = entry as? Map<*, *> ?: return@mapNotNull null
        val species = map["species"] as? String ?: return@mapNotNull null
        val count = (map["count"] as? Number)?.toInt() ?: 0
        SpeciesCount(species, count)
    }
}

/**
 * Mirrors the iOS app's `Post` struct field-for-field, since both apps share the same
 * Firestore "posts" collection. Do not rename these fields without updating the iOS app too.
 *
 * A post is exactly one of: photo (postImage), video (postVideo), or link (postURL) -- only
 * one of those three is ever non-blank on a given document. The link* fields are only
 * populated alongside postURL and hold a one-time snapshot of that URL's Open Graph metadata,
 * fetched at post-creation time so both apps can render a preview card without re-fetching
 * the page (and without breaking if the page later changes or goes offline).
 *
 * Note: `likes` is a denormalized, document-level count (kept in sync via FieldValue.increment
 * whenever a like is added/removed). Who has liked a post is tracked separately, one document
 * per like, in the shared top-level "postLikes" collection -- see PostRepository/Liker.
 */
data class Post(
    val id: String = "",
    val username: String = "",
    val userAvatar: String = "",
    val postImage: String = "",
    val postVideo: String = "",
    val postVideoThumbnail: String = "",
    val postURL: String = "",
    val linkTitle: String = "",
    val linkDescription: String = "",
    val linkImageURL: String = "",
    val linkDomain: String = "",
    val caption: String = "",
    val userID: String? = null,
    val likes: Int = 0,
    val commentCount: Int = 0,
    val createdAt: Timestamp? = null,
    /** Structured per-species totals for a FishReel Reports bot post (empty for every other
     * post, and for report posts written before this field existed) -- see the fish-count graph
     * feature, FishCountsGraphScreen. */
    val speciesCountsPelagic: List<SpeciesCount> = emptyList(),
    val speciesCountsCoastal: List<SpeciesCount> = emptyList()
) {
    /** A post has either a photo or a video, mirroring the iOS app's `isVideo` computed property. */
    val isVideo: Boolean get() = postVideo.isNotBlank()

    /** A link post shares a URL (with a fetched preview card) instead of media. */
    val isURLPost: Boolean get() = postURL.isNotBlank()

    /** False for a text-only post (caption, no photo/video/link). Mirrors iOS's Post.hasMedia. */
    val hasMedia: Boolean get() = postImage.isNotBlank() || isVideo || isURLPost

    /** iOS falls back to the SF Symbol name "person.circle" when there's no real avatar URL. */
    val hasPhotoAvatar: Boolean get() = userAvatar.startsWith("http")

    companion object {
        fun fromDocument(document: DocumentSnapshot): Post {
            val data = document.data ?: emptyMap<String, Any?>()
            return Post(
                id = document.id,
                username = data["username"] as? String ?: "Unknown",
                userAvatar = data["userAvatar"] as? String ?: "person.circle",
                postImage = data["postImage"] as? String ?: "",
                postVideo = data["postVideo"] as? String ?: "",
                postVideoThumbnail = data["postVideoThumbnail"] as? String ?: "",
                postURL = data["postURL"] as? String ?: "",
                linkTitle = data["linkTitle"] as? String ?: "",
                linkDescription = data["linkDescription"] as? String ?: "",
                linkImageURL = data["linkImageURL"] as? String ?: "",
                linkDomain = data["linkDomain"] as? String ?: "",
                caption = data["caption"] as? String ?: "",
                userID = data["userID"] as? String,
                likes = (data["likes"] as? Number)?.toInt() ?: 0,
                commentCount = (data["commentCount"] as? Number)?.toInt() ?: 0,
                createdAt = data["createdAt"] as? Timestamp,
                speciesCountsPelagic = parseSpeciesCounts(data["speciesCountsPelagic"]),
                speciesCountsCoastal = parseSpeciesCounts(data["speciesCountsCoastal"])
            )
        }
    }
}
