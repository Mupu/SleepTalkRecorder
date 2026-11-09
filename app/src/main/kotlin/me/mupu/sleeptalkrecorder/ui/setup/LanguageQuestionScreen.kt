package me.mupu.sleeptalkrecorder.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.settings.AppLanguage
import me.mupu.sleeptalkrecorder.settings.AppLanguages
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import kotlinx.coroutines.launch

/** Remembers the answer to the first-start language question and switches the app to it. */
class LanguageQuestionViewModel(private val store: SettingsStore) : ViewModel() {

    fun choose(language: AppLanguage) {
        viewModelScope.launch {
            store.set(Settings.languageChosen, true)
            AppLanguages.apply(language)
        }
    }
}

/**
 * The first screen after installing: asks which language the app speaks, with the phone's
 * language selected.
 */
@Composable
fun LanguageQuestionScreen(viewModel: LanguageQuestionViewModel) {
    val phoneLanguage = AppLanguages.phoneLanguage(LocalContext.current)
    var selected by rememberSaveable { mutableStateOf(phoneLanguage) }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_bedtime),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                stringResource(R.string.setup_welcome, stringResource(R.string.app_name)),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(stringResource(R.string.setup_language_question), style = MaterialTheme.typography.titleMedium)

            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguage.entries.forEach { language ->
                    LanguageOption(
                        language = language,
                        isPhoneLanguage = language == phoneLanguage,
                        selected = language == selected,
                        onSelect = { selected = language },
                    )
                }
            }
            Text(
                stringResource(R.string.setup_language_later),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))
            Button(onClick = { viewModel.choose(selected) }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.next))
            }
        }
    }
}

@Composable
private fun LanguageOption(
    language: AppLanguage,
    isPhoneLanguage: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        border = border,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(start = 16.dp)) {
                Text(language.nativeName, style = MaterialTheme.typography.titleMedium)
                if (isPhoneLanguage) {
                    Text(
                        stringResource(R.string.setup_phone_language),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
