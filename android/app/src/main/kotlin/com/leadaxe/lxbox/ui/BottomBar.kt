package com.leadaxe.lxbox.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.leadaxe.lxbox.ui.Destination

@Composable
fun LxBottomBar(
    selected: Destination,
    onSelect: (Destination) -> Unit,
) {
    NavigationBar {
        Destination.entries.forEach { dest ->
            NavigationBarItem(
                selected = dest == selected,
                onClick = { onSelect(dest) },
                icon = { Icon(dest.icon, contentDescription = dest.label) },
                label = { Text(dest.label) },
            )
        }
    }
}