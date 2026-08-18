package com.reptar.fishreel.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.reptar.fishreel.data.PostRepository
import com.reptar.fishreel.model.Comment
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsScreen(postId: String, onBack: () -> Unit) {
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var likedCommentIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }
    var commentText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Set when the person taps Reply on a comment -- sendComment() then posts the draft as a
    // reply to this comment instead of a new top-level one. Mirrors iOS's
    // CommentsViewModel.replyingTo.
    var replyingTo by remember { mutableStateOf<Comment?>(null) }
    val scope = rememberCoroutineScope()
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid

    LaunchedEffect(postId) {
        PostRepository.commentsFlow(postId)
            .catch { e ->
                errorMessage = "Couldn't load comments: ${e.message ?: "unknown error"}"
                isLoading = false
            }
            .collect { list ->
                comments = list
                isLoading = false
            }
    }

    LaunchedEffect(postId, currentUserId) {
        val userId = currentUserId ?: return@LaunchedEffect
        PostRepository.likedCommentIdsFlow(postId, userId)
            .catch { /* Best-effort -- worst case, hearts just don't show as already-liked. */ }
            .collect { likedCommentIds = it }
    }

    // Top-level comments, in load order. Also surfaces any reply whose parent comment no
    // longer exists (e.g. the parent was deleted) so it doesn't just silently disappear -- it's
    // shown un-nested instead. Mirrors iOS's CommentsViewModel.topLevelComments.
    val topLevelComments = remember(comments) {
        val allIds = comments.map { it.id }.toSet()
        comments.filter { it.parentCommentID == null || it.parentCommentID !in allIds }
    }
    fun repliesFor(commentId: String) = comments.filter { it.parentCommentID == commentId }

    fun sendComment() {
        val text = commentText.trim()
        if (text.isEmpty()) return
        val parentId = replyingTo?.id
        commentText = ""
        replyingTo = null
        scope.launch {
            isSending = true
            try {
                PostRepository.addComment(postId, text, parentId)
            } catch (e: Exception) {
                errorMessage = "Couldn't post comment: ${e.message ?: "unknown error"}"
            } finally {
                isSending = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Comments") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    topLevelComments.isEmpty() -> Text(
                        text = "No comments yet — say something nice!",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(topLevelComments, key = { it.id }) { comment ->
                            CommentThread(
                                comment = comment,
                                replies = repliesFor(comment.id),
                                likedCommentIds = likedCommentIds,
                                onLikeTapped = { target ->
                                    scope.launch {
                                        try {
                                            PostRepository.toggleCommentLike(
                                                postId,
                                                target.id,
                                                target.id in likedCommentIds
                                            )
                                        } catch (e: Exception) {
                                            errorMessage = "Couldn't update like: ${e.message ?: "unknown error"}"
                                        }
                                    }
                                },
                                onReplyTapped = { parent, mentioning ->
                                    replyingTo = parent
                                    commentText = "@${mentioning.username} "
                                }
                            )
                        }
                    }
                }
            }

            errorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            replyingTo?.let { parent ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Replying to @${parent.username}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        replyingTo = null
                        commentText = ""
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel reply")
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    placeholder = { Text("Add a comment...") },
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    enabled = commentText.isNotBlank() && !isSending,
                    onClick = { sendComment() }
                ) {
                    if (isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send comment")
                    }
                }
            }
        }
    }
}

/**
 * A top-level comment plus its replies (one level deep -- a reply's own Reply button still
 * attaches to this same top-level comment, just with the @mention pointed at whichever reply
 * was tapped). Replies start collapsed behind a "View N replies" toggle, mirroring iOS's
 * CommentThreadView.
 */
@Composable
private fun CommentThread(
    comment: Comment,
    replies: List<Comment>,
    likedCommentIds: Set<String>,
    onLikeTapped: (Comment) -> Unit,
    onReplyTapped: (parent: Comment, mentioning: Comment) -> Unit
) {
    var isShowingReplies by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        CommentRow(
            comment = comment,
            isLiked = comment.id in likedCommentIds,
            onLikeTapped = { onLikeTapped(comment) },
            onReplyTapped = { onReplyTapped(comment, comment) }
        )

        if (replies.isNotEmpty()) {
            if (isShowingReplies) {
                Column(modifier = Modifier.padding(start = 40.dp)) {
                    replies.forEach { reply ->
                        CommentRow(
                            comment = reply,
                            isLiked = reply.id in likedCommentIds,
                            onLikeTapped = { onLikeTapped(reply) },
                            onReplyTapped = { onReplyTapped(comment, reply) }
                        )
                    }
                    TextButton(onClick = { isShowingReplies = false }) {
                        Text("Hide replies", style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                TextButton(
                    onClick = { isShowingReplies = true },
                    modifier = Modifier.padding(start = 40.dp)
                ) {
                    Text(
                        text = if (replies.size == 1) "View 1 reply" else "View ${replies.size} replies",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
fun CommentRow(
    comment: Comment,
    isLiked: Boolean = false,
    onLikeTapped: () -> Unit = {},
    onReplyTapped: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // The shared schema doesn't store a per-comment avatar (iOS doesn't show one either).
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = comment.username.ifBlank { "Angler" },
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = comment.text,
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(
                onClick = onReplyTapped,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text("Reply", style = MaterialTheme.typography.labelSmall)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onLikeTapped, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isLiked) "Unlike" else "Like",
                    tint = if (isLiked) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (comment.likes > 0) {
                Text(
                    text = "${comment.likes}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
