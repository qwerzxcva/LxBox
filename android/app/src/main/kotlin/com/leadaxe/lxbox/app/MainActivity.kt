package com.leadaxe.lxbox.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.leadaxe.lxbox.LxBoxApp
import com.leadaxe.lxbox.engine.vpn.BoxController
import com.leadaxe.lxbox.ui.Destination
import com.leadaxe.lxbox.ui.LxBottomBar
import com.leadaxe.lxbox.ui.LocalizedApp
import com.leadaxe.lxbox.ui.screens.DnsScreen
import com.leadaxe.lxbox.ui.screens.HomeScreen
import com.leadaxe.lxbox.ui.screens.RoutesScreen
import com.leadaxe.lxbox.ui.screens.SettingsScreen
import com.leadaxe.lxbox.ui.screens.SubscriptionsScreen
import com.leadaxe.lxbox.ui.theme.LxTheme

class MainActivity : ComponentActivity() {

    private lateinit var controller: BoxController

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            controller.startFromBackground()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LxBoxApp
        controller = BoxController(this, app.appStateStore)
        setContent {
            val state by app.appStateStore.state.collectAsState()
            LocalizedApp(language = state.language) {
                LxTheme(colorMode = state.colorMode) {
                    RootScaffold(
                        controller = controller,
                        onRequestVpnConsent = { intent -> vpnLauncher.launch(intent) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RootScaffold(
    controller: BoxController,
    onRequestVpnConsent: (Intent) -> Unit,
) {
    val nav: NavHostController = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val selected = Destination.entries.firstOrNull { dest ->
        current?.hierarchy?.any { it.route == dest.name } == true
    } ?: Destination.Home

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            LxBottomBar(
                selected = selected,
                onSelect = { dest ->
                    nav.navigate(dest.name) {
                        popUpTo(Destination.Home.name) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = nav, startDestination = Destination.Home.name) {
                composable(Destination.Home.name) {
                    HomeScreen(controller = controller, onRequestVpnConsent = onRequestVpnConsent)
                }
                composable(Destination.Subscriptions.name) { SubscriptionsScreen() }
                composable(Destination.Routes.name) { RoutesScreen() }
                composable(Destination.Dns.name) { DnsScreen() }
                composable(Destination.Settings.name) { SettingsScreen() }
            }
        }
    }
}