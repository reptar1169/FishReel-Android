package com.reptar.fishreel.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.reptar.fishreel.ui.FeedViewModel
import com.reptar.fishreel.ui.components.LinkPreviewCard
import com.reptar.fishreel.ui.components.VideoPlayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPostScreen(postId: String, viewModel: FeedViewModel, onSaved: () -> Unit, onBack: () -> Unit) {
    val posts by viewModel.posts.collectAsState()
    val post = posts.firstOrNull { it.id == postId }

    if (post == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Edit Post") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("This post is no longer available.")
            }
        }
        return
    }

    var caption by remember(post.id) { mutableStateOf(post.caption) }
    var newImageUri by remember(post.id) { mutableStateOf<Uri?>(null) }
    val uploading by viewModel.uploading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) newImageUri = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Post") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Only photo posts can swap their media from here -- video/link posts show a
            // read-only preview of their existing content and can only have their caption edited.
            when {
                post.isVideo -> {
                    VideoPlayer(
                        uri = Uri.parse(post.postVideo),
                        modifier = Modifier.fillMaxWidth(),
                        thumbnailUrl = post.postVideoThumbnail
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                post.isURLPost -> {
                    LinkPreviewCard(
                        url = post.postURL,
                        title = post.linkTitle,
                        description = post.linkDescription,
                        imageURL = post.linkImageURL,
                        domain = post.linkDomain
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                else -> {
                    // Show the newly picked photo if there is one, otherwise the post's existing photo.
                    val previewModel: Any? = newImageUri ?: post.postImage.takeIf { it.isNotBlank() }
                    if (previewModel != null) {
                        AsyncImage(
                            model = previewModel,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Button(onClick = { launcher.launch("image/*") }, enabled = !uploading) {
                        Text("Change photo")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                label = { Text("Caption") },
                enabled = !uploading,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (uploading) {
                CircularProgressIndicator()
                Text(
                    text = "Saving...",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Button(
                    onClick = {
                        viewModel.updatePost(post.id, caption, post.postImage, newImageUri, onSaved)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save")
                }
            }
        }
    }
}
