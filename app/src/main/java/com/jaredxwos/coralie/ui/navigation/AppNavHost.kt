package com.jaredxwos.coralie.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.jaredxwos.coralie.ui.composable.screens.AddFileScreen
import com.jaredxwos.coralie.ui.composable.screens.HomeScreen
import com.jaredxwos.coralie.ui.composable.screens.SettingsScreen
import com.jaredxwos.coralie.ui.composable.screens.ViewerScreen
import com.jaredxwos.coralie.viewModel.storage.LiveStorageViewModel

@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = HomeRoute, modifier = modifier) {
        composable<HomeRoute> {
            HomeScreen(
                viewModel = LiveStorageViewModel,
                onAddFileClicked = { navController.navigate(AddFileRoute) },
                onOpenFileClicked = { config -> navController.navigate(ViewerRoute(
                    assetId = config.assetId,
                    spaceId = config.spaceId,
                    name = config.name,
                )) },
                onEditFileClicked = { config, uri -> navController.navigate(EditFileRoute(
                    assetId = config.assetId,
                    spaceId = config.spaceId,
                    existingName = config.name,
                    sourceUri = uri,
                )) },
            )
        }
        composable<AddFileRoute> {
            AddFileScreen(
                viewModel = LiveStorageViewModel,
                onBack = { navController.popBackStack() },
                onFileAdded = { navController.popBackStack() },
            )
        }
        composable<EditFileRoute> { backStackEntry ->
            val route: EditFileRoute = backStackEntry.toRoute()
            AddFileScreen(
                viewModel = LiveStorageViewModel,
                onBack = { navController.popBackStack() },
                onFileAdded = { navController.popBackStack() },
                initialName = route.existingName,
                initialSpaceId = route.spaceId,
                initialUri = route.sourceUri.toUri()
            )
        }
        composable<ViewerRoute> { backStackEntry ->
            val route: ViewerRoute = backStackEntry.toRoute()
            ViewerScreen(
                assetId = route.assetId,
                spaceId = route.spaceId,
                name = route.name,
                onBack = { navController.popBackStack() },
                onSettings = { },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                viewModel = LiveStorageViewModel,
                onBackClicked = { navController.popBackStack() }
            )
        }
    }
}