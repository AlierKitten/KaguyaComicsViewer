package com.kaguya.comicsviewer.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kaguya.comicsviewer.R
import com.kaguya.comicsviewer.ui.library.LibraryScreen
import com.kaguya.comicsviewer.ui.library.RecentScreen
import com.kaguya.comicsviewer.ui.reader.ReaderScreen
import com.kaguya.comicsviewer.ui.settings.SettingsScreen
import com.kaguya.comicsviewer.ui.sources.SourcesScreen

sealed class Tab(val route: String, val label: Int, val icon: ImageVector) {
    data object Library : Tab("library", R.string.tab_library, Icons.Outlined.AutoStories)
    data object Recent : Tab("recent", R.string.tab_recent, Icons.Outlined.History)
    data object Sources : Tab("sources", R.string.tab_sources, Icons.Outlined.Folder)
    data object Settings : Tab("settings", R.string.tab_settings, Icons.Outlined.Settings)
}

@Composable
fun KaguyaApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()

    // 点击已选中的 Tab 时自增，用于通知对应页面滚动到顶部
    var scrollToTopSignal by remember { mutableLongStateOf(0L) }

    val tabRoutes = listOf(Tab.Library, Tab.Recent, Tab.Sources, Tab.Settings)
    val showBar = backStack?.destination?.route in tabRoutes.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabRoutes.forEach { tab ->
                        val selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (selected) {
                                    // 已在当前 Tab，触发滚动到顶部
                                    scrollToTopSignal++
                                } else {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        AppNavHost(nav, padding, scrollToTopSignal)
    }
}

@Composable
private fun AppNavHost(
    nav: androidx.navigation.NavHostController,
    padding: PaddingValues,
    scrollToTopSignal: Long
) {
    NavHost(
        navController = nav,
        startDestination = Tab.Library.route,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        composable(Tab.Library.route) { LibraryScreen(nav, scrollToTopSignal) }
        composable(Tab.Recent.route) { RecentScreen(nav, scrollToTopSignal) }
        composable(Tab.Sources.route) { SourcesScreen(nav) }
        composable(Tab.Settings.route) { SettingsScreen(nav) }
        composable(
            route = "reader/{comicId}",
            arguments = listOf(androidx.navigation.navArgument("comicId") { type = androidx.navigation.NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong("comicId") ?: 0L
            androidx.compose.runtime.key("reader_$id") {
                ReaderScreen(comicId = id, onBack = { nav.popBackStack() })
            }
        }
    }
}
