package com.leadaxe.lxbox.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.ui.graphics.vector.ImageVector

/** Top-level destinations the bottom bar can navigate to. */
enum class Destination(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Outlined.Home),
    Subscriptions("Subscriptions", Icons.Outlined.Subscriptions),
    Routes("Routes", Icons.Outlined.Route),
    Dns("DNS", Icons.Outlined.Dns),
    Settings("Settings", Icons.Outlined.Settings),
}