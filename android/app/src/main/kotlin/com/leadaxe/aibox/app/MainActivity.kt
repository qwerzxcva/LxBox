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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
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
        // "Blue sky / white cloud" canvas (theme doc promises it; the plain
        // background colour alone read as near-white). A vertical sky
        // gradient plus a few soft cloud puffs behind the frosted cards.
        SkyBackdrop()
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !pagerLocked,
        ) { page ->
            // Reserve room at the bottom so lists can scroll their last
            // item clear of the floating bar (bar height ≈ 64dp + lift).
            Column(modifier = Modifier.fillMaxSize().padding(bottom = 96.dp)) {
            // Page-title capsule: every page opens with its name in a
            // rounded pill, centered (user request).
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Text(
                        stringResource(Destination.entries[page].labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    )
                }
            }
            when (Destination.entries[page]) {
                Destination.Home -> HomeScreen(controller = controller, onRequestVpnConsent = onRequestVpnConsent)
                Destination.Subscriptions -> SubscriptionsScreen(onEditorLock = { pagerLocked = it })
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

        // Back gate (user report): with a second-level editor open the
        // back gesture went straight through the Activity and killed the
        // app. When the pager is locked an editor is on top — swallow the
        // back press; the editor's own close button (or its BackHandler if
        // it adds one) is the exit path.
        androidx.activity.compose.BackHandler(enabled = pagerLocked) { }

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
/**
 * The "blue sky / white cloud" backdrop the theme documentation promises.
 * Drawn as the bottom layer of the root Box, behind every frosted card.
 *
 * Light mode: daylight sky — a cyan-to-pale-blue vertical gradient with
 * soft white cloud puffs. Dark mode: deep twilight — navy gradient with
 * faint moon-lit clouds (never pure black, matching the SeedDark palette).
 * Pure Canvas, no assets, so it costs nothing to render and scales to any
 * screen without a bitmap.
 */
@Composable
private fun SkyBackdrop() {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val topColor = if (dark) androidx.compose.ui.graphics.Color(0xFF0A1E33)
    else androidx.compose.ui.graphics.Color(0xFF7EC4F2)
    val midColor = if (dark) androidx.compose.ui.graphics.Color(0xFF12314F)
    else androidx.compose.ui.graphics.Color(0xFFAEDCFA)
    val bottomColor = if (dark) androidx.compose.ui.graphics.Color(0xFF1A3E60)
    else androidx.compose.ui.graphics.Color(0xFFE4F4FF)
    val cloudColor = if (dark) androidx.compose.ui.graphics.Color(0xFF2C4A66).copy(alpha = 0.55f)
    else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f)
    val cloudShade = if (dark) androidx.compose.ui.graphics.Color(0xFF24405A).copy(alpha = 0.5f)
    else androidx.compose.ui.graphics.Color(0xFFF0F8FF).copy(alpha = 0.85f)

    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        // Sky gradient.
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                colors = listOf(topColor, midColor, bottomColor),
            ),
        )
        // Cloud puffs: each cloud is 3 overlapping circles (two bright lobes
        // + one shaded base) for a soft, rounded silhouette. Positions are
        // fractions of the canvas so they sit naturally on any aspect ratio.
        fun cloud(cx: Float, cy: Float, r: Float) {
            drawCircle(cloudShade, radius = r * 1.05f, center = androidx.compose.ui.geometry.Offset(cx + r * 0.55f, cy + r * 0.18f))
            drawCircle(cloudColor, radius = r, center = androidx.compose.ui.geometry.Offset(cx, cy))
            drawCircle(cloudColor, radius = r * 0.78f, center = androidx.compose.ui.geometry.Offset(cx + r * 1.1f, cy + r * 0.12f))
            drawCircle(cloudColor, radius = r * 0.62f, center = androidx.compose.ui.geometry.Offset(cx - r * 0.9f, cy + r * 0.22f))
        }
        val w = size.width
        val h = size.height
        // Cloud radii as a fraction of width, so they scale with the device.
        cloud(w * 0.22f, h * 0.12f, w * 0.11f)
        cloud(w * 0.78f, h * 0.22f, w * 0.085f)
        cloud(w * 0.45f, h * 0.34f, w * 0.07f)
    }
}
