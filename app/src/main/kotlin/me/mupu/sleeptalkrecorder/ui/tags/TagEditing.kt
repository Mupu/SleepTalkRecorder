package me.mupu.sleeptalkrecorder.ui.tags

import me.mupu.sleeptalkrecorder.data.SleepEventOf
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.data.TagEntity
import me.mupu.sleeptalkrecorder.data.TagType
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The open tag window: the events it tags and the user's choice for them. */
data class TagWindowState(val events: List<SleepEventOf>, val choices: TagChoices)

/** An own tag whose deletion waits for the user's confirmation, with how many events would lose it. */
data class TagDeletion(val tag: TagEntity, val events: Int)

/**
 * The user's tags as a screen edits them: the tag window for one event or several selected ones,
 * which remembers the last changes it applied, new tags and deleting own tags. The sleep screen
 * and the library each hold one in their `ViewModel`'s [scope].
 */
class TagEditing(
    private val scope: CoroutineScope,
    private val repository: SleepRepository,
    private val settings: SettingsStore,
    /** Called with the id of an own tag once it is deleted, so a filter can let go of it. */
    private val onDeleted: (Long) -> Unit = {},
) {

    /** Favourite and the user's own tags, Favourite first. */
    val tags: StateFlow<List<TagEntity>> = repository.tags().stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val window = MutableStateFlow<TagWindowState?>(null)

    /** The tag window; null while it is closed. */
    val state: StateFlow<TagWindowState?> = window

    private val deletionFlow = MutableStateFlow<TagDeletion?>(null)

    /** The own tag the user asked to delete; null while no deletion waits. */
    val deletion: StateFlow<TagDeletion?> = deletionFlow

    /** Opens the tag window for [events], with the last changes applied; nothing for none. */
    fun open(events: List<SleepEventOf>) {
        if (events.isEmpty()) return
        scope.launch {
            val last = TagChanges.parse(settings.flow(Settings.tagChanges).first())
            val tagIds = repository.tags().first().map { it.id }
            window.value = TagWindowState(events, TagChoices.of(tagIds, events.map { it.event }, last))
        }
    }

    /** Taps the tag [tagId] in the open window. */
    fun tap(tagId: Long) {
        window.value = window.value?.let { it.copy(choices = it.choices.tap(tagId)) }
    }

    /**
     * Applies the window's choice to its events, remembers its changes for the next time and
     * closes it; then [onApplied] runs, as for ending a selection.
     */
    fun confirm(onApplied: () -> Unit = {}) {
        val open = window.value ?: return
        window.value = null
        scope.launch {
            // Only tags that still exist: one deleted meanwhile would have no row for its marks.
            val existing = repository.tags().first().mapTo(HashSet()) { it.id }
            val changes = open.choices.changes.keeping(existing)
            repository.changeTags(open.events, changes.added, changes.removed)
            settings.set(Settings.tagChanges, open.choices.remembered.keeping(existing).format())
            onApplied()
        }
    }

    /** Closes the window without a change. */
    fun close() {
        window.value = null
    }

    /**
     * Makes an own tag with [name], which an open tag window gives its events at once; [onResult]
     * learns whether it was made or the name was taken.
     */
    fun create(name: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            val id = repository.createTag(name)
            if (id != null) window.value = window.value?.let { it.copy(choices = it.choices.withNewTag(id)) }
            onResult(id != null)
        }
    }

    /** Asks to delete the own tag [tag]; the question names how many events would lose it. */
    fun askToDelete(tag: TagEntity) {
        if (tag.type != TagType.OWN) return
        scope.launch { deletionFlow.value = TagDeletion(tag, repository.taggedEventCount(tag.id)) }
    }

    /**
     * Deletes the tag the user confirmed, which takes it from every event, from an open tag window
     * and from the last changes the window remembers.
     */
    fun confirmDeletion() {
        val tag = deletionFlow.value?.tag ?: return
        deletionFlow.value = null
        window.value = window.value?.let { it.copy(choices = it.choices.without(tag.id)) }
        scope.launch {
            repository.deleteTag(tag.id)
            val last = TagChanges.parse(settings.flow(Settings.tagChanges).first())
            settings.set(Settings.tagChanges, last.without(tag.id).format())
            onDeleted(tag.id)
        }
    }

    fun cancelDeletion() {
        deletionFlow.value = null
    }
}
