package com.xinchen.echomind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xinchen.echomind.ui.nav.Destinations
import com.xinchen.echomind.ui.player.PlayerScreen
import com.xinchen.echomind.ui.shelf.ShelfScreen
import com.xinchen.echomind.ui.stats.StatsScreen
import com.xinchen.echomind.ui.theme.EchoMindTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EchoMindTheme {
                RootScreen()
            }
        }
    }
}

private data class Tab(val route: String, val label: String)

private val tabs = listOf(
    Tab(Destinations.SHELF, "书架"),
    Tab(Destinations.TRANSCRIPT, "转录"),
    Tab(Destinations.STATS, "统计"),
    Tab(Destinations.SETTINGS, "设置"),
)

@Composable
private fun RootScreen() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        alwaysShowLabel = false,
                        icon = { Text(tab.label.first().toString()) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destinations.SHELF,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destinations.SHELF) { ShelfScreen(onOpenPlayer = { audioId -> navController.navigate(Destinations.player(audioId)) }) }
            composable(Destinations.TRANSCRIPT) { Placeholder("转录任务", "Phase 6") }
            composable(Destinations.STATS) { StatsScreen() }
            composable(Destinations.SETTINGS) { Placeholder("设置", "Phase 7") }
            composable(
                route = Destinations.PLAYER,
                arguments = listOf(navArgument("audioId") { type = NavType.StringType }),
            ) { entry ->
                PlayerScreen(
                    audioId = entry.arguments?.getString("audioId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/** 各 Phase 落地前如实标注未实现，避免用假界面冒充功能 */
@Composable
private fun Placeholder(title: String, phase: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            "该页面将在 $phase 实现",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
