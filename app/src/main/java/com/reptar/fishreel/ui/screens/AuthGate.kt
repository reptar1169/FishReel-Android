package com.reptar.fishreel.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.reptar.fishreel.auth.AuthViewModel

/**
 * Signed-out entry point: a simple local toggle between Login and Register.
 * No NavController needed for just these two screens with no deep-linking needs.
 */
@Composable
fun AuthGate(authViewModel: AuthViewModel, isDarkTheme: Boolean) {
    var showRegister by rememberSaveable { mutableStateOf(false) }

    if (showRegister) {
        RegisterScreen(
            authViewModel = authViewModel,
            isDarkTheme = isDarkTheme,
            onSwitchToLogin = { showRegister = false }
        )
    } else {
        LoginScreen(
            authViewModel = authViewModel,
            isDarkTheme = isDarkTheme,
            onSwitchToRegister = { showRegister = true }
        )
    }
}
