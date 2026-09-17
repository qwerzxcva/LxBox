package com.leadaxe.aibox.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Swipe-to-delete wrapper with a two-step confirm (user request): swipe the
 * card left to reveal a red delete button, tap it once to arm (the label
 * changes to 确定), tap again to actually delete. Any swipe back disarms.
 * This kills accidental deletions without a dialog.
 */
@Composable
fun <T> SwipeToDeleteRow(
    item: T,
    key: Any,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    var offsetX by remember(key) { mutableStateOf(0f) }
    var armed by rememberSaveable(key) { mutableStateOf(false) }
    val density = LocalDensity.current
    val revealPx = with(density) { 96.dp.toPx() }

    val bgAlpha by animateFloatAsState(if (armed) 1f else 0.9f, label = "bg")
    val shift by animateFloatAsState(
        targetValue = if (offsetX != 0f) offsetX else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "shift",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium),
    ) {
        // Red delete backdrop.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.error.copy(alpha = bgAlpha))
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (armed) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = onDelete,
                    modifier = Modifier.size(width = 96.dp, height = 40.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(Icons.Outlined.Done, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  确认")
                    }
                }
            } else {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "delete",
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        }
        // The card, sliding left over the backdrop.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = shift }
                .pointerInput(key) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            val next = (offsetX + amount).coerceIn(-revealPx * 1.4f, 0f)
                            offsetX = next
                            armed = abs(offsetX) >= revealPx * 0.6f
                        },
                        onDragEnd = {
                            offsetX = if (armed) -revealPx else 0f
                        },
                        onDragCancel = { offsetX = 0f },
                    )
                }
                .animateContentSize(),
        ) {
            content()
        }
    }
}
