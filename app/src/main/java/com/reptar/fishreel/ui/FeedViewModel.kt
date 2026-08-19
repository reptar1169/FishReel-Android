package com.reptar.fishreel.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.reptar.fishreel.data.ImageCompressor
import com.reptar.fishreel.data.LinkMetadata
import com.reptar.fishreel.data.LinkMetadataFetcher
import com.reptar.fishreel.data.PostRepository
import com.reptar.fishreel.data.VideoThumbnailExtractor
import com.reptar.fishreel.model.Post
import com.reptar.fishreel.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FeedViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // Matches storage.rules' isVideoUpload() cap -- keep in sync.
        private const val MAX_VIDEO_BYTES = 100L * 1024 * 1024

        /**
         * Firebase Auth UID of the "FishReel Reports" bot account (see functions/.env's
         * FISH_COUNTS_BOT_UID). Its posts are excluded from the main feed/Hooked and shown
         * instead in a dedicated Reports tab -- keep this in sync with iOS's
         * FeedViewModel.fishCountsBotUserID.
         */
        const val FISH_COUNTS_BOT_UID = "xUqHjgVtNqQkrP4caneknR4wm1k1"
    }

    private val _posts = MutableStateFlow<List<Post>>(emptyList())
    val posts: StateFlow<List<Post>> = _posts

    /** Live feed of just the "FishReel Reports" bot's posts, for the dedicated Reports tab. */
    private val _reportPosts = MutableStateFlow<List<Post>>(emptyList())
    val reportPosts: StateFlow<List<Post>> = _reportPosts

    private val _isLoadingFeed = MutableStateFlow(true)
    val isLoadingFeed: StateFlow<Boolean> = _isLoadingFeed

    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _followingIds = MutableStateFlow<Set<String>>(emptySet())
    val followingIds: StateFlow<Set<String>> = _followingIds

    // Post IDs the current user has liked, driving each post's heart state in the feed.
    private val _likedPostIds = MutableStateFlow<Set<String>>(emptySet())
    val likedPostIds: StateFlow<Set<String>> = _likedPostIds

    // Set when a post's username is tapped, to repopulate the main feed with just that
    // user's posts. Cleared when switching tabs or explicitly dismissed.
    private val _selectedUserId = MutableStateFlow<String?>(null)
    val selectedUserId: StateFlow<String?> = _selectedUserId

    private val _selectedUsername = MutableStateFlow<String?>(null)
    val selectedUsername: StateFlow<String?> = _selectedUsername

    private val _selectedUserAvatar = MutableStateFlow<String?>(null)
    val selectedUserAvatar: StateFlow<String?> = _selectedUserAvatar

    val currentUserId: String?
        get() = FirebaseAuth.getInstance().currentUser?.uid

    /** Posts from users the current person follows, derived client-side like iOS's hookedPosts. */
    val hookedPosts: StateFlow<List<Post>> = combine(_posts, _followingIds) { posts, followingIds ->
        posts.filter { post -> post.userID != null && followingIds.contains(post.userID) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The main feed narrowed down to a single user's posts, once one's been tapped into. */
    val filteredPosts: StateFlow<List<Post>> = combine(_posts, _selectedUserId) { posts, userId ->
        if (userId == null) posts else posts.filter { it.userID == userId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // This ViewModel is retained in the Activity's ViewModelStore across a sign-out/sign-in
    // cycle -- switching accounts doesn't tear it down and re-run init{}. Without this listener,
    // followingIds/likedPostIds would stay whatever they were for the *previous* account forever,
    // since they were only ever subscribed once against the uid captured at construction time.
    // That's exactly what caused likes toggled after switching accounts to briefly show the
    // wrong state and then fail with a permissions error: the old account's likedPostIds made a
    // post look already-liked, so toggleLike() tried to delete a postLikes doc that belonged to
    // the old account, not this one.
    private var followingJob: Job? = null
    private var likedPostsJob: Job? = null
    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        followingJob?.cancel()
        likedPostsJob?.cancel()
        val uid = firebaseAuth.currentUser?.uid
        if (uid == null) {
            _followingIds.value = emptySet()
            _likedPostIds.value = emptySet()
            return@AuthStateListener
        }
        // Clear immediately rather than waiting on the new listeners' first snapshot, so a
        // moment after switching accounts never shows the previous account's like/follow state.
        _followingIds.value = emptySet()
        _likedPostIds.value = emptySet()
        followingJob = viewModelScope.launch {
            PostRepository.followingIdsFlow(uid)
                .catch { /* Non-critical: hooked feed just stays empty if this fails. */ }
                .collect { _followingIds.value = it }
        }
        likedPostsJob = viewModelScope.launch {
            PostRepository.likedPostIdsFlow(uid)
                .catch { /* Non-critical: hearts just stay unfilled if this fails. */ }
                .collect { _likedPostIds.value = it }
        }
    }

    init {
        viewModelScope.launch {
            PostRepository.postsFlow()
                .catch { e ->
                    _errorMessage.value = "Couldn't load feed: ${e.message ?: "unknown error"}"
                    _isLoadingFeed.value = false
                }
                .collect { list ->
                    // FishReel Reports' bot posts get their own dedicated Reports tab (see
                    // reportPosts below) instead of cluttering the main Feed/Hooked --
                    // excluding them here cascades automatically to hookedPosts/filteredPosts
                    // since both are derived client-side from this same flow.
                    _posts.value = list.filter { it.userID != FISH_COUNTS_BOT_UID }
                    _isLoadingFeed.value = false
                }
        }

        viewModelScope.launch {
            PostRepository.postsByUserFlow(FISH_COUNTS_BOT_UID)
                .catch { /* Non-critical: Reports tab just stays empty if this fails. */ }
                .collect { _reportPosts.value = it }
        }

        // Fires immediately with the current auth state, so this also covers the normal
        // first-launch case -- no separate one-time subscription needed alongside it.
        FirebaseAuth.getInstance().addAuthStateListener(authStateListener)
    }

    override fun onCleared() {
        super.onCleared()
        FirebaseAuth.getInstance().removeAuthStateListener(authStateListener)
    }

    /** A text-only post - no photo, video, or link, just a caption. */
    fun uploadTextPost(caption: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            _uploading.value = true
            try {
                PostRepository.uploadTextPost(caption)
                onComplete()
            } catch (e: Exception) {
                _errorMessage.value = "Upload failed: ${e.message ?: "unknown error"}"
            } finally {
                _uploading.value = false
            }
        }
    }

    fun uploadPhotoPost(uri: Uri, caption: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            _uploading.value = true
            try {
                val bytes = withContext(Dispatchers.IO) {
                    ImageCompressor.compress(getApplication(), uri)
                }
                PostRepository.uploadPhotoPost(bytes, caption)
                onComplete()
            } catch (e: Exception) {
                _errorMessage.value = "Upload failed: ${e.message ?: "unknown error"}"
            } finally {
                _uploading.value = false
            }
        }
    }

    /**
     * Reads the picked video's raw bytes and mime type, rejecting anything over the shared
     * Storage rules' 100MB cap client-side so the user gets an immediate, clear error instead
     * of a slow upload that fails at the very end.
     */
    fun uploadVideoPost(uri: Uri, caption: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            _uploading.value = true
            try {
                val context = getApplication<Application>()
                val mimeType = context.contentResolver.getType(uri) ?: "video/mp4"
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Couldn't read that video")
                }
                if (bytes.size > MAX_VIDEO_BYTES) {
                    error("That video is too large (max 100MB)")
                }
                // Best-effort -- a failed/null extraction just means this post falls back to
                // VideoPlayer's no-thumbnail display, not a blocked upload.
                val thumbnailBytes = withContext(Dispatchers.IO) {
                    VideoThumbnailExtractor.extractThumbnail(context, uri)
                }
                PostRepository.uploadVideoPost(bytes, caption, mimeType, thumbnailBytes)
                onComplete()
            } catch (e: Exception) {
                _errorMessage.value = "Upload failed: ${e.message ?: "unknown error"}"
            } finally {
                _uploading.value = false
            }
        }
    }

    fun uploadLinkPost(url: String, caption: String, metadata: LinkMetadata?, onComplete: () -> Unit) {
        viewModelScope.launch {
            _uploading.value = true
            try {
                PostRepository.uploadLinkPost(url, caption, metadata)
                onComplete()
            } catch (e: Exception) {
                _errorMessage.value = "Upload failed: ${e.message ?: "unknown error"}"
            } finally {
                _uploading.value = false
            }
        }
    }

    /** Fetches a live preview card while the user is composing a link post. Best-effort. */
    suspend fun fetchLinkPreview(url: String): LinkMetadata? = withContext(Dispatchers.IO) {
        LinkMetadataFetcher.fetch(url)
    }

    /** Thin wrappers around PostRepository for UserProfileScreen -- see there for usage. */
    suspend fun fetchUserProfile(userId: String): UserProfile? = PostRepository.fetchUserProfile(userId)

    suspend fun fetchFollowers(userId: String): List<UserProfile> = PostRepository.fetchFollowers(userId)

    fun updatePost(postId: String, caption: String, oldImageUrl: String, newImageUri: Uri?, onComplete: () -> Unit) {
        viewModelScope.launch {
            _uploading.value = true
            try {
                val bytes = newImageUri?.let {
                    withContext(Dispatchers.IO) { ImageCompressor.compress(getApplication(), it) }
                }
                val trimmedCaption = caption.trim()
                val newImageUrl = PostRepository.updatePost(postId, caption, oldImageUrl, bytes)

                // Patch the in-memory list immediately rather than waiting on the Firestore
                // listener's round trip, so the feed reflects the edit as soon as this screen
                // is popped instead of only after the next cold start.
                _posts.value = _posts.value.map { existing ->
                    if (existing.id == postId) {
                        existing.copy(
                            caption = trimmedCaption,
                            postImage = newImageUrl ?: existing.postImage
                        )
                    } else {
                        existing
                    }
                }
                onComplete()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't save changes: ${e.message ?: "unknown error"}"
            } finally {
                _uploading.value = false
            }
        }
    }

    fun toggleLike(post: Post) {
        val isCurrentlyLiked = _likedPostIds.value.contains(post.id)
        val delta = if (isCurrentlyLiked) -1 else 1

        // Optimistic: flip the heart and adjust the count immediately rather than waiting on
        // Firestore's round trip, then roll both back if the write actually fails.
        _likedPostIds.value = if (isCurrentlyLiked) {
            _likedPostIds.value - post.id
        } else {
            _likedPostIds.value + post.id
        }
        _posts.value = _posts.value.map { existing ->
            if (existing.id == post.id) existing.copy(likes = (existing.likes + delta).coerceAtLeast(0)) else existing
        }

        viewModelScope.launch {
            try {
                PostRepository.toggleLike(post.id, isCurrentlyLiked)
            } catch (e: Exception) {
                _likedPostIds.value = if (isCurrentlyLiked) {
                    _likedPostIds.value + post.id
                } else {
                    _likedPostIds.value - post.id
                }
                _posts.value = _posts.value.map { existing ->
                    if (existing.id == post.id) existing.copy(likes = (existing.likes - delta).coerceAtLeast(0)) else existing
                }
                _errorMessage.value = "Couldn't update like: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun toggleFollow(post: Post) {
        val uid = currentUserId ?: return
        val followeeId = post.userID ?: return
        if (followeeId == uid) return
        val isFollowing = _followingIds.value.contains(followeeId)
        viewModelScope.launch {
            try {
                PostRepository.toggleFollow(uid, followeeId, isFollowing)
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't update follow: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun deletePost(post: Post) {
        viewModelScope.launch {
            try {
                PostRepository.deletePost(post)
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't delete post: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /** Narrows the main feed down to just this user's posts, e.g. after tapping their name. */
    fun filterByUser(userId: String, username: String, avatarUrl: String) {
        _selectedUserId.value = userId
        _selectedUsername.value = username
        _selectedUserAvatar.value = avatarUrl
    }

    fun clearUserFilter() {
        _selectedUserId.value = null
        _selectedUsername.value = null
        _selectedUserAvatar.value = null
    }
}
