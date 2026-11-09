package me.mupu.sleeptalkrecorder.ui.whatsnew

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.settings.AppLanguage
import me.mupu.sleeptalkrecorder.whatsnew.BundledChangelogs
import me.mupu.sleeptalkrecorder.whatsnew.Release
import me.mupu.sleeptalkrecorder.whatsnew.Version
import me.mupu.sleeptalkrecorder.whatsnew.WhatsNew
import me.mupu.sleeptalkrecorder.whatsnew.newerThan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The changelog of the language the app shows, for the list of all updates, and the news of an
 * update, for the pop-up that shows them once. It lives as long as the activity, so a new language
 * re-reads the changelog while the news keep their state.
 */
class WhatsNewViewModel(
    private val changelogs: BundledChangelogs,
    private val whatsNew: WhatsNew,
    /** The version of the installed app. */
    val installed: Version,
) : ViewModel() {

    private val language = MutableStateFlow<AppLanguage?>(null)
    private val unseenSince = MutableStateFlow<Version?>(null)

    /** Every release in the language the app shows, newest first; null while it is read. */
    val releases: StateFlow<List<Release>?> = language
        .filterNotNull()
        .map { withContext(Dispatchers.IO) { changelogs.releases(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The releases the user has not seen since the last update, newest first; empty when there are none. */
    val news: StateFlow<List<Release>> = combine(releases.filterNotNull(), unseenSince) { releases, since ->
        since?.let(releases::newerThan).orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { unseenSince.value = whatsNew.unseenSince() }
    }

    /** Shows the changelog in [language], the one the app is shown in now. */
    fun showIn(language: AppLanguage) {
        this.language.value = language
    }

    /** The user has seen the news; they are not shown again. */
    fun dismissNews() {
        unseenSince.value = null
        viewModelScope.launch { whatsNew.markSeen() }
    }
}
