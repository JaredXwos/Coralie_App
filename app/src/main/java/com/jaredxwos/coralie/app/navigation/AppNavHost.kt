package com.jaredxwos.coralie.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.jaredxwos.coralie.app.AppContainer
import com.jaredxwos.coralie.feature.editor.PageEditorMode
import com.jaredxwos.coralie.feature.editor.PageEditorScreen
import com.jaredxwos.coralie.feature.editor.PageEditorViewModel
import com.jaredxwos.coralie.feature.home.HomeScreen
import com.jaredxwos.coralie.feature.home.HomeViewModel
import com.jaredxwos.coralie.feature.settings.SettingsScreen
import com.jaredxwos.coralie.feature.settings.SettingsViewModel
import com.jaredxwos.coralie.feature.viewer.ViewerScreen
import com.jaredxwos.coralie.feature.viewer.ViewerViewModel

@Composable
fun AppNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController:
        NavHostController =
        rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
    ) {
        composable<HomeRoute> {
            val homeViewModel:
                HomeViewModel =
                viewModel(
                    factory =
                        HomeViewModel.factory(
                            container
                                .pageLibrary,
                        ),
                )

            HomeScreen(
                viewModel =
                    homeViewModel,
                onAddFileClicked = {
                    navController.navigate(
                        AddFileRoute,
                    )
                },
                onOpenFileClicked = { assetId ->
                    navController.navigate(
                        ViewerRoute(
                            assetId = assetId,
                        ),
                    )
                },
                onEditFileClicked = { assetId ->
                    navController.navigate(
                        EditFileRoute(
                            assetId = assetId,
                        ),
                    )
                },
                onSettingsClicked = {
                    navController.navigate(
                        SettingsRoute,
                    )
                },
            )
        }

        composable<AddFileRoute> {
            val editorViewModel:
                PageEditorViewModel =
                viewModel(
                    factory =
                        PageEditorViewModel
                            .factory(
                                mode =
                                    PageEditorMode
                                        .Create,
                                pageLibrary =
                                    container
                                        .pageLibrary,
                            ),
                )

            PageEditorScreen(
                viewModel =
                    editorViewModel,
                onBack = {
                    navController
                        .popBackStack()
                },
                onFileAdded = {
                    navController
                        .popBackStack()
                },
            )
        }

        composable<EditFileRoute> { backStackEntry ->
            val route:
                EditFileRoute =
                backStackEntry.toRoute()

            val editorViewModel:
                PageEditorViewModel =
                viewModel(
                    factory =
                        PageEditorViewModel
                            .factory(
                                mode =
                                    PageEditorMode
                                        .Edit(
                                            route
                                                .assetId,
                                        ),
                                pageLibrary =
                                    container
                                        .pageLibrary,
                            ),
                )

            PageEditorScreen(
                viewModel =
                    editorViewModel,
                onBack = {
                    navController
                        .popBackStack()
                },
                onFileAdded = {
                    navController
                        .popBackStack()
                },
            )
        }

        composable<ViewerRoute> { backStackEntry ->
            val route:
                ViewerRoute =
                backStackEntry.toRoute()

            val viewerViewModel:
                ViewerViewModel =
                viewModel(
                    factory =
                        ViewerViewModel
                            .factory(
                                assetId =
                                    route.assetId,
                                pageLibrary =
                                    container
                                        .pageLibrary,
                                sessionFactory =
                                    container
                                        .viewerSessionFactory,
                            ),
                )

            ViewerScreen(
                viewModel =
                    viewerViewModel,
                onBack = {
                    navController
                        .popBackStack()
                },
                onSettings = {
                    navController.navigate(
                        SettingsRoute,
                    )
                },
            )
        }

        composable<SettingsRoute> {
            val settingsViewModel:
                SettingsViewModel =
                viewModel(
                    factory =
                        SettingsViewModel
                            .factory(
                                pageLibrary =
                                    container
                                        .pageLibrary,
                                domainPermissionStore =
                                    container
                                        .domainPermissionStore,
                            ),
                )

            SettingsScreen(
                viewModel =
                    settingsViewModel,
                onBackClicked = {
                    navController
                        .popBackStack()
                },
            )
        }
    }
}
