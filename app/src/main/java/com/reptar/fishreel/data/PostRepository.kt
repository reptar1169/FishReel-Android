package com.reptar.fishreel.data

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.reptar.fishreel.model.Comment
import com.reptar.fishreel.model.Liker
import com.reptar.fishreel.model.Post
import com.reptar.fishreel.model.UserProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Single source of truth for all Firestore/Storage reads and writes used by the app.
 *
 * IMPORTANT: this app shares its Firestore/Storage backend with a companion iOS app.
 * Field names and write shapes here must stay in lockstep with the iOS app's
 * FeedViewModel/CommentsViewModel -- do not rename or restructure without checking there too.
 *
 * A post document is exactly one of photo/video/link: postImage, postVideo, or postURL is
 * set (the other two stay ""). Link posts also carry a one-time snapshot of the URL's Open
 * Graph metadata: linkTitle, linkDescription, linkImageURL, linkDomain.
 */
object PostRepository {
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private const val POSTS_COLLECTION = "posts"
    private const val COMMENTS_COLLECTION = "comments"
    private const val FOLLOWS_COLLECTION = "follows"
    private const val POST_LIKES_COLLECTION = "postLikes"
    private const val COMMENT_LIKES_COLLECTION = "commentLikes"
    private const val USERS_COLLECTION = "users"

    /**
     * Firebase Auth's currentUser is a local per-device cache of profile fields
     * (displayName, photoUrl). If the profile was updated from a different device or app
     * (e.g. a photo set on Android), this client won't see it until an explicit reload --
     * without this, posts/comments created here can bake in a stale cached avatar/name.
     * Best-effort: if the reload fails (offline, etc.) we fall back to whatever's cached
     * rather than blocking the post/comment entirely.
     */
    private suspend fun refreshedUser(): FirebaseUser? {
        val user = auth.currentUser ?: return null
        try {
            user.reload().await()
        } catch (_: Exception) {
            // Non-fatal; proceed with the possibly-stale cached profile below.
        }
        return auth.currentUser
    }

    private fun displayName(user: FirebaseUser?): String {
        val name = user?.displayName
        return if (!name.isNullOrBlank()) name else user?.email ?: "FishReel User"
    }

    /** Live, real-time stream of all posts, newest first. */
    fun postsFlow(): Flow<List<Post>> = callbackFlow {
        val registration = firestore.collection(POSTS_COLLECTION)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.map(Post::fromDocument) ?: emptyList())
            }
        awaitClose { registration.remove() }
    }

    /**
     * Live, real-time stream of just one account's posts, newest first -- used for the
     * dedicated Reports tab (FishReel's automated fish-count bot). Requires the
     * posts(userID ASC, createdAt DESC) composite index in firestore.indexes.json. Matches
     * iOS's FeedViewModel.listenForReportPosts().
     */
    fun postsByUserFlow(userId: String): Flow<List<Post>> = callbackFlow {
        val registration = firestore.collection(POSTS_COLLECTION)
            .whereEqualTo("userID", userId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.map(Post::fromDocument) ?: emptyList())
            }
        awaitClose { registration.remove() }
    }

    /** Uploads an already-compressed image and creates the Firestore post document. */
    suspend fun uploadPhotoPost(imageBytes: ByteArray, caption: String) {
        val user = refreshedUser() ?: error("You must be signed in to post")

        // Must match the iOS app's CreatePostView upload path (postImages/{uid}/{fileName})
        // and set an explicit image content type -- the shared Storage rules require both.
        // ImageCompressor now encodes as WebP (smaller than JPEG at the same quality); iOS 14+'s
        // ImageIO decodes it natively, so this is safe for both apps to read.
        val fileName = "${UUID.randomUUID()}.webp"
        val ref = storage.reference.child("postImages/${user.uid}/$fileName")
        val metadata = StorageMetadata.Builder()
            .setContentType("image/webp")
            .build()
        ref.putBytes(imageBytes, metadata).await()
        val downloadUrl = ref.downloadUrl.await().toString()

        createPostDocument(user, postImage = downloadUrl, caption = caption)
    }

    /**
     * Uploads a video file (and, if provided, a poster-frame thumbnail -- see
     * VideoThumbnailExtractor) and creates the Firestore post document. Matches iOS's
     * CreatePostView upload path (postVideos/{uid}/{fileName}) -- the shared Storage rules
     * require both that path and an explicit video content type (e.g. video/mp4), capped at
     * 100MB. The thumbnail, when present, uploads to postVideoThumbnails/{uid}/{fileName} as a
     * regular image; a failed thumbnail upload is non-fatal, it just means this post falls back
     * to VideoPlayer's no-thumbnail display.
     */
    suspend fun uploadVideoPost(
        videoBytes: ByteArray,
        caption: String,
        mimeType: String = "video/mp4",
        thumbnailBytes: ByteArray? = null
    ) {
        val user = refreshedUser() ?: error("You must be signed in to post")

        val extension = if (mimeType.contains("quicktime")) "mov" else "mp4"
        val fileName = "${UUID.randomUUID()}.$extension"
        val ref = storage.reference.child("postVideos/${user.uid}/$fileName")
        val metadata = StorageMetadata.Builder()
            .setContentType(mimeType)
            .build()
        ref.putBytes(videoBytes, metadata).await()
        val downloadUrl = ref.downloadUrl.await().toString()

        val thumbnailUrl = if (thumbnailBytes != null) {
            try {
                val thumbFileName = "${UUID.randomUUID()}.webp"
                val thumbRef = storage.reference.child("postVideoThumbnails/${user.uid}/$thumbFileName")
                val thumbMetadata = StorageMetadata.Builder().setContentType("image/webp").build()
                thumbRef.putBytes(thumbnailBytes, thumbMetadata).await()
                thumbRef.downloadUrl.await().toString()
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }

        createPostDocument(user, postVideo = downloadUrl, postVideoThumbnail = thumbnailUrl, caption = caption)
    }

    /**
     * Creates a link post: no Storage upload, just the URL plus a one-time snapshot of its
     * Open Graph metadata (title/description/image/domain), fetched by the caller beforehand
     * via LinkMetadataFetcher. `metadata` may be null if the fetch failed -- the post still
     * goes out with just the bare URL and no preview card.
     */
    suspend fun uploadLinkPost(url: String, caption: String, metadata: LinkMetadata?) {
        val user = refreshedUser() ?: error("You must be signed in to post")
        createPostDocument(
            user,
            postURL = url.trim(),
            linkTitle = metadata?.title.orEmpty(),
            linkDescription = metadata?.description.orEmpty(),
            linkImageURL = metadata?.imageURL.orEmpty(),
            linkDomain = metadata?.domain.orEmpty(),
            caption = caption
        )
    }

    /**
     * Shared post-document builder for all three post types (photo/video/link) -- exactly one
     * of postImage/postVideo/postURL should be non-blank per call. Keeping this in one place
     * avoids the username/userAvatar/timestamp logic drifting between the three upload paths.
     */
    private suspend fun createPostDocument(
        user: FirebaseUser,
        postImage: String = "",
        postVideo: String = "",
        postVideoThumbnail: String = "",
        postURL: String = "",
        linkTitle: String = "",
        linkDescription: String = "",
        linkImageURL: String = "",
        linkDomain: String = "",
        caption: String = ""
    ) {
        val postData = hashMapOf(
            "username" to displayName(user),
            "userID" to user.uid,
            "userAvatar" to (user.photoUrl?.toString() ?: "person.circle"),
            "postImage" to postImage,
            "postVideo" to postVideo,
            "postVideoThumbnail" to postVideoThumbnail,
            "postURL" to postURL,
            "linkTitle" to linkTitle,
            "linkDescription" to linkDescription,
            "linkImageURL" to linkImageURL,
            "linkDomain" to linkDomain,
            "caption" to caption.trim(),
            "likes" to 0,
            "commentCount" to 0,
            // A client timestamp, not FieldValue.serverTimestamp(): the latter leaves this
            // field "pending" (null) in the writer's own local cache until the server round
            // trips it back, and Firestore excludes pending-timestamp docs from orderBy(createdAt)
            // results in the meantime -- so your own new post would vanish from your own feed
            // until that resolves. A client timestamp has no pending state, so it shows up
            // immediately. Cross-app compatible: iOS just reads whatever Timestamp is here.
            "createdAt" to Timestamp.now()
        )
        firestore.collection(POSTS_COLLECTION).add(postData).await()
    }

    /**
     * Updates a post's caption and, if a new image was picked, its photo. Editing is
     * photo-posts-only for now (video/link posts can still have their caption edited, since
     * this never touches postImage/postVideo/postURL unless newImageBytes is non-null). Only
     * touches the fields actually changing -- unlike iOS's updatePost() (which always rewrites
     * postVideo, defaulting to "" if not explicitly passed), this never touches postVideo at
     * all, so it can't accidentally wipe a video reference on a post edited from Android.
     */
    /** Returns the new photo's download URL if one was uploaded, or null if only the caption changed. */
    suspend fun updatePost(postId: String, caption: String, oldImageUrl: String, newImageBytes: ByteArray?): String? {
        val user = auth.currentUser ?: error("You must be signed in to edit a post")

        val updates = mutableMapOf<String, Any>("caption" to caption.trim())
        var newImageUrl: String? = null

        if (newImageBytes != null) {
            val fileName = "${UUID.randomUUID()}.webp"
            val ref = storage.reference.child("postImages/${user.uid}/$fileName")
            val metadata = StorageMetadata.Builder().setContentType("image/webp").build()
            ref.putBytes(newImageBytes, metadata).await()
            newImageUrl = ref.downloadUrl.await().toString()
            updates["postImage"] = newImageUrl
        }

        firestore.collection(POSTS_COLLECTION).document(postId).update(updates).await()

        if (newImageUrl != null && oldImageUrl.startsWith("http")) {
            try {
                storage.getReferenceFromUrl(oldImageUrl).delete().await()
            } catch (_: Exception) {
                // Best-effort cleanup of the replaced photo; not fatal.
            }
        }

        return newImageUrl
    }

    /**
     * Live set of post IDs the given user has liked, mirroring followingIdsFlow's shape --
     * lets the feed show each post's correct heart state for the *current* viewer with a
     * single listener, instead of one query per visible post.
     */
    fun likedPostIdsFlow(currentUserId: String): Flow<Set<String>> = callbackFlow {
        val registration = firestore.collection(POST_LIKES_COLLECTION)
            .whereEqualTo("userID", currentUserId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val ids = snapshot?.documents
                    ?.mapNotNull { it.getString("postID") }
                    ?.toSet()
                    ?: emptySet()
                trySend(ids)
            }
        awaitClose { registration.remove() }
    }

    /** Live, real-time list of everyone who has liked a post, newest first -- powers the "Liked by" screen. */
    fun likersFlow(postId: String): Flow<List<Liker>> = callbackFlow {
        val registration = firestore.collection(POST_LIKES_COLLECTION)
            .whereEqualTo("postID", postId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.map(Liker::fromDocument) ?: emptyList())
            }
        awaitClose { registration.remove() }
    }

    /**
     * Likes or unlikes a post on behalf of the current user. Tracked as its own document in
     * the shared top-level "postLikes" collection (id "{postID}_{userID}", mirroring "follows")
     * rather than a single boolean on the post -- a document-level flag meant one person's tap
     * flipped the heart for every viewer. Also keeps the post's denormalized `likes` counter in
     * sync so the feed can show a count without reading the whole postLikes collection.
     */
    suspend fun toggleLike(postId: String, isCurrentlyLiked: Boolean) {
        val user = refreshedUser() ?: error("You must be signed in to like a post")
        val likeRef = firestore.collection(POST_LIKES_COLLECTION).document("${postId}_${user.uid}")
        val postRef = firestore.collection(POSTS_COLLECTION).document(postId)

        if (isCurrentlyLiked) {
            likeRef.delete().await()
            postRef.update("likes", FieldValue.increment(-1)).await()
        } else {
            val likeData = hashMapOf(
                "postID" to postId,
                "userID" to user.uid,
                "username" to displayName(user),
                "userAvatar" to (user.photoUrl?.toString() ?: "person.circle"),
                "createdAt" to Timestamp.now()
            )
            likeRef.set(likeData).await()
            postRef.update("likes", FieldValue.increment(1)).await()
        }
    }

    /** Deletes a post's Firestore document, its comments, its likes, and its stored media (best-effort). */
    suspend fun deletePost(post: Post) {
        val postRef = firestore.collection(POSTS_COLLECTION).document(post.id)

        try {
            val commentDocs = postRef.collection(COMMENTS_COLLECTION).get().await()
            if (!commentDocs.isEmpty) {
                val batch = firestore.batch()
                commentDocs.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        } catch (_: Exception) {
            // Best-effort cleanup; the post delete below still proceeds.
        }

        try {
            val likeDocs = firestore.collection(POST_LIKES_COLLECTION)
                .whereEqualTo("postID", post.id)
                .get()
                .await()
            if (!likeDocs.isEmpty) {
                val batch = firestore.batch()
                likeDocs.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        } catch (_: Exception) {
            // Best-effort cleanup; the post delete below still proceeds.
        }

        try {
            // Cleans up likes left on any of this post's comments (by anyone, not just the
            // post's owner) -- stored keyed by postID precisely so this cleanup can query
            // them all in one shot instead of walking every comment individually.
            val commentLikeDocs = firestore.collection(COMMENT_LIKES_COLLECTION)
                .whereEqualTo("postID", post.id)
                .get()
                .await()
            if (!commentLikeDocs.isEmpty) {
                val batch = firestore.batch()
                commentLikeDocs.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        } catch (_: Exception) {
            // Best-effort cleanup; the post delete below still proceeds.
        }

        postRef.delete().await()

        listOf(post.postImage, post.postVideo, post.postVideoThumbnail).filter { it.startsWith("http") }.forEach { url ->
            try {
                storage.getReferenceFromUrl(url).delete().await()
            } catch (_: Exception) {
                // Media may already be gone or URL malformed; not fatal.
            }
        }
    }

    /** Live, real-time stream of comments for a post, oldest first. */
    fun commentsFlow(postId: String): Flow<List<Comment>> = callbackFlow {
        val registration = firestore.collection(POSTS_COLLECTION).document(postId)
            .collection(COMMENTS_COLLECTION)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val comments = snapshot?.documents
                    ?.map(Comment::fromDocument)
                    ?.filter { it.text.isNotBlank() }
                    ?: emptyList()
                trySend(comments)
            }
        awaitClose { registration.remove() }
    }

    /**
     * Adds a comment (or, if [parentCommentId] is set, a reply to that comment -- always a
     * top-level comment, since replies only nest one level deep), then bumps the parent
     * comment's replyCount (if this is a reply) and the post's overall comment count. Matches
     * iOS's CommentsViewModel.sendComment(), just as sequential awaited writes rather than a
     * batch, consistent with the rest of this file's style.
     */
    suspend fun addComment(postId: String, text: String, parentCommentId: String? = null) {
        val user = refreshedUser() ?: error("You must be signed in to comment")
        val commentData = hashMapOf<String, Any>(
            "text" to text.trim(),
            "userID" to user.uid,
            "username" to displayName(user),
            "likes" to 0,
            "replyCount" to 0,
            // Same reasoning as uploadPost(): a client timestamp so your own new comment
            // shows up immediately in your own orderBy(createdAt) query.
            "createdAt" to Timestamp.now()
        )
        if (parentCommentId != null) {
            commentData["parentCommentID"] = parentCommentId
        }

        firestore.collection(POSTS_COLLECTION).document(postId)
            .collection(COMMENTS_COLLECTION)
            .add(commentData)
            .await()

        if (parentCommentId != null) {
            firestore.collection(POSTS_COLLECTION).document(postId)
                .collection(COMMENTS_COLLECTION).document(parentCommentId)
                .update("replyCount", FieldValue.increment(1))
                .await()
        }

        firestore.collection(POSTS_COLLECTION).document(postId)
            .update("commentCount", FieldValue.increment(1))
            .await()
    }

    /**
     * Live set of comment IDs on [postId] that [currentUserId] has liked, scoped to this post
     * so the listener only ever tracks a handful of documents instead of every comment like
     * across the whole app. Matches iOS's CommentsViewModel.listenForCommentLikes().
     */
    fun likedCommentIdsFlow(postId: String, currentUserId: String): Flow<Set<String>> = callbackFlow {
        val registration = firestore.collection(COMMENT_LIKES_COLLECTION)
            .whereEqualTo("postID", postId)
            .whereEqualTo("userID", currentUserId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val ids = snapshot?.documents
                    ?.mapNotNull { it.getString("commentID") }
                    ?.toSet()
                    ?: emptySet()
                trySend(ids)
            }
        awaitClose { registration.remove() }
    }

    /**
     * Likes or unlikes a comment on behalf of the current user. Mirrors toggleLike() above --
     * a per-user document in the shared top-level "commentLikes" collection (id
     * "{commentID}_{userID}"), plus keeping the comment's denormalized `likes` counter in sync.
     */
    suspend fun toggleCommentLike(postId: String, commentId: String, isCurrentlyLiked: Boolean) {
        val user = refreshedUser() ?: error("You must be signed in to like a comment")
        val likeRef = firestore.collection(COMMENT_LIKES_COLLECTION).document("${commentId}_${user.uid}")
        val commentRef = firestore.collection(POSTS_COLLECTION).document(postId)
            .collection(COMMENTS_COLLECTION).document(commentId)

        if (isCurrentlyLiked) {
            likeRef.delete().await()
            commentRef.update("likes", FieldValue.increment(-1)).await()
        } else {
            val likeData = hashMapOf(
                "postID" to postId,
                "commentID" to commentId,
                "userID" to user.uid,
                "createdAt" to Timestamp.now()
            )
            likeRef.set(likeData).await()
            commentRef.update("likes", FieldValue.increment(1)).await()
        }
    }

    /** Live set of user IDs the given user follows, matching iOS's listenForFollowing(). */
    fun followingIdsFlow(currentUserId: String): Flow<Set<String>> = callbackFlow {
        val registration = firestore.collection(FOLLOWS_COLLECTION)
            .whereEqualTo("followerID", currentUserId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val ids = snapshot?.documents
                    ?.mapNotNull { it.getString("followeeID") }
                    ?.toSet()
                    ?: emptySet()
                trySend(ids)
            }
        awaitClose { registration.remove() }
    }

    /**
     * Follows or unfollows a user, using the same "{followerID}_{followeeID}" document ID
     * scheme as iOS's toggleFollow(for:) so both apps see the same relationship docs.
     */
    suspend fun toggleFollow(currentUserId: String, followeeId: String, isCurrentlyFollowing: Boolean) {
        if (followeeId == currentUserId) return
        val followRef = firestore.collection(FOLLOWS_COLLECTION)
            .document("${currentUserId}_$followeeId")
        if (isCurrentlyFollowing) {
            followRef.delete().await()
        } else {
            val data = hashMapOf(
                "followerID" to currentUserId,
                "followeeID" to followeeId,
                "createdAt" to Timestamp.now()
            )
            followRef.set(data).await()
        }
    }

    /**
     * Looks up a single user's profile summary (username, avatar, join date) from the top-level
     * "users" collection -- the only way to see this for anyone but the currently-signed-in
     * user, since Firebase Auth itself won't expose another account's metadata client-side.
     * Returns null if that user hasn't signed in since this feature shipped yet (no doc written
     * for them) or the fetch fails. Matches iOS's FeedViewModel.fetchUserProfile(userID:).
     */
    suspend fun fetchUserProfile(userId: String): UserProfile? {
        return try {
            val snapshot = firestore.collection(USERS_COLLECTION).document(userId).get().await()
            UserProfile.fromDocument(snapshot)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Everyone following [userId] ("who has hooked them"), resolved to full profiles for
     * display. Fetches all the followers' profiles concurrently rather than one at a time,
     * matching iOS's FeedViewModel.fetchFollowers(of:).
     */
    suspend fun fetchFollowers(userId: String): List<UserProfile> = coroutineScope {
        try {
            val snapshot = firestore.collection(FOLLOWS_COLLECTION)
                .whereEqualTo("followeeID", userId)
                .get()
                .await()
            val followerIds = snapshot.documents.mapNotNull { it.getString("followerID") }
            if (followerIds.isEmpty()) return@coroutineScope emptyList()

            followerIds
                .map { followerId -> async { fetchUserProfile(followerId) } }
                .awaitAll()
                .filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
