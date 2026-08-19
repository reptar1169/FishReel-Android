package com.reptar.fishreel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.reptar.fishreel.auth.AuthViewModel
import com.reptar.fishreel.ui.FeedViewModel
import com.reptar.fishreel.ui.ThemeViewModel
import com.reptar.fishreel.ui.screens.AuthGate
import com.reptar.fishreel.ui.screens.CommentsScreen
import com.reptar.fishreel.ui.screens.EditPostScreen
import com.reptar.fishreel.ui.screens.FeedScreen
import com.reptar.fishreel.ui.screens.LikersScreen
import com.reptar.fishreel.ui.screens.PostScreen
import com.reptar.fishreel.ui.screens.UserProfileScreen
import com.reptar.fishreel.ui.theme.FishreelTheme
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    // Class-level (not inside @Composable) so onNewIntent -- which fires outside Compose's
    // recomposition scope, e.g. when a notification or App Link is tapped while the app is
    // already running -- can update these and have Compose pick up the change. Kept as two
    // separate fields, mirroring iOS's NotificationRouter.pendingPostID vs pendingSharedPostID
    // split: a tapped comment/reply notification (extra "postID", set by
    // FishReelMessagingService or the system's auto-built PendingIntent) should jump straight
    // into that post's comments, while a tapped shared-post App Link
    // (https://fishreelapp.com/post/{id}) should instead land on the post itself, scrolled-to
    // and highlighted inline in the Feed.
    private var pendingCommentPostId by mutableStateOf<String?>(null)
    private var pendingSharedPostId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingCommentPostId = extractNotificationPostId(intent)
        pendingSharedPostId = extractSharedPostId(intent)
        setContent {
            val themeViewModel: ThemeViewModel = viewModel()
            val isDarkTheme by themeViewModel.isDarkTheme.collectAsState()

            FishreelTheme(darkTheme = isDarkTheme) {
                val authViewModel: AuthViewModel = viewModel()
                val currentUser by authViewModel.currentUser.collectAsState()

                if (currentUser == null) {
                    AuthGate(authViewModel = authViewModel, isDarkTheme = isDarkTheme)
                } else {
                    val navController = rememberNavController()
                    val feedViewModel: FeedViewModel = viewModel()

                    // Requested once per sign-in rather than on first launch only, mirroring
                    // iOS's PushNotificationManager.requestPermissionIfNeeded() call site --
                    // harmless no-op if already granted or already denied (Android won't
                    // re-prompt after a denial without a Settings round trip).
                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { /* Granted or not, FishReelMessagingService checks before posting. */ }
                    LaunchedEffect(currentUser) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    // Simpler than iOS's equivalent (NotificationRouter + resolvePendingNotification):
                    // CommentsScreen(postId) fetches its comments directly by ID, so there's no
                    // posts-list-loaded race to wait out here -- navigate as soon as a tapped
                    // notification's postId shows up.
                    LaunchedEffect(pendingCommentPostId) {
                        pendingCommentPostId?.let { postId ->
                            navController.navigate("comments/$postId")
                            pendingCommentPostId = null
                        }
                    }

                    // A shared-post App Link should land on the Feed tab itself (scrolled to and
                    // highlighted, via FeedScreen's scrollToPostId below) rather than wherever
                    // the person happened to be navigated to -- pop back to "feed" first so
                    // FeedScreen is guaranteed to be on screen to receive it. FeedScreen clears
                    // pendingSharedPostId itself (onScrollToPostIdHandled) once it's done with it,
                    // since it also needs to wait out the posts-list-loaded race iOS's
                    // resolvePendingSharedPost() waits out.
                    LaunchedEffect(pendingSharedPostId) {
                        if (pendingSharedPostId != null) {
                            navController.popBackStack("feed", inclusive = false)
                        }
                    }

                    NavHost(navController = navController, startDestination = "feed") {
                        composable("feed") {
                            FeedScreen(
                                viewModel = feedViewModel,
                                authViewModel = authViewModel,
                                themeViewModel = themeViewModel,
                                isDarkTheme = isDarkTheme,
                                onAddPost = { navController.navigate("post") },
                                onOpenComments = { postId -> navController.navigate("comments/$postId") },
                                onEditPost = { postId -> navController.navigate("editPost/$postId") },
                                onOpenLikers = { postId -> navController.navigate("likers/$postId") },
                                onOpenUserProfile = { userId, username, avatarUrl ->
                                    val encodedUsername = URLEncoder.encode(username, "UTF-8")
                                    val encodedAvatar = URLEncoder.encode(avatarUrl, "UTF-8")
                                    navController.navigate("userProfile/$userId/$encodedUsername/$encodedAvatar")
                                },
                                scrollToPostId = pendingSharedPostId,
                                onScrollToPostIdHandled = { pendingSharedPostId = null }
                            )
                        }
                        composable("post") {
                            PostScreen(
                                viewModel = feedViewModel,
                                onPostSuccess = { navController.popBackStack() },
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(
                            route = "comments/{postId}",
                            arguments = listOf(navArgument("postId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val postId = backStackEntry.arguments?.getString("postId").orEmpty()
                            CommentsScreen(
                                postId = postId,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(
                            route = "editPost/{postId}",
                            arguments = listOf(navArgument("postId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val postId = backStackEntry.arguments?.getString("postId").orEmpty()
                            EditPostScreen(
                                postId = postId,
                                viewModel = feedViewModel,
                                onSaved = { navController.popBackStack() },
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(
                            route = "likers/{postId}",
                            arguments = listOf(navArgument("postId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val postId = backStackEntry.arguments?.getString("postId").orEmpty()
                            LikersScreen(
                                postId = postId,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(
                            route = "userProfile/{userId}/{username}/{avatar}",
                            arguments = listOf(
                                navArgument("userId") { type = NavType.StringType },
                                navArgument("username") { type = NavType.StringType },
                                navArgument("avatar") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val userId = backStackEntry.arguments?.getString("userId").orEmpty()
                            val username = URLDecoder.decode(
                                backStackEntry.arguments?.getString("username").orEmpty(), "UTF-8"
                            )
                            val avatar = URLDecoder.decode(
                                backStackEntry.arguments?.getString("avatar").orEmpty(), "UTF-8"
                            )
                            UserProfileScreen(
                                userId = userId,
                                username = username,
                                avatarUrl = avatar,
                                viewModel = feedViewModel,
                                onBack = { navController.popBackStack() },
                                onFollowerClick = { followerId, followerUsername, followerAvatar ->
                                    feedViewModel.filterByUser(followerId, followerUsername, followerAvatar)
                                    navController.popBackStack("feed", inclusive = false)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Fires instead of a fresh onCreate when a notification or App Link is tapped while this
    // Activity instance already exists in the task -- guaranteed by launchMode="singleTask" in
    // the manifest, which routes the tap here rather than spawning a duplicate MainActivity.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingCommentPostId = extractNotificationPostId(intent)
        pendingSharedPostId = extractSharedPostId(intent)
    }

    /**
     * Extracts the postId from a tapped push notification (extra "postID", set by
     * FishReelMessagingService or the system's auto-built PendingIntent) -- a comment/reply
     * notification, which should jump straight into that post's comments, mirroring iOS's
     * NotificationRouter.pendingPostID.
     */
    private fun extractNotificationPostId(intent: Intent): String? {
        return intent.getStringExtra("postID")
    }

    /**
     * Extracts the postId from a tapped shared-post App Link
     * (https://fishreelapp.com/post/{id}, delivered as an ACTION_VIEW intent whose data is that
     * URL -- see the autoVerify intent-filter in AndroidManifest.xml, and
     * website/.well-known/assetlinks.json, which is what proves to Android this app owns that
     * domain). Kept separate from extractNotificationPostId above since sharing a post
     * shouldn't jump straight into its comments the way a notification does -- this instead
     * feeds FeedScreen's scrollToPostId, mirroring iOS's NotificationRouter.pendingSharedPostID.
     */
    private fun extractSharedPostId(intent: Intent): String? {
        val segments = intent.data?.pathSegments ?: return null
        return if (segments.size == 2 && segments[0] == "post") segments[1] else null
    }
}
