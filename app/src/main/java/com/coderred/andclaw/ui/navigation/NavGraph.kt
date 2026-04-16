package com.coderred.andclaw.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coderred.andclaw.ui.screen.dashboard.DashboardScreen
import com.coderred.andclaw.ui.screen.onboarding.OnboardingScreen
import com.coderred.andclaw.ui.screen.settings.OpenClawConfigEditorScreen
import com.coderred.andclaw.ui.screen.settings.SettingsScreen
import com.coderred.andclaw.ui.screen.settings.SettingsViewModel
import com.coderred.andclaw.ui.screen.setup.SetupScreen

@Composable
fun AndClawNavGraph(
    navController: NavHostController,
    isSetupComplete: Boolean,
    isOnboardingComplete: Boolean,
    authCallbackUri: Uri? = null,
    openPairingRequestsOnLaunch: Boolean = false,
    onOpenPairingRequestsHandled: () -> Unit = {},
) {
    val targetRoute = when {
        !isSetupComplete -> Screen.Setup.route
        !isOnboardingComplete -> Screen.Onboarding.route
        else -> Screen.Dashboard.route
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(targetRoute, currentRoute) {
        val shouldRedirect = when (currentRoute) {
            Screen.Setup.route -> targetRoute != Screen.Setup.route
            Screen.Onboarding.route -> targetRoute != Screen.Onboarding.route
            else -> false
        }

        if (shouldRedirect && currentRoute != null) {
            navController.navigate(targetRoute) {
                popUpTo(currentRoute) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(openPairingRequestsOnLaunch, targetRoute, currentRoute) {
        if (!openPairingRequestsOnLaunch) return@LaunchedEffect
        if (targetRoute != Screen.Dashboard.route) return@LaunchedEffect
        if (currentRoute == null) return@LaunchedEffect

        if (currentRoute != Screen.Dashboard.route) {
            val poppedToDashboard = navController.popBackStack(Screen.Dashboard.route, inclusive = false)
            if (!poppedToDashboard) {
                navController.navigate(Screen.Dashboard.route) {
                    launchSingleTop = true
                }
            }
        }

        onOpenPairingRequestsHandled()
    }

    NavHost(
        navController = navController,
        startDestination = targetRoute,
    ) {
        composable(Screen.Setup.route) {
            SetupScreen()
        }

        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                authCallbackUri = authCallbackUri,
            )
        }

        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onNavigateToSettings = { provider, initialSection ->
                    navController.currentBackStackEntry?.savedStateHandle?.set("settings_api_provider", provider)
                    navController.currentBackStackEntry?.savedStateHandle?.set(
                        "settings_open_api_key_dialog",
                        provider != null,
                    )
                    navController.currentBackStackEntry?.savedStateHandle?.set(
                        "settings_initial_section",
                        initialSection,
                    )
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Settings.route) {
            val previousSavedStateHandle = navController.previousBackStackEntry?.savedStateHandle
            val initialApiProvider = previousSavedStateHandle?.remove<String>("settings_api_provider")
            val initialSection = previousSavedStateHandle?.remove<String>("settings_initial_section")
            val openApiKeyDialogOnLaunch =
                previousSavedStateHandle?.remove<Boolean>("settings_open_api_key_dialog") == true
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenClawConfigEditor = { navController.navigate(Screen.OpenClawConfigEditor.route) },
                initialApiProvider = initialApiProvider,
                initialSection = initialSection,
                openApiKeyDialogOnLaunch = openApiKeyDialogOnLaunch,
            )
        }

        composable(Screen.OpenClawConfigEditor.route) { editorBackStackEntry ->
            val settingsEntry = remember(editorBackStackEntry) {
                navController.getBackStackEntry(Screen.Settings.route)
            }
            val settingsViewModel: SettingsViewModel = viewModel(settingsEntry)
            OpenClawConfigEditorScreen(
                onBack = { navController.popBackStack() },
                onNavigateDashboard = {
                    val poppedToDashboard = navController.popBackStack(Screen.Dashboard.route, inclusive = false)
                    if (!poppedToDashboard) {
                        navController.navigate(Screen.Dashboard.route) {
                            launchSingleTop = true
                        }
                    }
                },
                viewModel = settingsViewModel,
            )
        }
    }
}
