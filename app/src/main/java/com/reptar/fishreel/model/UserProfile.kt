package com.reptar.fishreel.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot

/**
 * A user's public profile summary, stored in the shared top-level "users/{uid}" collection.
 * iOS's AuthViewModel keeps this in sync on every sign-in -- Firebase Auth's account creation
 * date can only ever be read for whoever's currently signed in, so copying it into a document
 * anyone signed in can read is what makes someone's join date visible on their own profile
 * screen to other users at all. Android's AuthViewModel does the same on every auth state
 * change. Do not rename these fields without updating the iOS app too -- note that unlike every
 * other collection in this shared schema, the avatar field here is named "avatar", not
 * "userAvatar".
 */
data class UserProfile(
    val id: String = "",
    val username: String = "",
    val avatar: String = "",
    val createdAt: Timestamp? = null
) {
    /** iOS falls back to the SF Symbol name "person.circle" when there's no real avatar URL. */
    val hasPhotoAvatar: Boolean get() = avatar.startsWith("http")

    companion object {
        fun fromDocument(document: DocumentSnapshot): UserProfile? {
            if (!document.exists()) return null
            val data = document.data ?: return null
            return UserProfile(
                id = document.id,
                username = data["username"] as? String ?: "Unknown",
                avatar = data["avatar"] as? String ?: "person.circle",
                createdAt = data["createdAt"] as? Timestamp
            )
        }
    }
}
