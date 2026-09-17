package com.leadaxe.aibox.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.leadaxe.aibox.AIBoxApp
import kotlinx.coroutines.launch
import com.leadaxe.aibox.engine.vpn.BoxController
import com.leadaxe.aibox.ui.Destination
import com.leadaxe.aibox.ui.LxBottomBar
import com.leadaxe.aibox.ui.LocalizedApp
import com.leadaxe.aibox.ui.screens.ConnectionsScreen
import com.leadaxe.aibox.ui.screens.DnsScreen
import com.leadaxe.aibox.ui.screens.HomeScreen
import com.leadaxe.aibox.ui.screens.RoutesScreen
import com.leadaxe.aibox.ui.screens.SettingsScreen
import com.leadaxe.aibox.ui.screens.SubscriptionsScreen
import com.leadaxe.aibox.ui.theme.LxTheme

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
        val app = application as AIBoxApp
        controller = BoxController(this, app.appStateStore)
        // Start cross-process state syncing before the first frame so the
        // Home dial reflects a tunnel that was already running.
        app.vpnRelay.startListening()
        // Privacy gate (rsxm-security verdict): hide engine surfaces from
        // the task switcher and screenshots while the user asks for it.
        lifecycleScope.launch {
            app.appStateStore.state.collect { st ->
                window?.let { w ->
                    if (st.blockScreenshots) {
                        w.setFlags(
                            android.view.WindowManager.LayoutParams.FLAG_SECURE,
                            android.view.WindowManager.LayoutParams.FLAG_SECURE,
                        )
                    } else {
                        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
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

    override fun onDestroy() {
        // The relay is an app-lifetime singleton, but its receiver only has
        // work while an activity can display the flows: stop the broadcast
        // dispatch while nothing is attached (startListening re-registers).
        (application as AIBoxApp).vpnRelay.stopListening()
        super.onDestroy()
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RootScaffold(
    controller: BoxController,
    onRequestVpnConsent: (Intent) -> Unit,
) {
    // HorizontalPager drives the tabs: left/right swipes move between
    // screens exactly like the bottom bar taps do (bilipai-style gesture
    // navigation). beyondBoundsPageCount keeps neighbours alive so a swipe
    // does not blank the screen, and saveState/restoreState semantics come
    // free from rememberSaveable inside each screen.
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = Destination.Home.ordinal,
        pageCount = { Destination.entries.size },
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Second-level pages (rule editors etc.) lock horizontal swiping so a
    // drag inside a text field never throws the user into another tab.
    var pagerLocked by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !pagerLocked,
        ) { page ->
            // Reserve room at the bottom so lists can scroll their last
            // item clear of the floating bar (bar height ≈ 64dp + lift).
            Column(modifier = Modifier.fillMaxSize().padding(bottom = 96.dp)) {
            when (Destination.entries[page]) {
                Destination.Home -> HomeScreen(controller = controller, onRequestVpnConsent = onRequestVpnConsent)
                Destination.Subscriptions -> SubscriptionsScreen()
                Destination.Connections -> {
                    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as AIBoxApp
                    ConnectionsScreen(relay = app.vpnRelay)
                }
                Destination.Routes -> RoutesScreen(onEditorLock = { pagerLocked = it })
                Destination.Dns -> DnsScreen(onEditorLock = { pagerLocked = it })
                Destination.Settings -> SettingsScreen()
            }
            }
        }

        // True floating bar: overlaid on the pager (not docked in a
        // Scaffold slot), lifted off the very bottom by the navigation-bar
        // inset plus a margin, so it reads as a layer above the content.
        LxBottomBar(
            selected = Destination.entries[pagerState.currentPage],
            onSelect = { dest ->
                scope.launch {
                    pagerState.animateScrollToPage(dest.ordinal)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    bottom = androidx.compose.foundation.layout.WindowInsets
                        .navigationBars
                        .asPaddingValues()
                        .calculateBottomPadding() + 12.dp,
                ),
        )
    }
}