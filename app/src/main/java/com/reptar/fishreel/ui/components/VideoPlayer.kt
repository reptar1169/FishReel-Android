package com.reptar.fishreel.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

/**
 * A basic Media3 (ExoPlayer) video player for both the new-post video preview and feed
 * playback.
 *
 * When [thumbnailUrl] is available (posts made after the postVideoThumbnail feature shipped --
 * see VideoThumbnailExtractor), this starts as a static poster image with a play button overlay
 * and defers creating the ExoPlayer entirely until tapped, mirroring iOS's PostVideoView --
 * scrolling past a video then costs nothing until someone actually taps play. Older posts (or
 * the live preview while composing a new post, which has no thumbnail yet) fall back to the
 * previous behavior: playback starts immediately, paused, with controls visible.
 *
 * When [enableFullscreen] is set, an expand button overlays the inline (already-playing) player;
 * tapping it opens a full-screen Dialog that reuses this same ExoPlayer instance (rather than
 * starting a second one), so playback position and state carry over exactly like iOS's
 * FullScreenVideoPlayer reusing the same AVPlayer. The inline PlayerView is removed from
 * composition (not just hidden) while the dialog is open so the two views never fight over the
 * player's render surface at the same time.
 */
@Composable
fun VideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    thumbnailUrl: String = "",
    enableFullscreen: Boolean = false
) {
    // No thumbnail means there's nothing to show while waiting for a tap, so preserve the
    // original behavior for those: start "playing" (paused, controls visible) right away.
    var hasStartedPlayback by remember(uri) { mutableStateOf(thumbnailUrl.isBlank()) }

    if (!hasStartedPlayback) {
        Box(modifier = modifier) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clickable { hasStartedPlayback = true },
                contentScale = ContentScale.Crop
            )
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .padding(12.dp)
                    .clickable { hasStartedPlayback = true }
            )
        }
    } else {
        PlayingVideo(
            uri = uri,
            modifier = modifier,
            // Only autoplay when this came from an explicit tap on the thumbnail's play button
            // -- the no-thumbnail fallback path still starts paused, matching prior behavior.
            autoplay = thumbnailUrl.isNotBlank(),
            enableFullscreen = enableFullscreen
        )
    }
}

@Composable
private fun PlayingVideo(uri: Uri, modifier: Modifier, autoplay: Boolean, enableFullscreen: Boolean) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = autoplay
            prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    var isFullScreen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        if (!isFullScreen) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            )
            if (enableFullscreen) {
                IconButton(
                    onClick = { isFullScreen = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                ) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "Full screen", tint = Color.White)
                }
            }
        } else {
            // Placeholder that holds the same footprint while the dialog below owns the
            // player's render surface, so the layout doesn't jump when toggling fullscreen.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(Color.Black)
            )
        }
    }

    if (isFullScreen) {
        Dialog(
            onDismissRequest = { isFullScreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                IconButton(
                    onClick = { isFullScreen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}
