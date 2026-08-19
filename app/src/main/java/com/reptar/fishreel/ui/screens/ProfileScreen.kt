package com.reptar.fishreel.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.reptar.fishreel.auth.AuthViewModel
import com.reptar.fishreel.data.ImageCompressor
import com.reptar.fishreel.ui.ThemeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Account tab's content (see FeedScreen's selectedTab == 3) - profile photo, dark mode
 * toggle, legal links, sign out, and delete account. No Scaffold/TopAppBar of its own since
 * it's embedded directly in FeedScreen's; error messages surface through the snackbarHostState
 * FeedScreen already owns rather than a separate nested SnackbarHost.
 */
@Composable
fun ProfileContent(
    authViewModel: AuthViewModel,
    themeViewModel: ThemeViewModel,
    snackbarHostState: SnackbarHostState,
    scrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by authViewModel.currentUser.collectAsState()
    val isWorking by authViewModel.isWorking.collectAsState()
    val errorMessage by authViewModel.errorMessage.collectAsState()
    val needsReauthentication by authViewModel.needsReauthentication.collectAsState()
    val isDarkTheme by themeViewModel.isDarkTheme.collectAsState()

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var reauthPassword by remember { mutableStateOf("") }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            authViewModel.clearError()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selected ->
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        ImageCompressor.compress(context, selected)
                    }
                    authViewModel.updateProfilePhoto(bytes)
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Couldn't read that image")
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val photoUrl = user?.photoUrl?.toString()
            if (!photoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = photoUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(96.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isWorking) {
                CircularProgressIndicator()
            } else {
                TextButton(onClick = { launcher.launch("image/*") }) {
                    Text("Change photo")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(text = user?.displayName?.ifBlank { "Angler" } ?: "Angler", style = MaterialTheme.typography.titleMedium)
            user?.email?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.DarkMode, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "Dark Mode", modifier = Modifier.weight(1f))
                Switch(
                    checked = isDarkTheme,
                    onCheckedChange = { themeViewModel.setDarkTheme(it) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://fishreelapp.com/terms-of-use.html"))
                        )
                    } catch (_: Exception) {
                        // No browser available; fail silently rather than crash the screen.
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Terms of Use")
            }
            TextButton(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://fishreelapp.com/privacy-policy.html"))
                        )
                    } catch (_: Exception) {
                        // No browser available; fail silently rather than crash the screen.
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Privacy Policy")
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = { authViewModel.signOut() },
                enabled = !isWorking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign Out")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showDeleteConfirmDialog = true },
                enabled = !isWorking,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete Account")
            }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete your account?") },
            text = {
                Text("This permanently deletes your account, posts, comments, follows, and photos. This can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmDialog = false
                    authViewModel.deleteAccount()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (needsReauthentication) {
        AlertDialog(
            onDismissRequest = { /* Must confirm or cancel explicitly. */ },
            title = { Text("Confirm your password") },
            text = {
                Column {
                    Text("For your security, please re-enter your password to confirm account deletion.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = reauthPassword,
                        onValueChange = { reauthPassword = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = reauthPassword.isNotBlank(),
                    onClick = {
                        authViewModel.reauthenticateAndDelete(reauthPassword)
                        reauthPassword = ""
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    reauthPassword = ""
                    authViewModel.clearError()
                    authViewModel.clearNeedsReauthentication()
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}
