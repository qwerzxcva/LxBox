package com.leadaxe.aibox.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.leadaxe.aibox.R

/**
 * Floating bottom navigation.
 *
 * A rounded bar that hovers above the content instead of being welded to the
 * screen edge: the list scrolls underneath it, so the surface change reads as
 * depth rather than a hard cut. The selected item grows a soft pill behind
 * its icon and label, animated with a spring so switching tabs feels alive
 * (this is the AsteriskBOX / bilipai-style chrome the user asked for).
 *
 * The bar carries its own horizontal inset and the screen adds the matching
 * bottom padding, which keeps content reachable on gesture-navigation
 * devices without hard-coding window insets.
 */
@Composable
fun LxBottomBar(
    selected: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Collapse/expand by horizontal drag (user gesture spec): swipe the bar
    // left to fold it into a single floating orb (current page icon), swipe
    // the orb right to unfold. State survives recomposition.
    var collapsed by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    if (collapsed) {
        // Folded orb: one floating circle showing the current destination.
        // Drag it right to unfold; tap to unfold too (bigger target).
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 12.dp,
            modifier = modifier
                .padding(horizontal = 24.dp, vertical = 6.dp)
                .size(56.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { },
                        onDragEnd = { collapsed = false },
                        onDragCancel = { },
                    ) { _, _ -> }
                },
            onClick = { collapsed = false },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    selected.icon,
                    contentDescription = stringResource(selected.labelRes),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        return
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { },
                    onDragEnd = { },
                    onDragCancel = { },
                ) { change, dragAmount ->
                    if (dragAmount < -24f) collapsed = true  // swipe left folds
                    change.consume()
                }
            },
        shape = RoundedCornerShape(32.dp),
        // Frosted look: a much more translucent surface lets the scrolled
        // content bleed through (the "light-through" read), with a soft
        // shadow keeping the bar legible over any background.
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        shadowElevation = 16.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Destination.entries.forEach { dest ->
                FloatingBarItem(
                    destination = dest,
                    selected = dest == selected,
                    onClick = { onSelect(dest) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One tab: an animated pill holding the icon, plus its label. */
@Composable
private fun FloatingBarItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(destination.labelRes)
    val pillColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            Color.Transparent
        },
        animationSpec = spring(),
        label = "pill",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = spring(),
        label = "content",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "scale",
    )
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(pillColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icons only: six permanent labels crowd a phone-width bar, and
            // the icon set is unambiguous. The selected tab grows the pill.
            Icon(
                destination.icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier
                    .size(24.dp)
                    .scale(iconScale),
            )
        }
    }
}
