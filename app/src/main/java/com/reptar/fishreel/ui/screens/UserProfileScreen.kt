package com.reptar.fishreel.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.reptar.fishreel.model.UserProfile
import com.reptar.fishreel.ui.FeedViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Shown when tapping a user's name/avatar while already viewing their filtered feed (a second
 * tap, since the first tap is what filtered the feed down to just their posts). Mirrors iOS's
 * UserProfileView: join date and "Hooked By" come from the shared top-level "users"/"follows"
 * collections, while the post count is derived client-side from the posts already loaded for
 * the main feed, same approach as the Feed/Hooked tab filtering elsewhere in this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    userId: String,
    username: String,
    avatarUrl: String,
    viewModel: FeedViewModel,
    onBack: () -> Unit,
    onFollowerClick: (userId: String, username: String, avatarUrl: String) -> Unit
) {
    var profile by remember(userId) { mutableStateOf<UserProfile?>(null) }
    var followers by remember(userId) { mutableStateOf<List<UserProfile>>(emptyList()) }
    var isLoading by remember(userId) { mutableStateOf(true) }

    val posts by viewModel.posts.collectAsState()
    val postCount = remember(posts, userId) { posts.count { it.userID == userId } }

    LaunchedEffect(userId) {
        isLoading = true
        coroutineScope {
            val profileDeferred = async { viewModel.fetchUserProfile(userId) }
            val followersDeferred = async { viewModel.fetchFollowers(userId) }
            profile = profileDeferred.await()
            followers = followersDeferred.await()
        }
        isLoading = false
    }

    val joinedDateText = profile?.createdAt?.let {
        "Joined " + SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(it.toDate())
    } ?: "Join date unavailable"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ProfileAvatar(avatarUrl = profile?.avatar?.takeIf { it.isNotBlank() } ?: avatarUrl, size = 84.dp)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = profile?.username?.ifBlank { username } ?: username,
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = joinedDateText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (postCount == 1) "1 post" else "$postCount posts",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            Text(
                text = "Hooked By",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    followers.isEmpty() -> {
                        Text(
                            text = "No one has hooked ${username.ifBlank { "this angler" }} yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(followers, key = { it.id }) { follower ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onFollowerClick(follower.id, follower.username, follower.avatar) }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ProfileAvatar(avatarUrl = follower.avatar, size = 36.dp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = follower.username.ifBlank { "Angler" },
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileAvatar(avatarUrl: String, size: Dp) {
    if (avatarUrl.startsWith("http")) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    }
}
