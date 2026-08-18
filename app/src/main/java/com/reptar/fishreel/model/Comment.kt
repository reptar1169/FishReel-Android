package com.reptar.fishreel.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot

/**
 * Mirrors the iOS app's `Comment` struct field-for-field, since both apps share the same
 * Firestore "posts/{postId}/comments" subcollection. Note there's no avatar/photo field --
 * the iOS app doesn't store one for comments.
 *
 * `parentCommentID` is null for a top-level comment, or another comment's id when this is a
 * reply -- replies live in the same "comments" collection as top-level comments (not a
 * separate subcollection), and are grouped into threads client-side. `likes` and `replyCount`
 * are denormalized counts kept in sync via FieldValue.increment, the same pattern as a post's
 * `likes`/`commentCount` -- see PostRepository.toggleCommentLike()/addComment().
 */
data class Comment(
    val id: String = "",
    val text: String = "",
    val userID: String = "",
    val username: String = "",
    val createdAt: Timestamp? = null,
    val likes: Int = 0,
    val replyCount: Int = 0,
    val parentCommentID: String? = null
) {
    val isReply: Boolean get() = parentCommentID != null

    companion object {
        fun fromDocument(document: DocumentSnapshot): Comment {
            val data = document.data ?: emptyMap<String, Any?>()
            return Comment(
                id = document.id,
                text = data["text"] as? String ?: "",
                userID = data["userID"] as? String ?: "",
                username = data["username"] as? String ?: "Unknown",
                createdAt = data["createdAt"] as? Timestamp,
                likes = (data["likes"] as? Number)?.toInt() ?: 0,
                replyCount = (data["replyCount"] as? Number)?.toInt() ?: 0,
                parentCommentID = data["parentCommentID"] as? String
            )
        }
    }
}
