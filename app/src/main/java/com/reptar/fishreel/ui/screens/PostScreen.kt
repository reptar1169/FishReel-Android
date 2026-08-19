package com.reptar.fishreel.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.reptar.fishreel.data.LinkMetadata
import com.reptar.fishreel.ui.FeedViewModel
import com.reptar.fishreel.ui.components.LinkPreviewCard
import com.reptar.fishreel.ui.components.VideoPlayer
import kotlinx.coroutines.delay

// TEXT appended last so PHOTO/VIDEO/LINK keep their existing ordinal-based tab indices.
private enum class PostType { PHOTO, VIDEO, LINK, TEXT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostScreen(viewModel: FeedViewModel, onPostSuccess: () -> Unit, onBack: () -> Unit) {
    var postType by remember { mutableStateOf(PostType.PHOTO) }
    var caption by remember { mutableStateOf("") }

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }

    var linkUrl by remember { mutableStateOf("") }
    var linkPreview by remember { mutableStateOf<LinkMetadata?>(null) }
    var isFetchingPreview by remember { mutableStateOf(false) }

    val uploading by viewModel.uploading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Debounced live preview: waits for a pause in typing before hitting the network, and
    // ignores results for a URL that's since changed (or emptied) while the fetch was in flight.
    LaunchedEffect(linkUrl) {
        if (linkUrl.isBlank()) {
            linkPreview = null
            isFetchingPreview = false
            return@LaunchedEffect
        }
        isFetchingPreview = true
        delay(800)
        val result = viewModel.fetchLinkPreview(linkUrl)
        if (linkUrl.isNotBlank()) {
            linkPreview = result
        }
        isFetchingPreview = false
    }

    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> selectedImageUri = uri }

    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> selectedVideoUri = uri }

    val canPost = !uploading && when (postType) {
        PostType.PHOTO -> selectedImageUri != null
        PostType.VIDEO -> selectedVideoUri != null
        PostType.LINK -> linkUrl.isNotBlank()
        PostType.TEXT -> caption.isNotBlank()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Post") },
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
                .padding(16.dp)
                // Safety net so the Post button stays reachable regardless of preview size or
                // screen height -- the photo preview height is also capped below, but this
                // keeps things scrollable if that or anything else ever grows too tall again.
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TabRow(selectedTabIndex = postType.ordinal, modifier = Modifier.fillMaxWidth()) {
                Tab(
                    selected = postType == PostType.PHOTO,
                    onClick = { postType = PostType.PHOTO },
                    text = { Text("Photo") },
                    enabled = !uploading
                )
                Tab(
                    selected = postType == PostType.VIDEO,
                    onClick = { postType = PostType.VIDEO },
                    text = { Text("Video") },
                    enabled = !uploading
                )
                Tab(
                    selected = postType == PostType.LINK,
                    onClick = { postType = PostType.LINK },
                    text = { Text("Link") },
                    enabled = !uploading
                )
                Tab(
                    selected = postType == PostType.TEXT,
                    onClick = { postType = PostType.TEXT },
                    text = { Text("Text") },
                    enabled = !uploading
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (postType) {
                PostType.PHOTO -> {
                    if (selectedImageUri != null) {
                        // Capped at a fixed height (matching the 300.dp video preview below)
                        // instead of scaling to the image's natural aspect ratio -- an
                        // uncapped FillWidth on a portrait photo rendered tall enough to push
                        // the Post button off-screen, and the Column wasn't scrollable.
                        AsyncImage(
                            model = selectedImageUri,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { imageLauncher.launch("image/*") }, enabled = !uploading) {
                            Text("Choose a different photo")
                        }
                    } else {
                        Button(onClick = { imageLauncher.launch("image/*") }, enabled = !uploading) {
                            Text("Select Photo")
                        }
                    }
                }

                PostType.VIDEO -> {
                    if (selectedVideoUri != null) {
                        VideoPlayer(uri = selectedVideoUri!!, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { videoLauncher.launch("video/*") }, enabled = !uploading) {
                            Text("Choose a different video")
                        }
                    } else {
                        Button(onClick = { videoLauncher.launch("video/*") }, enabled = !uploading) {
                            Text("Select Video")
                        }
                    }
                    Text(
                        text = "Max 100MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                PostType.LINK -> {
                    OutlinedTextField(
                        value = linkUrl,
                        onValueChange = { linkUrl = it },
                        label = { Text("URL") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        enabled = !uploading,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    when {
                        isFetchingPreview -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Fetching preview…",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        linkPreview != null -> {
                            LinkPreviewCard(
                                url = linkUrl,
                                title = linkPreview!!.title,
                                description = linkPreview!!.description,
                                imageURL = linkPreview!!.imageURL,
                                domain = linkPreview!!.domain
                            )
                        }
                        linkUrl.isNotBlank() -> {
                            Text(
                                text = "No preview available -- the link will still post.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                PostType.TEXT -> {
                    Text(
                        text = "No photo, video, or link needed -- just write a caption below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    text = "Uploading...",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Button(
                    onClick = {
                        when (postType) {
                            PostType.PHOTO -> selectedImageUri?.let {
                                viewModel.uploadPhotoPost(it, caption, onPostSuccess)
                            }
                            PostType.VIDEO -> selectedVideoUri?.let {
                                viewModel.uploadVideoPost(it, caption, onPostSuccess)
                            }
                            PostType.LINK -> viewModel.uploadLinkPost(linkUrl, caption, linkPreview, onPostSuccess)
                            PostType.TEXT -> viewModel.uploadTextPost(caption, onPostSuccess)
                        }
                    },
                    enabled = canPost,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Post")
                }
            }
        }
    }
}
