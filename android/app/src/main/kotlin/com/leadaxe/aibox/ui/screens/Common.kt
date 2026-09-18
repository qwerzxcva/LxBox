package com.leadaxe.aibox.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leadaxe.aibox.R

/** Reusable header used at the top of every scrollable screen. */
@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/**
 * Collapsible section card used by the list-style screens (Groups, DNS,
 * Settings). The whole header row is the tap target, a trailing count and
 * chevron show the state; the body animates its height so expanding does
 * not jump the list.
 */
@Composable
fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    count: Int? = null,
    subtitle: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (count != null) {
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Scrollable form body for long editors.
 *
 * `AlertDialog` puts its `text` slot in a fixed-height box with no scroll;
 * anything past the viewport is simply unreachable (the clipped lower fields
 * also render on top of the buttons, which is the overlap that showed up in
 * the rule editor). Wrapping the form in this column gives it a bounded
 * height *and* a scrollbar, so every field stays reachable.
 */
@Composable
fun FormBody(scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    // When [scroll] is true the form lives in a bounded dialog: cap the
    // height and give it a scrollbar. When false the caller already scrolls
    // (an inline card inside a LazyColumn): measuring a verticalScroll under
    // the LazyColumn's infinite height constraint throws, so we must NOT add
    // another scrollable — the column just lays out at its natural height.
    val modifier = if (scroll) {
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .verticalScroll(rememberScrollState())
    } else {
        Modifier.fillMaxWidth()
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * Comma-separated list field. The existing rule editors store list-shaped
 * matchers as `List<String>` — this widget accepts "a, b, c" text and
 * hands the parsed list back to the caller.
 *
 * [supporting] renders as a hint line under the field; the rule editor
 * uses it to spell out what each matcher actually matches, which is the
 * difference between a field someone uses and one they scroll past.
 */
@Composable
fun ListField(
    label: String,
    values: List<String>,
    onValuesChange: (List<String>) -> Unit,
    placeholder: String = "",
    supporting: String = "",
    modifier: Modifier = Modifier,
    collapsible: Boolean = false,
) {
    // One entry per line. The raw text is kept locally while typing —
    // normalising on every keystroke (filtering blank lines) would eat the
    // newline the user just typed and yank the cursor back, making
    // multi-line input impossible. The list is pushed back when the field
    // loses focus, or when the content was changed externally.
    var text by remember { mutableStateOf(values.joinToString("\n")) }
    var lastExternal by remember { mutableStateOf(values.joinToString("\n")) }
    if (values.joinToString("\n") != lastExternal) {
        // External change (e.g. the editor saved and rebuilt the state):
        // adopt it wholesale.
        lastExternal = values.joinToString("\n")
        text = lastExternal
    }
    // Collapsible (user request): long matchers (a dozen IP CIDRs, a full
    // package list) stretched every card forever. The header row shows the
    // label with an entry count and a chevron; the field itself unfolds
    // below it. Starts collapsed when empty, expanded when there is
    // something to show — so a fresh rule reads compact, a filled one never
    // hides its content by surprise.
    var open by remember(values.isNotEmpty()) { mutableStateOf(values.isNotEmpty()) }
    val body: @Composable () -> Unit = {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            placeholder = placeholder.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { st ->
                    if (!st.isFocused) {
                        val parsed = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                        if (parsed != values) onValuesChange(parsed)
                    }
                },
        )
        if (supporting.isNotEmpty()) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
    }
    if (!collapsible) {
        Column(modifier = modifier.fillMaxWidth()) { body() }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .padding(vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (values.isNotEmpty()) {
                    Text(
                        values.size.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                Icon(
                    if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.animation.AnimatedVisibility(visible = open) { body() }
        }
    }
}

/** Outlined text field bound to a String value with a label. */
@Composable
fun StringField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    supporting: String = "",
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = 1,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
            minLines = minLines,
            maxLines = maxLines,
            modifier = Modifier.fillMaxWidth(),
        )
        if (supporting.isNotEmpty()) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
    }
}

/** Single-choice chip row: pick exactly one value. */
@Composable
fun SingleChoiceChips(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    display: @Composable (String) -> String = { it },
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(display(option)) },
                )
            }
        }
    }
}

/** Multi-choice chip row: toggle each value in/out of the selection. */
@Composable
fun MultiChoiceChips(
    label: String,
    options: List<String>,
    selected: Collection<String>,
    onToggle: (String) -> Unit,
    display: @Composable (String) -> String = { it },
    selectAllAction: (() -> Unit)? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (selectAllAction != null && options.isNotEmpty()) {
                TextButton(onClick = selectAllAction) {
                    Text(
                        if (selected.containsAll(options)) stringResource(R.string.common_select_none)
                        else stringResource(R.string.common_select_all),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option in selected,
                    onClick = { onToggle(option) },
                    label = { Text(display(option)) },
                )
            }
        }
    }
}

/** Switch with a label (and optional supporting text) laid out on one row. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String = "",
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (supporting.isNotEmpty()) {
                Text(supporting, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}