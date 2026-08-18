package com.reptar.fishreel.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.reptar.fishreel.data.PostRepository
import com.reptar.fishreel.model.Post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * Email/password auth, mirroring the iOS app's AuthViewModel: register() sets a username via
 * the Firebase Auth displayName, and updateProfilePhoto() uploads to
 * profileImages/{uid}/photo.webp then backfills the new avatar onto the user's existing posts,
 * since posts snapshot the avatar URL at creation time.
 *
 * syncUserProfile() keeps this user's own top-level "users/{uid}" doc (username, avatar, real
 * account creation date) in sync on every auth state change, mirroring iOS's AuthViewModel --
 * that's the only way another person's join date is ever visible on their profile screen, since
 * Firebase Auth's account metadata is otherwise only readable for whoever's currently signed in.
 *
 * Account deletion goes a step further than iOS's current implementation: it reuses
 * PostRepository.deletePost() for each owned post, so comments and Storage media get cleaned
 * up too, not just the post documents. Worth porting that same thoroughness back to iOS.
 */
class AuthViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    private val _currentUser = MutableStateFlow(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _passwordResetMessage = MutableStateFlow<String?>(null)
    val passwordResetMessage: StateFlow<String?> = _passwordResetMessage

    private val _accountDeletionMessage = MutableStateFlow<String?>(null)
    val accountDeletionMessage: StateFlow<String?> = _accountDeletionMessage

    private val _needsReauthentication = MutableStateFlow(false)
    val needsReauthentication: StateFlow<Boolean> = _needsReauthentication

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        _currentUser.value = firebaseAuth.currentUser
        firebaseAuth.currentUser?.let { user ->
            viewModelScope.launch {
                syncUserProfile(user)
                saveFcmToken(user.uid)
            }
        }
    }

    init {
        auth.addAuthStateListener(authStateListener)
    }

    /**
     * Creates or refreshes this user's own "users/{uid}" profile doc -- both the first time
     * (creating it) and every time after (so a changed display name/avatar, or a previously
     * failed write, self-heals). `creationTimestamp` comes from Firebase Auth's own account
     * metadata, which is otherwise only ever readable for whoever's currently signed in --
     * copying it into a document anyone signed in can read is what makes someone's join date
     * visible on their profile screen at all. Best-effort: a failed sync just means the profile
     * screen falls back to not showing a join date yet, not worth blocking sign-in over.
     */
    private suspend fun syncUserProfile(user: FirebaseUser) {
        val username = user.displayName?.ifBlank { null } ?: user.email ?: "FishReel User"
        val avatar = user.photoUrl?.toString() ?: "person.circle"

        val data = hashMapOf<String, Any>(
            "username" to username,
            "avatar" to avatar
        )
        val creationTimestamp = user.metadata?.creationTimestamp ?: 0L
        if (creationTimestamp > 0) {
            data["createdAt"] = Timestamp(Date(creationTimestamp))
        }

        try {
            firestore.collection("users").document(user.uid).set(data, SetOptions.merge()).await()
        } catch (_: Exception) {
            // Best-effort; see the doc comment above.
        }
    }

    /**
     * Fetches this device's current FCM token and saves it to users/{uid}.fcmToken, mirroring
     * iOS's AppDelegate.didReceiveRegistrationToken. Called on every sign-in, not just when a
     * token is freshly generated -- FishReelMessagingService.onNewToken only fires when the
     * token actually changes, so if it was already generated before this sign-in (e.g. left
     * over from a previous account on this device), onNewToken would never fire and the token
     * would otherwise never get associated with this uid. Best-effort, same as syncUserProfile.
     */
    private suspend fun saveFcmToken(userId: String) {
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            firestore.collection("users").document(userId)
                .set(mapOf("fcmToken" to token), SetOptions.merge())
                .await()
        } catch (_: Exception) {
            // Best-effort; see the doc comment above.
        }
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authStateListener)
        super.onCleared()
    }

    fun signIn(email: String, password: String) {
        performAuthAction {
            auth.signInWithEmailAndPassword(email.trim(), password).await()
        }
    }

    fun register(email: String, password: String, username: String, photoBytes: ByteArray? = null) {
        performAuthAction {
            auth.createUserWithEmailAndPassword(email.trim(), password).await()
            val newUser = auth.currentUser
            val trimmedUsername = username.trim()

            // Upload the photo (if picked) before building the profile update, so displayName
            // and photoUri land in a single request instead of two round trips.
            var photoUrl: android.net.Uri? = null
            if (photoBytes != null && newUser != null) {
                val ref = storage.reference.child("profileImages/${newUser.uid}/photo.webp")
                val metadata = StorageMetadata.Builder().setContentType("image/webp").build()
                ref.putBytes(photoBytes, metadata).await()
                photoUrl = ref.downloadUrl.await()
            }

            if (trimmedUsername.isNotEmpty() || photoUrl != null) {
                val profileUpdate = UserProfileChangeRequest.Builder().apply {
                    if (trimmedUsername.isNotEmpty()) setDisplayName(trimmedUsername)
                    if (photoUrl != null) setPhotoUri(photoUrl)
                }.build()
                newUser?.updateProfile(profileUpdate)?.await()
            }
        }
    }

    fun sendPasswordReset(email: String) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isEmpty()) {
            _errorMessage.value = "Enter your email address first."
            return
        }
        viewModelScope.launch {
            _isWorking.value = true
            _errorMessage.value = null
            try {
                auth.sendPasswordResetEmail(trimmedEmail).await()
                _passwordResetMessage.value = "If an account exists for $trimmedEmail, " +
                    "we've sent a link to reset your password. Don't see it? Check your spam or junk folder."
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Couldn't send reset email"
            } finally {
                _isWorking.value = false
            }
        }
    }

    fun signOut() {
        // Clear this device's FCM token from the outgoing user's doc before signing out, so a
        // later sign-in by a different account on this device doesn't send that account's
        // notifications to the account that just left. Fire-and-forget, same as iOS's
        // PushNotificationManager.clearToken -- not worth blocking sign-out on.
        auth.currentUser?.uid?.let { userId ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    firestore.collection("users").document(userId)
                        .update("fcmToken", FieldValue.delete())
                        .await()
                } catch (_: Exception) {
                    // Best-effort; see the comment above.
                }
            }
        }
        try {
            auth.signOut()
            _errorMessage.value = null
        } catch (e: Exception) {
            _errorMessage.value = e.message ?: "Couldn't sign out"
        }
    }

    fun updateProfilePhoto(imageBytes: ByteArray) {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            _isWorking.value = true
            _errorMessage.value = null
            try {
                val ref = storage.reference.child("profileImages/${user.uid}/photo.webp")
                val metadata = StorageMetadata.Builder().setContentType("image/webp").build()
                ref.putBytes(imageBytes, metadata).await()
                val downloadUrl = ref.downloadUrl.await()

                val profileUpdate = UserProfileChangeRequest.Builder()
                    .setPhotoUri(downloadUrl)
                    .build()
                user.updateProfile(profileUpdate).await()
                _currentUser.value = auth.currentUser

                updateAvatarOnExistingPosts(user.uid, downloadUrl.toString())
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Couldn't update photo"
            } finally {
                _isWorking.value = false
            }
        }
    }

    /** Permanently deletes the signed-in user's account and all their data. */
    fun deleteAccount() {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            performDeletion(user)
        }
    }

    /**
     * Re-authenticates with the given password (required by Firebase after an extended
     * session) and retries account deletion.
     */
    fun reauthenticateAndDelete(password: String) {
        val user = auth.currentUser
        val email = user?.email
        if (user == null || email == null) {
            _errorMessage.value =
                "Unable to verify your identity. Please sign out and back in, then try deleting your account again."
            return
        }
        viewModelScope.launch {
            _isWorking.value = true
            _errorMessage.value = null
            try {
                val credential = EmailAuthProvider.getCredential(email, password)
                user.reauthenticate(credential).await()
                _needsReauthentication.value = false
                performDeletion(user)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Couldn't verify your password"
                _isWorking.value = false
            }
        }
    }

    private class DeletionStepError(val step: String, val underlying: Exception) : Exception(underlying)

    private suspend fun performDeletion(user: FirebaseUser) {
        _isWorking.value = true
        _errorMessage.value = null
        try {
            try {
                deleteCommentsAuthoredBy(user.uid)
            } catch (e: Exception) {
                throw DeletionStepError("deleting comments", e)
            }
            try {
                deletePostsOwnedBy(user.uid)
            } catch (e: Exception) {
                throw DeletionStepError("deleting posts", e)
            }
            try {
                deleteLikesAuthoredBy(user.uid)
            } catch (e: Exception) {
                throw DeletionStepError("deleting likes", e)
            }
            try {
                deleteCommentLikesAuthoredBy(user.uid)
            } catch (e: Exception) {
                throw DeletionStepError("deleting comment likes", e)
            }
            try {
                deleteFollowRelationships(user.uid)
            } catch (e: Exception) {
                throw DeletionStepError("deleting follow relationships", e)
            }
            try {
                deleteProfilePhotoFile(user.uid)
            } catch (e: Exception) {
                throw DeletionStepError("deleting your profile photo", e)
            }
            try {
                deleteUserProfile(user.uid)
            } catch (e: Exception) {
                throw DeletionStepError("deleting your profile", e)
            }
            try {
                user.delete().await()
            } catch (e: Exception) {
                throw DeletionStepError("deleting the account", e)
            }
            // Only clear the signed-in state once deletion has actually succeeded, so a
            // failure leaves the user on the Profile screen with a visible error instead
            // of silently bouncing them out.
            _currentUser.value = null
            _accountDeletionMessage.value = "Your account has been successfully deleted."
        } catch (e: Exception) {
            val underlying = (e as? DeletionStepError)?.underlying ?: e
            if (underlying is FirebaseAuthRecentLoginRequiredException) {
                _needsReauthentication.value = true
                _errorMessage.value = "For your security, please re-enter your password to confirm account deletion."
            } else if (e is DeletionStepError) {
                // Surface which step failed -- "Missing or insufficient permissions" alone
                // doesn't say whether it choked on comments, posts, follows, or the account
                // itself, and that distinction is exactly what's needed to track down a
                // rules mismatch.
                _errorMessage.value = "Failed while ${e.step}: ${underlying.message ?: "unknown error"}"
            } else {
                _errorMessage.value = underlying.message ?: "Something went wrong"
            }
        }
        _isWorking.value = false
    }

    private suspend fun deleteCommentsAuthoredBy(userId: String) {
        val snapshot = firestore.collectionGroup("comments")
            .whereEqualTo("userID", userId)
            .get()
            .await()
        if (snapshot.isEmpty) return
        val batch = firestore.batch()
        snapshot.documents.forEach { batch.delete(it.reference) }
        batch.commit().await()
    }

    /**
     * Deletes every post this user owns via PostRepository.deletePost(), which also cleans up
     * each post's comments subcollection and Storage image/video -- not just the post document.
     */
    private suspend fun deletePostsOwnedBy(userId: String) {
        val snapshot = firestore.collection("posts")
            .whereEqualTo("userID", userId)
            .get()
            .await()
        snapshot.documents.forEach { document ->
            PostRepository.deletePost(Post.fromDocument(document))
        }
    }

    /**
     * Removes this user's own likes on *other* people's posts. Likes on posts this user owns
     * are already cleaned up as part of deletePostsOwnedBy() -> PostRepository.deletePost().
     */
    private suspend fun deleteLikesAuthoredBy(userId: String) {
        val snapshot = firestore.collection("postLikes")
            .whereEqualTo("userID", userId)
            .get()
            .await()
        if (snapshot.isEmpty) return
        val batch = firestore.batch()
        snapshot.documents.forEach { batch.delete(it.reference) }
        batch.commit().await()
    }

    /**
     * Mirrors deleteLikesAuthoredBy() above, but for the deleted user's own likes on comments
     * (not posts). Note: this doesn't clean up likes *other* people left on comments this user
     * wrote -- the same preexisting gap deleteLikesAuthoredBy/deletePostsOwnedBy already has
     * for post likes left by others, not something new introduced here.
     */
    private suspend fun deleteCommentLikesAuthoredBy(userId: String) {
        val snapshot = firestore.collection("commentLikes")
            .whereEqualTo("userID", userId)
            .get()
            .await()
        if (snapshot.isEmpty) return
        val batch = firestore.batch()
        snapshot.documents.forEach { batch.delete(it.reference) }
        batch.commit().await()
    }

    /** Removes follow relationships on both sides, so no orphaned rows point at a deleted user. */
    private suspend fun deleteFollowRelationships(userId: String) {
        val follows = firestore.collection("follows")
        val followerSnapshot = follows.whereEqualTo("followerID", userId).get().await()
        val followeeSnapshot = follows.whereEqualTo("followeeID", userId).get().await()
        val allDocuments = followerSnapshot.documents + followeeSnapshot.documents
        if (allDocuments.isEmpty()) return
        val batch = firestore.batch()
        allDocuments.forEach { batch.delete(it.reference) }
        batch.commit().await()
    }

    private suspend fun deleteProfilePhotoFile(userId: String) {
        // Profile photos moved from photo.jpg to photo.webp -- try both so accounts whose photo
        // predates that switch (and were never re-uploaded since) still get cleaned up.
        for (fileName in listOf("photo.webp", "photo.jpg")) {
            try {
                storage.reference.child("profileImages/$userId/$fileName").delete().await()
            } catch (_: Exception) {
                // No photo at this path, or it's already gone; not fatal.
            }
        }
    }

    private suspend fun deleteUserProfile(userId: String) {
        firestore.collection("users").document(userId).delete().await()
    }

    private suspend fun updateAvatarOnExistingPosts(userId: String, avatarUrl: String) {
        val snapshot = firestore.collection("posts")
            .whereEqualTo("userID", userId)
            .get()
            .await()
        if (snapshot.isEmpty) return
        val batch = firestore.batch()
        snapshot.documents.forEach { document ->
            batch.update(document.reference, "userAvatar", avatarUrl)
        }
        batch.commit().await()
    }

    private fun performAuthAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            _isWorking.value = true
            _errorMessage.value = null
            try {
                action()
                _currentUser.value = auth.currentUser
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Something went wrong"
            } finally {
                _isWorking.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearPasswordResetMessage() {
        _passwordResetMessage.value = null
    }

    fun clearAccountDeletionMessage() {
        _accountDeletionMessage.value = null
    }

    fun clearNeedsReauthentication() {
        _needsReauthentication.value = false
    }
}
