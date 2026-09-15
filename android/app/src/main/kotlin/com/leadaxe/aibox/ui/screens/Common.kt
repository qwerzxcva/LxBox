package com.leadaxe.aibox.ui.screens

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp

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
 * Scrollable form body for long editors.
 *
 * `AlertDialog` puts its `text` slot in a fixed-height box with no scroll;
 * anything past the viewport is simply unreachable (the clipped lower fields
 * also render on top of the buttons, which is the overlap that showed up in
 * the rule editor). Wrapping the form in this column gives it a bounded
 * height *and* a scrollbar, so every field stays reachable.
 */
@Composable
fun FormBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .verticalScroll(rememberScrollState()),
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
    Column(modifier = modifier.fillMaxWidth()) {
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