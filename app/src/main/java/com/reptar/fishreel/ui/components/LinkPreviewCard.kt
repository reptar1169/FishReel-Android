package com.reptar.fishreel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Brand images that shouldn't be stretched/cropped to fill this card's full-bleed 160dp
 * hero image - the site logo (`DEFAULT_OG_IMAGE`, functions/index.js's fallback whenever a
 * link post has no real Open Graph image) and the wordmark FishReel Reports' posts use
 * instead (`REPORTS_OG_IMAGE`). Neither is a photo, so cropping to fill looked blown up
 * (logo) or cut off the text (wordmark, ~16:9) - both render small and centered instead.
 * Matches iOS's LinkPreviewCardView.swift.
 */
private val BRAND_LINK_IMAGE_URLS = setOf(
    "https://fishreelapp.com/assets/logo.png",
    "https://fishreelapp.com/assets/fishreel-logo-dark.png"
)

/**
 * Renders a link post's preview card -- shared between PostScreen (live preview while
 * composing) and FeedScreen (final display) so the two never drift apart visually.
 */
@Composable
fun LinkPreviewCard(
    url: String,
    title: String,
    description: String,
    imageURL: String,
    domain: String,
    modifier: Modifier = Modifier
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column {
            if (imageURL.isNotBlank() && imageURL in BRAND_LINK_IMAGE_URLS) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(vertical = 22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = imageURL,
                        contentDescription = null,
                        modifier = Modifier.height(56.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            } else if (imageURL.isNotBlank()) {
                AsyncImage(
                    model = imageURL,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = title.ifBlank { url },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Text(
                    text = domain.ifBlank { url },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
