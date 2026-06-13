package com.deivid22srk.qwenbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.deivid22srk.qwenbridge.ui.screens.DashboardScreen
import com.deivid22srk.qwenbridge.ui.screens.LoginScreen
import com.deivid22srk.qwenbridge.ui.screens.ChatScreen
import com.deivid22srk.qwenbridge.ui.theme.*
import com.deivid22srk.qwenbridge.ui.viewmodel.ChatViewModel
import com.deivid22srk.qwenbridge.ui.viewmodel.ServerViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QwenBridgeTheme {
                val navController = rememberNavController()
                val serverViewModel: ServerViewModel = hiltViewModel()
                val chatViewModel: ChatViewModel = hiltViewModel()

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                Scaffold(
                    bottomBar = {
                        if (currentRoute != "login") {
                            NavigationBar(
                                containerColor = CardBackground,
                                contentColor = TextSecondary
                            ) {
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                                    label = { Text("Dashboard") },
                                    selected = currentRoute == "dashboard" || currentRoute == null,
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.White,
                                        selectedTextColor = Color.White,
                                        indicatorColor = PurplePrimary,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    ),
                                    onClick = {
                                        navController.navigate("dashboard") {
                                            popUpTo("dashboard") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Edit, contentDescription = "Chat") },
                                    label = { Text("Chat") },
                                    selected = currentRoute == "chat",
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.White,
                                        selectedTextColor = Color.White,
                                        indicatorColor = PurplePrimary,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    ),
                                    onClick = {
                                        navController.navigate("chat") {
                                            popUpTo("dashboard") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "dashboard",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("dashboard") {
                            DashboardScreen(
                                viewModel = serverViewModel,
                                onNavigateToLogin = { navController.navigate("login") }
                            )
                        }
                        composable("chat") {
                            ChatScreen(viewModel = chatViewModel)
                        }
                        composable("login") {
                            LoginScreen(
                                onLoginSuccess = { email, cookies, ua ->
                                    serverViewModel.saveAccount(email, cookies, ua)
                                    navController.popBackStack()
                                },
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
