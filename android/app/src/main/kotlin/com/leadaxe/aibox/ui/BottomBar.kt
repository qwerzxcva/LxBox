package com.leadaxe.aibox.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.leadaxe.aibox.R

@Composable
fun LxBottomBar(
    selected: Destination,
    onSelect: (Destination) -> Unit,
) {
    NavigationBar {
        Destination.entries.forEach { dest ->
            val label = stringResource(dest.labelRes)
            NavigationBarItem(
                selected = dest == selected,
                onClick = { onSelect(dest) },
                icon = { Icon(dest.icon, contentDescription = label) },
                label = { Text(label) },
            )
        }
    }
}