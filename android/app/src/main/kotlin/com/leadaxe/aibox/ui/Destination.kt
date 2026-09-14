package com.leadaxe.aibox.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.ui.graphics.vector.ImageVector
import com.leadaxe.aibox.R

/** Top-level destinations the bottom bar can navigate to. */
enum class Destination(val labelRes: Int, val icon: ImageVector) {
    Home(R.string.nav_home, Icons.Outlined.Home),
    Subscriptions(R.string.nav_subscriptions, Icons.Outlined.Subscriptions),
    Routes(R.string.nav_routes, Icons.Outlined.Route),
    Dns(R.string.nav_dns, Icons.Outlined.Dns),
    Settings(R.string.nav_settings, Icons.Outlined.Settings),
}