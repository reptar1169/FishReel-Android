package com.reptar.fishreel.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.reptar.fishreel.R
import com.reptar.fishreel.auth.AuthViewModel
import com.reptar.fishreel.data.ReportHelper
import com.reptar.fishreel.data.ShareHelper
import com.reptar.fishreel.model.Post
import com.reptar.fishreel.ui.FeedViewModel
import com.reptar.fishreel.ui.ThemeViewModel
import com.reptar.fishreel.ui.components.LinkPreviewCard
import com.reptar.fishreel.ui.components.VideoPlayer
import com.reptar.fishreel.ui.components.ZoomableImageViewer
import com.google.firebase.Timestamp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    authViewModel: AuthViewModel,
    themeViewModel: ThemeViewModel,
    isDarkTheme: Boolean,
    onAddPost: () -> Unit,
    onOpenComments: (String) -> Unit,
    onEditPost: (String) -> Unit,
    onOpenLikers: (String) -> Unit,
    onOpenUserProfile: (userId: String, username: String, avatarUrl: String) -> Unit,
    // Set by MainActivity when a shared post link (App Link) is tapped -
    // scrolls to and briefly highlights that post in the main Feed tab,
    // then reports back via onScrollToPostIdHandled so the caller clears
    // its pending state. Unlike a tapped notification (handled entirely
    // in MainActivity - opens comments directly), sharing a post lands on
    // the post itself, not its comments. See MainActivity.applyIntent.
    scrollToPostId: String? = null,
    onScrollToPostIdHandled: () -> Unit = {}
) {
    val posts by viewModel.posts.collectAsState()
    val hookedPosts by viewModel.hookedPosts.collectAsState()
    val reportPosts by viewModel.reportPosts.collectAsState()
    val filteredPosts by viewModel.filteredPosts.collectAsState()
    val selectedUserId by viewModel.selectedUserId.collectAsState()
    val selectedUsername by viewModel.selectedUsername.collectAsState()
    val selectedUserAvatar by viewModel.selectedUserAvatar.collectAsState()
    val isLoading by viewModel.isLoadingFeed.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val followingIds by viewModel.followingIds.collectAsState()
    val likedPostIds by viewModel.likedPostIds.collectAsState()
    val currentUserId = viewModel.currentUserId
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // The Reports tab has its own 3-way sub-tab (see ReportsHeader below), defaulting to the
    // fish-count graph: 0 = Dock Totals, 1 = Reports by Dock (the post list), 2 = Bait Report.
    // Replaces the old reportsShowFeed boolean toggle now that there are three sections instead
    // of two. Mirrors iOS's ReportsTab enum in ContentView.swift.
    var reportsSubTab by rememberSaveable { mutableIntStateOf(0) }
    // San Diego is the only option today, but this is a dropdown (see ReportsHeader) so more
    // regions can be added later without another redesign. Mirrors iOS's FishingRegion menu.
    var selectedRegion by rememberSaveable { mutableStateOf("San Diego") }
    var regionMenuExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // The graph, Bait Report, and Account tab each scroll a plain Column rather than the
    // LazyColumn listState above drives -- lifted here so their scroll direction can also feed
    // the bottom bar's hide-on-scroll-down behavior below.
    val graphScrollState = rememberScrollState()
    val baitReportScrollState = rememberScrollState()
    val accountScrollState = rememberScrollState()
    val showBottomBar = when {
        selectedTab == 2 && reportsSubTab == 0 -> graphScrollState.isScrollingUp()
        selectedTab == 2 && reportsSubTab == 2 -> baitReportScrollState.isScrollingUp()
        selectedTab == 3 -> accountScrollState.isScrollingUp()
        else -> listState.isScrollingUp()
    }
    var highlightedPostId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Re-runs whenever `posts`/`reportPosts` change too, not just scrollToPostId - if the
    // shared post hasn't loaded into the live listener yet (e.g. a cold launch), the index
    // lookup below just misses this time and retries automatically the next time either list
    // updates, no separate fetch-by-id needed. Checks reportPosts too since a shared bot-post
    // link (e.g. a fish-count report) now belongs to the dedicated Reports tab, not the main
    // Feed -- mirrors iOS's resolvePendingSharedPost().
    LaunchedEffect(scrollToPostId, posts, reportPosts) {
        val postId = scrollToPostId ?: return@LaunchedEffect
        val feedIndex = posts.indexOfFirst { it.id == postId }
        val reportIndex = if (feedIndex == -1) reportPosts.indexOfFirst { it.id == postId } else -1
        val index = if (feedIndex != -1) feedIndex else reportIndex
        if (index == -1) return@LaunchedEffect

        // Land on the Feed tab (unfiltered - a shared post could belong to anyone, not just
        // whoever's currently filtered/hooked) or the Reports tab, whichever it was found in.
        selectedTab = if (feedIndex != -1) 0 else 2
        if (feedIndex == -1) {
            // The Reports tab opens to Dock Totals by default - without this, landing here via a
            // shared link would select the tab but never actually show the highlighted post.
            reportsSubTab = 1
        }
        viewModel.clearUserFilter()
        listState.animateScrollToItem(index)
        highlightedPostId = postId
        onScrollToPostIdHandled()

        // A brief flash to draw the eye to the shared post, not a
        // permanent marker.
        delay(1600)
        if (highlightedPostId == postId) {
            highlightedPostId = null
        }
    }

    val displayedPosts = when {
        selectedTab == 1 -> hookedPosts
        // Dock Totals and Bait Report render their own content directly (see the Box{when{}}
        // below) rather than the shared LazyColumn, so only the Reports-by-Dock sub-tab needs
        // the post list here.
        selectedTab == 2 && reportsSubTab == 1 -> reportPosts
        selectedTab == 2 -> emptyList()
        selectedTab == 3 -> emptyList()
        selectedUserId != null -> filteredPosts
        else -> posts
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Image(
                        painter = painterResource(
                            if (isDarkTheme) R.drawable.fishreel_title_dark else R.drawable.fishreel_title_light
                        ),
                        contentDescription = "Fishreel",
                        modifier = Modifier.height(48.dp)
                    )
                },
                actions = {
                    // Top-right, matching iOS's ToolbarItem(placement: .topBarTrailing) "+"
                    // button (was a bottom-right FAB here before). Only the main Feed tab is
                    // "your" unfiltered stream -- posting from Hooked/Reports/Account would be
                    // confusing since none of those are "everything you've posted to."
                    if (selectedTab == 0) {
                        IconButton(onClick = onAddPost) {
                            Icon(Icons.Default.Add, contentDescription = "Add Post")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Moved down from a top TabRow so the Feed/Hooked switch is reachable one-handed
            // on large-screen devices -- Scaffold automatically keeps the FAB and content
            // padding clear of this bar. Now also hides on scroll-down and reappears on
            // scroll-up (tracked via listState.isScrollingUp() below) to reclaim screen space
            // while reading; Scaffold reclaims/restores the content padding as it animates.
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                // Left at Material3's default height (~80dp) -- a fixed shorter height looked
                // fine on some devices but clipped on others (larger font-scale settings, etc.),
                // so the default's built-in adaptiveness is safer than a hardcoded value.
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            viewModel.clearUserFilter()
                        },
                        icon = {
                            Icon(
                                painterResource(R.drawable.ic_tab_feed),
                                contentDescription = null,
                                // Layout box stays the original 24dp -- same as the hook -- so
                                // the selected-state indicator pill doesn't grow. scale() only
                                // affects the drawn pixels, not the measured size the indicator
                                // wraps.
                                modifier = Modifier
                                    .size(24.dp)
                                    .scale(2f)
                            )
                        },
                        label = { Text("Feed") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            viewModel.clearUserFilter()
                        },
                        icon = {
                            Icon(
                                painterResource(R.drawable.ic_tab_hooked),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = { Text("Hooked") }
                    )
                    // No custom asset for this tab -- a core Material icon, same as this file's
                    // other non-custom cases. Graph icon since the tab now opens straight to
                    // the fish-count charts (see reportsSubTab above).
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = {
                            selectedTab = 2
                            viewModel.clearUserFilter()
                        },
                        icon = {
                            Icon(
                                Icons.Default.ShowChart,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = { Text("Reports") }
                    )
                    // Mirrors iOS's Account tab (AccountView) -- a real tab rather than a
                    // top-bar avatar button, so profile/sign-out/delete-account live inline
                    // here (see ProfileContent) instead of behind a separate pushed screen.
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = { Text("Account") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectedTab == 2) {
                ReportsHeader(
                    selectedRegion = selectedRegion,
                    regionMenuExpanded = regionMenuExpanded,
                    onRegionMenuExpandedChange = { regionMenuExpanded = it },
                    onRegionSelected = {
                        selectedRegion = it
                        regionMenuExpanded = false
                    },
                    selectedSubTab = reportsSubTab,
                    onSubTabSelected = { reportsSubTab = it }
                )
            }

            if (selectedUserId != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // A quick shortcut to this person's profile without needing to scroll to a
                    // post row -- kept separate from the bottom bar's Account tab (which always
                    // means "go to my account" everywhere else in the app) so that stays predictable.
                    val avatarModifier = Modifier
                        .size(28.dp)
                        .clickable {
                            onOpenUserProfile(
                                selectedUserId.orEmpty(),
                                selectedUsername.orEmpty(),
                                selectedUserAvatar.orEmpty()
                            )
                        }
                    if (!selectedUserAvatar.isNullOrBlank() && selectedUserAvatar!!.startsWith("http")) {
                        AsyncImage(
                            model = selectedUserAvatar,
                            contentDescription = "Open profile",
                            modifier = avatarModifier.clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "Open profile",
                            modifier = avatarModifier
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Posts by ${selectedUsername ?: "this angler"}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.clearUserFilter() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Show All")
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    selectedTab == 2 && reportsSubTab == 0 -> {
                        // The graph is its own self-contained empty state (no data yet vs. no
                        // reports at all look the same to it), so this branch short-circuits
                        // before any of the list-oriented isLoading/empty checks below.
                        FishCountsGraphContent(
                            reportPosts = reportPosts,
                            scrollState = graphScrollState,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    selectedTab == 2 && reportsSubTab == 2 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(baitReportScrollState)
                                .padding(16.dp)
                        ) {
                            BaitReportCard()
                        }
                    }
                    selectedTab == 3 -> {
                        ProfileContent(
                            authViewModel = authViewModel,
                            themeViewModel = themeViewModel,
                            snackbarHostState = snackbarHostState,
                            scrollState = accountScrollState,
                            // "My Posts" replaces the old dedicated My Fish tab (removed for
                            // being 100% redundant with tapping your own username elsewhere -
                            // same viewModel.posts-filtered-by-userID data, just with less
                            // functionality). Reuses the exact same filterByUser() + Feed-tab
                            // mechanism that powers tapping *any* username, rather than a
                            // separate one-off "my posts" screen/query.
                            onOpenMyPosts = {
                                authViewModel.currentUser.value?.let { user ->
                                    val username = user.displayName?.ifBlank { null } ?: user.email ?: "FishReel User"
                                    val avatar = user.photoUrl?.toString() ?: "person.circle"
                                    viewModel.filterByUser(user.uid, username, avatar)
                                    selectedTab = 0
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    displayedPosts.isEmpty() && selectedUserId != null -> {
                        Text(
                            text = "No posts from ${selectedUsername ?: "this angler"} yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                        )
                    }
                    displayedPosts.isEmpty() && selectedTab == 0 -> {
                        Text(
                            text = "No catches yet — be the first to post!",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    displayedPosts.isEmpty() && selectedTab == 2 -> {
                        Text(
                            text = "No fish count reports yet — check back each morning.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                        )
                    }
                    displayedPosts.isEmpty() -> {
                        Text(
                            text = "You haven't hooked anyone yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                        )
                    }
                    else -> {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(displayedPosts, key = { it.id }) { post ->
                                PostItem(
                                    post = post,
                                    currentUserId = currentUserId,
                                    isFollowing = post.userID != null && followingIds.contains(post.userID),
                                    isLiked = likedPostIds.contains(post.id),
                                    highlighted = post.id == highlightedPostId,
                                    onToggleLike = { viewModel.toggleLike(post) },
                                    onOpenLikers = { onOpenLikers(post.id) },
                                    onOpenComments = { onOpenComments(post.id) },
                                    onDelete = { viewModel.deletePost(post) },
                                    onEdit = { onEditPost(post.id) },
                                    onToggleFollow = { viewModel.toggleFollow(post) },
                                    onUsernameClick = {
                                        val uid = post.userID
                                        if (uid != null) {
                                            // A second tap on the same person whose filtered
                                            // feed we're already viewing would just re-apply an
                                            // identical filter -- treat it instead as "show me
                                            // more about this person" and open their profile.
                                                            if (uid == selectedUserId) {
                                                onOpenUserProfile(uid, post.username, post.userAvatar)
                                            } else {
                                                selectedTab = 0
                                                viewModel.filterByUser(uid, post.username, post.userAvatar)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Matches iOS's PostRow.attributedCaption(_:) pattern -- see annotatedCaption() below. */
private val CAPTION_LINK_REGEX = Regex("""\[([^\]]+)\]\((https?://[^\s)]+)\)""")

/**
 * Turns "[label](https://example.com)" markdown-style link spans in a caption into actual
 * tappable links, leaving everything else as plain text -- a narrow, custom parser (not a
 * general Markdown renderer) so a caption that happens to contain a stray "*" or "_" isn't
 * unexpectedly reformatted. Mirrors iOS's PostRow.attributedCaption(_:). Only recognizes the
 * bracket-link pattern the FishReel Reports bot uses to link out to each landing's own
 * fish-count page; a caption with no such spans (every other post) renders unchanged.
 * Compose's Text(AnnotatedString) overload handles LinkAnnotation taps natively, no
 * ClickableText/pointerInput plumbing needed.
 */
private fun annotatedCaption(caption: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var lastIndex = 0
    for (match in CAPTION_LINK_REGEX.findAll(caption)) {
        append(caption.substring(lastIndex, match.range.first))
        val label = match.groupValues[1]
        val url = match.groupValues[2]
        withLink(
            LinkAnnotation.Url(
                url,
                TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            )
        ) {
            append(label)
        }
        lastIndex = match.range.last + 1
    }
    append(caption.substring(lastIndex))
}

/**
 * Reports tab header: a region picker (San Diego only for now, but built as a dropdown menu so
 * more regions can be added later without another redesign - mirrors iOS's FishingRegion Menu in
 * ContentView.swift) plus a 3-way sub-tab selector (Dock Totals / Reports by Dock / Bait Report)
 * that replaces the old single "Reports by Dock" / "Show Graph" toggle button that used to live
 * in the top bar's actions. Rendered only when selectedTab == 2 (see FeedScreen).
 */
@Composable
private fun ReportsHeader(
    selectedRegion: String,
    regionMenuExpanded: Boolean,
    onRegionMenuExpandedChange: (Boolean) -> Unit,
    onRegionSelected: (String) -> Unit,
    selectedSubTab: Int,
    onSubTabSelected: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Box {
            TextButton(onClick = { onRegionMenuExpandedChange(true) }) {
                Text(
                    text = selectedRegion,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = regionMenuExpanded,
                onDismissRequest = { onRegionMenuExpandedChange(false) }
            ) {
                // San Diego is the only region scraped today (see functions/index.js) - listed
                // explicitly rather than hardcoded into the button so adding a second region
                // later is just adding another DropdownMenuItem here.
                DropdownMenuItem(
                    text = { Text("San Diego") },
                    onClick = { onRegionSelected("San Diego") }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        val subTabLabels = listOf("Dock Totals", "Reports by Dock", "Bait Report")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            subTabLabels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = selectedSubTab == index,
                    onClick = { onSubTabSelected(index) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = subTabLabels.size)
                ) {
                    Text(label)
                }
            }
        }
    }
}

/**
 * Compact relative post age for the header (e.g. "10m", "2h", "5d", "3mo", "1y") -- mirrors
 * iOS's PostRow.relativeTimeString(from:) and the website feed's relativeTime() in
 * feed-shared.js. Deliberately terse (no "ago" suffix) since this sits inline next to the
 * username rather than as its own sentence; "just now" is the only spelled-out case, for
 * anything under a minute.
 */
private fun relativeTimeString(timestamp: Timestamp): String {
    val seconds = ((System.currentTimeMillis() - timestamp.toDate().time) / 1000).coerceAtLeast(0)
    val units = listOf(
        "y" to 31_536_000L,
        "mo" to 2_592_000L,
        "d" to 86_400L,
        "h" to 3_600L,
        "m" to 60L
    )
    for ((label, unitSeconds) in units) {
        val value = seconds / unitSeconds
        if (value >= 1) return "$value$label"
    }
    return "just now"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PostItem(
    post: Post,
    currentUserId: String?,
    isFollowing: Boolean,
    isLiked: Boolean,
    highlighted: Boolean = false,
    onToggleLike: () -> Unit,
    onOpenLikers: () -> Unit,
    onOpenComments: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onToggleFollow: () -> Unit,
    onUsernameClick: () -> Unit = {}
) {
    var showDeleteDialog by rememberSaveable(post.id) { mutableStateOf(false) }
    var showMenu by remember(post.id) { mutableStateOf(false) }
    var showReportMenu by remember(post.id) { mutableStateOf(false) }
    var showFullScreenImage by remember(post.id) { mutableStateOf(false) }
    val isOwner = currentUserId != null && currentUserId == post.userID
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Fades in quickly when a shared link scrolls to this post, then fades
    // back out on its own after the delay in FeedScreen's LaunchedEffect
    // above flips `highlighted` back to false.
    val baseCardColor = CardDefaults.cardColors().containerColor
    val cardColor by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primaryContainer else baseCardColor,
        animationSpec = tween(durationMillis = if (highlighted) 200 else 600),
        label = "postHighlight"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Same tappable target as the username below -- only clickable when there's a
                // userID to act on, matching that guard.
                val avatarModifier = Modifier
                    .size(32.dp)
                    .let { base -> if (post.userID != null) base.clickable { onUsernameClick() } else base }
                if (post.hasPhotoAvatar) {
                    AsyncImage(
                        model = post.userAvatar,
                        contentDescription = null,
                        modifier = avatarModifier.clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = avatarModifier
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Outer Row carries the weight(1f) that pushes the trailing menu/follow
                // controls to the right - kept separate from the username Text's own
                // clickable() so the relative-time label alongside it stays outside the
                // tap target that opens the user's profile, matching iOS's PostRow.
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = post.username.ifBlank { "Angler" },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.let { base ->
                            // Only clickable when there's a userID to filter by -- a blank/legacy
                            // post without one has nothing to repopulate the feed with.
                            if (post.userID != null) base.clickable { onUsernameClick() } else base
                        }
                    )
                    post.createdAt?.let { timestamp ->
                        Text(
                            text = relativeTimeString(timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
                if (isOwner) {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Post options")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                onClick = {
                                    showMenu = false
                                    onEdit()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
                                }
                            )
                        }
                    }
                } else if (currentUserId != null) {
                    // Follow toggle only makes sense when there's an owner to follow -- a
                    // blank/legacy post without a userID just skips straight to the report menu.
                    if (post.userID != null) {
                        val seaBlueGreen = colorResource(R.color.sea_blue_green)
                        // Plain tappable icon+text, no button chrome/background/min-size -- just
                        // clickable(), which still gets a ripple for free. The label itself
                        // ("Hooked" vs "Hook 'em") is what communicates the on/off state now.
                        Row(
                            modifier = Modifier.clickable { onToggleFollow() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_tab_hooked),
                                contentDescription = null,
                                tint = seaBlueGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isFollowing) "Hooked" else "Hook 'em",
                                color = seaBlueGreen,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showReportMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Post options")
                        }
                        DropdownMenu(expanded = showReportMenu, onDismissRequest = { showReportMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Report post") },
                                onClick = {
                                    showReportMenu = false
                                    // Deliberately opens an email app (not the general
                                    // Sharesheet) addressed to the reporting contact published
                                    // in the Terms of Use -- see ReportHelper.
                                    try {
                                        context.startActivity(ReportHelper.buildReportIntent(post))
                                    } catch (_: Exception) {
                                        // No email app available on this device; fail silently
                                        // rather than crash the feed.
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Caption - right under the header/username now (was previously below the
            // like/comment row), and only rendered when there's caption text so a
            // photo/video/link posted without one doesn't leave a stray gap here.
            if (post.caption.isNotBlank()) {
                Text(
                    text = annotatedCaption(post.caption, colorResource(R.color.sea_blue_green)),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // FishReel Reports bot posts: postURL is only set for the canonical
            // fishreelapp.com share-link's Open Graph preview (see postLink in
            // functions/index.js), not something to show in-app - it would otherwise trip
            // isURLPost below and render a redundant fishreel-logo link card with the landing
            // title/domain repeated underneath, when the full report (with tappable
            // per-landing links) already sits in the caption above. The bot signs in as a
            // real Firebase Auth account (see FeedViewModel.FISH_COUNTS_BOT_UID), so
            // post.userID is a real uid here, not null - checking against that constant
            // rather than null is what actually identifies these posts. Mirrors iOS's PostRow.
            if (post.userID == FeedViewModel.FISH_COUNTS_BOT_UID) {
                // Nothing rendered here for bot posts.
            } else if (post.postImage.isNotBlank()) {
                // FillWidth (not Crop) so the full photo is visible at its natural aspect
                // ratio, matching the iOS app, instead of cropping top/bottom or sides to
                // force it into a fixed-height box.
                AsyncImage(
                    model = post.postImage,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFullScreenImage = true },
                    contentScale = ContentScale.FillWidth
                )
            } else if (post.isVideo) {
                VideoPlayer(
                    uri = Uri.parse(post.postVideo),
                    modifier = Modifier.fillMaxWidth(),
                    thumbnailUrl = post.postVideoThumbnail,
                    enableFullscreen = true
                )
            } else if (post.isURLPost) {
                LinkPreviewCard(
                    url = post.postURL,
                    title = post.linkTitle,
                    description = post.linkDescription,
                    imageURL = post.linkImageURL,
                    domain = post.linkDomain,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clickable {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(post.postURL)))
                            } catch (_: Exception) {
                                // Malformed URL or no app can handle it; fail silently rather
                                // than crash the feed.
                            }
                        }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // A plain IconButton only exposes onClick -- combinedClickable adds the
                // long-press-to-see-who-liked-this gesture on top of the normal tap-to-toggle,
                // while keeping the same 48dp touch target and ripple feedback.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .combinedClickable(onClick = onToggleLike, onLongClick = onOpenLikers),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (isLiked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(text = post.likes.toString(), style = MaterialTheme.typography.bodyMedium)

                Spacer(modifier = Modifier.width(16.dp))

                IconButton(onClick = onOpenComments) {
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = "Comments")
                }
                Text(text = post.commentCount.toString(), style = MaterialTheme.typography.bodyMedium)

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = {
                    scope.launch {
                        val intent = ShareHelper.buildShareIntent(context, post)
                        context.startActivity(Intent.createChooser(intent, "Share post"))
                    }
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete post?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showFullScreenImage && post.postImage.isNotBlank()) {
        ZoomableImageViewer(
            imageUrl = post.postImage,
            onDismiss = { showFullScreenImage = false }
        )
    }
}

/**
 * True while the list's most recent movement was upward (or it hasn't moved at all), false
 * once it's moved downward -- drives the bottom bar's hide-on-scroll-down/show-on-scroll-up
 * behavior. Compares both the visible item index and its scroll offset, since a downward
 * scroll can either advance to a new first-visible item or just push the current one further
 * up, depending on how far the fling goes.
 */
@Composable
private fun LazyListState.isScrollingUp(): Boolean {
    var previousIndex by remember(this) { mutableIntStateOf(firstVisibleItemIndex) }
    var previousScrollOffset by remember(this) { mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            if (previousIndex != firstVisibleItemIndex) {
                previousIndex > firstVisibleItemIndex
            } else {
                previousScrollOffset >= firstVisibleItemScrollOffset
            }.also {
                previousIndex = firstVisibleItemIndex
                previousScrollOffset = firstVisibleItemScrollOffset
            }
        }
    }.value
}

/**
 * Same hide-on-scroll-down/show-on-scroll-up signal as LazyListState.isScrollingUp() above, but
 * for the plain Column+verticalScroll used by the Reports tab's graph and the Account tab
 * (FishCountsGraphContent, ProfileContent) -- neither is a LazyColumn, so there's no item index,
 * just a single scroll offset to compare against its previous value.
 */
@Composable
private fun ScrollState.isScrollingUp(): Boolean {
    var previousValue by remember(this) { mutableIntStateOf(value) }
    return remember(this) {
        derivedStateOf {
            (previousValue >= value).also { previousValue = value }
        }
    }.value
}
