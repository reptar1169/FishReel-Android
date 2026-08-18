package com.reptar.fishreel.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot

/**
 * A single user's like on a post. Stored in the shared top-level "postLikes" collection
 * (document id "{postID}_{userID}", mirroring the "follows" collection's id scheme) rather
 * than as a boolean on the post document itself -- a document-level flag meant one person's
 * tap flipped the heart for every viewer, and there was no way to attribute a like to anyone
 * or list who'd liked a post. Do not rename these fields without updating the iOS app too.
 */
data class Liker(
    val id: String = "",
    val postID: String = "",
    val userID: String = "",
    val username: String = "",
    val userAvatar: String = "",
    val createdAt: Timestamp? = null
) {
    /** iOS falls back to the SF Symbol name "person.circle" when there's no real avatar URL. */
    val hasPhotoAvatar: Boolean get() = userAvatar.startsWith("http")

    companion object {
        fun fromDocument(document: DocumentSnapshot): Liker {
            val data = document.data ?: emptyMap<String, Any?>()
            return Liker(
                id = document.id,
                postID = data["postID"] as? String ?: "",
                userID = data["userID"] as? String ?: "",
                username = data["username"] as? String ?: "Unknown",
                userAvatar = data["userAvatar"] as? String ?: "person.circle",
                createdAt = data["createdAt"] as? Timestamp
            )
        }
    }
}
