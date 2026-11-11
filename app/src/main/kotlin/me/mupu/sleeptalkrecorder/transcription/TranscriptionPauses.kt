package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import java.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which sleeps [TranscriptionRunner] leaves out because the user paused their speech recognition,
 * and where its runs over all events of a sleep stand, so a paused or stopped one goes on there.
 */
interface PauseStore {
    /** The sleeps whose speech recognition is paused now. */
    suspend fun paused(): Set<Long>

    /**
     * A run over all events of [sleepId] is at [rerun]: every event before it is done. Null once
     * the run has done every event.
     */
    suspend fun rerunAt(sleepId: Long, rerun: Rerun?)
}

/** Where a run over all events of a sleep stands: at the event at [from], [left] events before its end. */
data class Rerun(val from: Duration, val left: Int)

/**
 * The [paused] sleeps, where the runs over all events of sleeps stand ([reruns]), and whether a
 * charger was plugged in at the latest pause, so that only a later charge ends it.
 */
data class PauseState(
    val paused: Set<Long> = emptySet(),
    val reruns: Map<Long, Rerun> = emptyMap(),
    val duringCharge: Boolean = false,
) {
    /**
     * The state as it is stored: "c p12 r13@4500/139" for a pause while charging, paused sleep 12,
     * and sleep 13's run over all events at 4.5 s with 139 events left.
     */
    fun encode(): String? = if (paused.isEmpty() && reruns.isEmpty()) {
        null
    } else {
        (
            listOf(if (duringCharge) CHARGING else NOT_CHARGING) +
                paused.map { "p$it" } +
                reruns.map { (id, rerun) -> "r$id@${rerun.from.toMillis()}/${rerun.left}" }
            ).joinToString(" ")
    }

    companion object {
        private const val CHARGING = "c"
        private const val NOT_CHARGING = "-"

        /** The state [text] stores; parts that cannot be read are left out, so a damaged entry never blocks a sleep for good. */
        fun decode(text: String?): PauseState {
            val parts = text?.split(' ').orEmpty()
            if (parts.isEmpty()) return PauseState()
            val paused = parts.filter { it.startsWith('p') }.mapNotNull { it.drop(1).toLongOrNull() }.toSet()
            val reruns = parts.filter { it.startsWith('r') }.mapNotNull { part ->
                val id = part.drop(1).substringBefore('@').toLongOrNull()
                val from = part.substringAfter('@', "").substringBefore('/').toLongOrNull()
                val left = part.substringAfter('/', "").toIntOrNull()
                if (id == null || from == null || left == null) null else id to Rerun(Duration.ofMillis(from), left)
            }.toMap()
            return PauseState(paused, reruns, duringCharge = parts.first() == CHARGING)
        }
    }
}

/**
 * The pauses the user made and where runs over all events stand, kept in the settings store so
 * they survive app restarts. A pause holds until the next run the settings start on their own
 * (see `Transcriptions.resume`).
 */
class TranscriptionPauses(private val settings: SettingsStore) : PauseStore {

    // Pauses come from the screens, the notification and the runner at once; each change reads
    // and writes the whole state.
    private val changes = Mutex()

    /** The current state, and every later change of it. */
    val state: Flow<PauseState> = settings.flow(Settings.transcriptionPauses).map(PauseState::decode)

    override suspend fun paused(): Set<Long> = state.first().paused

    override suspend fun rerunAt(sleepId: Long, rerun: Rerun?) = change { current ->
        current.copy(reruns = if (rerun == null) current.reruns - sleepId else current.reruns + (sleepId to rerun))
    }

    /** Where the run over all events of [sleepId] stopped, if one did. */
    suspend fun rerunOf(sleepId: Long): Rerun? = state.first().reruns[sleepId]

    /** Pauses [sleepId]; [charging] says whether a charger is plugged in now. */
    suspend fun pause(sleepId: Long, charging: Boolean) = change { current ->
        current.copy(paused = current.paused + sleepId, duringCharge = charging)
    }

    /** The charge during which the user paused ended, so the next one ends the pauses. */
    suspend fun chargeEnded() = change { it.copy(duringCharge = false) }

    /** Ends every pause and gives the state before, so the paused runs can go on. */
    suspend fun clear(): PauseState {
        var ended = PauseState()
        change { current ->
            ended = current
            current.copy(paused = emptySet(), duringCharge = false)
        }
        return ended
    }

    private suspend fun change(transform: (PauseState) -> PauseState) = changes.withLock {
        settings.set(Settings.transcriptionPauses, transform(state.first()).encode())
    }
}
