package me.mupu.sleeptalkrecorder.ui.tags

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.data.EventKindEntity
import me.mupu.sleeptalkrecorder.data.EventTag
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.TagEntity
import me.mupu.sleeptalkrecorder.data.TagType
import me.mupu.sleeptalkrecorder.data.Tags
import me.mupu.sleeptalkrecorder.data.WaysFound
import me.mupu.sleeptalkrecorder.detection.FoundBy
import me.mupu.sleeptalkrecorder.ui.sleep.kindName

/** The name of [tag] in the app language: the user's name, or the app's for Favourite. */
@Composable
internal fun tagName(tag: TagEntity): String = tag.name ?: stringResource(R.string.tag_favourite)

/** The name of [tag] in the app language, with the user's [tags] and the event [kinds] it may refer to. */
@Composable
internal fun tagName(tag: EventTag, tags: List<TagEntity>, kinds: List<EventKindEntity>): String = when (tag) {
    is EventTag.Stored -> tags.firstOrNull { it.id == tag.id }?.let { tagName(it) }.orEmpty()
    is EventTag.System.Kind -> kindName(tag.kindId, kinds.firstOrNull { it.id == tag.kindId }?.name)
    EventTag.System.Named -> stringResource(R.string.tag_named)
    EventTag.System.Hidden -> stringResource(R.string.tag_hidden)
    EventTag.System.Background -> stringResource(R.string.tag_background)
    EventTag.System.Unsure -> stringResource(R.string.tag_unsure)
    is EventTag.System.Found -> stringResource(
        when (tag.by) {
            FoundBy.LOUDNESS -> R.string.tag_found_loudness
            FoundBy.LISTENING -> R.string.tag_found_listening
            FoundBy.BOTH -> R.string.tag_found_both
        },
    )
    is EventTag.System.Ways -> stringResource(
        when (tag.found) {
            WaysFound.BOTH -> R.string.tag_ways_both
            WaysFound.ONLY_OLD -> R.string.tag_ways_only_old
            WaysFound.ONLY_NEW -> R.string.tag_ways_only_new
        },
    )
}

/**
 * The filter of an event list: "All", selected while no tag is chosen, which takes every chosen tag
 * out again; the button that opens the filter window; and the chosen tags as pills after them that
 * wrap onto further lines, where tapping a pill takes its tag out of the filter. System tags have
 * the tertiary colour, which sets them apart from the user's tags.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagFilterBar(
    filter: TagFilter,
    tags: List<TagEntity>,
    kinds: List<EventKindEntity>,
    onOpen: () -> Unit,
    onRemove: (EventTag) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    /** The system tags offered beyond the kinds, named and hidden. */
    extraTags: List<EventTag.System> = emptyList(),
) {
    val colors = MaterialTheme.colorScheme
    FlowRow(modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = filter.chosen.isEmpty(),
            onClick = onClear,
            label = { Text(stringResource(R.string.tag_filter_all)) },
        )
        AssistChip(
            onClick = onOpen,
            label = { Text(stringResource(R.string.tag_filter)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_filter_list), contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        val offered = filterTags(tags, kinds, extraTags)
        // In the order the window lists them; a chosen tag the window no longer offers comes last.
        val chosen = offered.filter { it in filter.chosen } + filter.chosen.filterNot { it in offered }
        for (tag in chosen) {
            val name = tagName(tag, tags, kinds)
            val remove = stringResource(R.string.tag_filter_remove, name)
            val system = tag is EventTag.System
            InputChip(
                selected = true,
                onClick = { onRemove(tag) },
                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = if (tag == EventTag.Stored(Tags.FAVOURITE)) {
                    { Icon(painterResource(R.drawable.ic_star), contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
                trailingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(18.dp)) },
                colors = if (system) {
                    InputChipDefaults.inputChipColors(
                        selectedContainerColor = colors.tertiaryContainer,
                        selectedLabelColor = colors.onTertiaryContainer,
                        selectedLeadingIconColor = colors.onTertiaryContainer,
                        selectedTrailingIconColor = colors.onTertiaryContainer,
                    )
                } else {
                    InputChipDefaults.inputChipColors()
                },
                modifier = Modifier.semantics { contentDescription = remove },
            )
        }
    }
}

/**
 * The tag dialogs of a screen: the tag window of [editing], the filter window while [filterOpen],
 * and the questions for a new tag and before an own tag is deleted, which both windows offer.
 */
@Composable
fun TagDialogs(
    editing: TagEditing,
    kinds: List<EventKindEntity>,
    filter: TagFilter,
    filterOpen: Boolean,
    /** How many events of the list the tag alone would show, or null to show no counts. */
    count: ((EventTag) -> Int)?,
    onToggleFilter: (EventTag) -> Unit,
    onClearFilter: () -> Unit,
    onCloseFilter: () -> Unit,
    /** Runs once the tag window's choice is stored, as for ending a selection. */
    onApplied: () -> Unit = {},
    /** The system tags the filter window offers beyond the kinds, named and hidden. */
    extraTags: List<EventTag.System> = emptyList(),
) {
    val tags by editing.tags.collectAsStateWithLifecycle()
    val window by editing.state.collectAsStateWithLifecycle()
    val deletion by editing.deletion.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }

    if (filterOpen) {
        TagFilterWindow(
            filter, tags, kinds, extraTags, count,
            onToggle = onToggleFilter,
            onClear = onClearFilter,
            onNewTag = { creating = true },
            onDelete = editing::askToDelete,
            onDismiss = onCloseFilter,
        )
    }
    window?.let { open ->
        TagWindow(
            open, tags,
            onTap = editing::tap,
            onNewTag = { creating = true },
            onDelete = editing::askToDelete,
            onConfirm = { editing.confirm(onApplied) },
            onDismiss = editing::close,
        )
    }
    if (creating) NewTagDialog(tags, onCreate = editing::create, onDismiss = { creating = false })
    deletion?.let { DeleteTagDialog(it, onConfirm = editing::confirmDeletion, onDismiss = editing::cancelDeletion) }
}

/**
 * The filter window: every tag with a checkbox, "My tags" (Favourite and the user's own, and a
 * new one) open, "Automatic" (the system tags, in the tertiary colour) closed unless one of them
 * is chosen. Each change applies at once.
 */
@Composable
private fun TagFilterWindow(
    filter: TagFilter,
    tags: List<TagEntity>,
    kinds: List<EventKindEntity>,
    extraTags: List<EventTag.System>,
    count: ((EventTag) -> Int)?,
    onToggle: (EventTag) -> Unit,
    onClear: () -> Unit,
    onNewTag: () -> Unit,
    onDelete: (TagEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var mineOpen by rememberSaveable { mutableStateOf(true) }
    var automaticOpen by rememberSaveable { mutableStateOf(filter.chosen.any { it is EventTag.System }) }
    val tertiary = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.tertiary)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tag_filter_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.tag_filter_hint), style = MaterialTheme.typography.bodySmall)
                SectionHeader(stringResource(R.string.tags_mine), mineOpen, MaterialTheme.colorScheme.primary) { mineOpen = !mineOpen }
                if (mineOpen) {
                    for (tag in tags) {
                        val key = EventTag.Stored(tag.id)
                        TagRow(
                            tagName(tag),
                            favourite = tag.type == TagType.FAVOURITE,
                            starSlot = true,
                            count = count?.invoke(key),
                            onClick = { onToggle(key) },
                            onDelete = if (tag.type == TagType.OWN) ({ onDelete(tag) }) else null,
                        ) { Checkbox(checked = key in filter.chosen, onCheckedChange = null) }
                    }
                    NewTagRow(onNewTag)
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SectionHeader(stringResource(R.string.tags_automatic), automaticOpen, MaterialTheme.colorScheme.tertiary) {
                    automaticOpen = !automaticOpen
                }
                if (automaticOpen) {
                    for (tag in systemTags(kinds, extraTags)) {
                        TagRow(tagName(tag, tags, kinds), count = count?.invoke(tag), onClick = { onToggle(tag) }) {
                            Checkbox(checked = tag in filter.chosen, onCheckedChange = null, colors = tertiary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            TextButton(onClick = onClear, enabled = filter.chosen.isNotEmpty()) { Text(stringResource(R.string.tag_filter_clear)) }
        },
    )
}

/**
 * The tag window of one event or several: every assignable tag with its state on them, ticked,
 * empty or a dash for some, and a new tag. OK applies the choice.
 */
@Composable
private fun TagWindow(
    state: TagWindowState,
    tags: List<TagEntity>,
    onTap: (Long) -> Unit,
    onNewTag: () -> Unit,
    onDelete: (TagEntity) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = state.events.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.tags_title, count, count)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.tags_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.heightIn(min = 4.dp))
                for (tag in tags) {
                    TagRow(
                        tagName(tag),
                        favourite = tag.type == TagType.FAVOURITE,
                        starSlot = true,
                        onClick = { onTap(tag.id) },
                        onDelete = if (tag.type == TagType.OWN) ({ onDelete(tag) }) else null,
                    ) {
                        TriStateCheckbox(
                            state = when (state.choices.stateOf(tag.id)) {
                                TagState.ON -> ToggleableState.On
                                TagState.OFF -> ToggleableState.Off
                                TagState.MIXED -> ToggleableState.Indeterminate
                            },
                            onClick = null,
                        )
                    }
                }
                NewTagRow(onNewTag)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The heading of a group of tags in the filter window, which opens and closes the group. */
@Composable
private fun SectionHeader(title: String, open: Boolean, color: Color, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onToggle),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.weight(1f))
        Icon(
            painterResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
            contentDescription = null,
            tint = color,
        )
    }
}

/**
 * One tag in a window: its [checkbox], its name, how many events it lists and, for an own tag, a
 * delete button. The user's tags keep a [starSlot] before the name, so their names line up with
 * Favourite's, which has its star there.
 */
@Composable
private fun TagRow(
    name: String,
    favourite: Boolean = false,
    starSlot: Boolean = false,
    count: Int? = null,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    checkbox: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick),
    ) {
        checkbox()
        if (favourite) {
            Icon(
                painterResource(R.drawable.ic_star),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp).size(18.dp),
            )
        } else if (starSlot) {
            Spacer(Modifier.padding(start = 4.dp).size(18.dp))
        }
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        count?.let {
            Text(
                it.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.tag_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The row that asks for a new tag. */
@Composable
private fun NewTagRow(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.tag_new))
    }
}

/**
 * Asks for the name of a new own tag; a name another tag has, whatever its case, is refused.
 * [onCreate] makes it and tells whether that worked.
 */
@Composable
private fun NewTagDialog(tags: List<TagEntity>, onCreate: (String, (Boolean) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var rejected by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    val favourite = stringResource(R.string.tag_favourite)
    val trimmed = name.trim()
    val taken = trimmed == rejected || trimmed.equals(favourite, ignoreCase = true) ||
        tags.any { it.name.equals(trimmed, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tag_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.tag_name)) },
                singleLine = true,
                isError = taken,
                supportingText = if (taken) ({ Text(stringResource(R.string.tag_name_taken)) }) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty() && !taken,
                onClick = { onCreate(trimmed) { made -> if (made) onDismiss() else rejected = trimmed } },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Asks whether to delete an own tag, naming how many events lose it. */
@Composable
private fun DeleteTagDialog(deletion: TagDeletion, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
        title = { Text(stringResource(R.string.tag_delete_title, tagName(deletion.tag))) },
        text = {
            Text(
                if (deletion.events == 0) {
                    stringResource(R.string.tag_delete_unused)
                } else {
                    pluralStringResource(R.plurals.tag_delete_text, deletion.events, deletion.events)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * The user's tags of [event] as small outlined labels that wrap, Favourite with its star; nothing
 * without one. Outlined rather than filled, so they show on a selected row as well.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventTagLabels(event: SleepEvent, tags: List<TagEntity>, modifier: Modifier = Modifier) {
    val own = tags.filter { it.id in event.tagIds }
    if (own.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    FlowRow(modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (tag in own) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                if (tag.type == TagType.FAVOURITE) {
                    Icon(
                        painterResource(R.drawable.ic_star),
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.padding(end = 2.dp).size(12.dp),
                    )
                }
                Text(tagName(tag), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}
