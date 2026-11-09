package me.mupu.sleeptalkrecorder.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import java.time.Instant
import java.util.concurrent.Executor

/**
 * The phone's built-in microphone as 16 kHz mono 16-bit PCM.
 *
 * The source is `VOICE_RECOGNITION` because the Android CDD requires automatic gain control and
 * noise reduction to be off for it by default, which keeps loudness comparable across a night.
 * 16 kHz covers speech fully and keeps a night near 1 GB of WAV.
 *
 * When a call, another app or the privacy toggle takes the microphone away, Android keeps the
 * recording running but delivers silence; the recording callback reports it, and [isSilenced]
 * follows it.
 */
class MicrophoneSource(private val context: Context) : PcmSource {

    override val sampleRate: Int = 16_000

    private var record: AudioRecord? = null
    private val audioTimestamp = AudioTimestamp()

    @Volatile
    private var silenced = false
    private var silenceCallback: AudioManager.AudioRecordingCallback? = null

    override fun start(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        val minBufferBytes = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        if (minBufferBytes <= 0) return false

        val audio = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(CHANNEL)
                        .setEncoding(ENCODING)
                        .build(),
                )
                // At least one second of audio, so a short stall of the writer loses nothing.
                .setBufferSizeInBytes(maxOf(minBufferBytes, sampleRate * ChunkedWavWriter.BYTES_PER_FRAME))
                .build()
        } catch (_: UnsupportedOperationException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        record = audio
        if (audio.state != AudioRecord.STATE_INITIALIZED) return false

        // Keep recording from the phone even when a headset connects during the night.
        builtInMicrophone()?.let(audio::setPreferredDevice)

        // Registered before the start, so that no change is missed.
        val session = audio.audioSessionId
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                configs.firstOrNull { it.clientAudioSessionId == session }?.let { silenced = it.isClientSilenced }
            }
        }
        // The callback only sets a flag, so it may run on the thread that delivers it.
        audio.registerAudioRecordingCallback(Executor(Runnable::run), callback)
        silenceCallback = callback

        return try {
            audio.startRecording()
            audio.activeRecordingConfiguration?.let { silenced = it.isClientSilenced }
            audio.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (_: IllegalStateException) {
            false
        }
    }

    override fun read(buffer: ShortArray): Int = record?.read(buffer, 0, buffer.size) ?: -1

    override fun timestamp(): AudioTimestampSample? {
        val audio = record ?: return null
        val result = audio.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_BOOTTIME)
        if (result != AudioRecord.SUCCESS) return null
        return AudioTimestampSample(audioTimestamp.framePosition, audioTimestamp.nanoTime)
    }

    override fun isSilenced(): Boolean = silenced

    override fun close() {
        val audio = record ?: return
        record = null
        silenceCallback?.let(audio::unregisterAudioRecordingCallback)
        silenceCallback = null
        try {
            audio.stop()
        } catch (_: IllegalStateException) {
            // Never started; releasing is all that is left to do.
        }
        audio.release()
    }

    private fun builtInMicrophone(): AudioDeviceInfo? =
        context.getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    private companion object {
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}

/**
 * The system clocks. `elapsedRealtimeNanos` is the timebase `AudioTimestamp.TIMEBASE_BOOTTIME`
 * reports in, so the two can be subtracted.
 */
object SystemClocks : Clocks {
    override fun now(): Instant = Instant.now()
    override fun elapsedNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
