package me.mupu.sleeptalkrecorder.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.Duration
import java.time.LocalTime

/**
 * One user setting: the key it is stored under and the value it has until the user changes it.
 *
 * A stored value that cannot be read back (an enum constant that no longer exists, a duration
 * outside the allowed range) never reaches the app: it reads as the default or the nearest allowed
 * value. New settings are declared in [Settings] with one of the factory functions below.
 */
abstract class Setting<T> internal constructor(
    /** The value before the user changed it. */
    val default: T,
) {
    internal abstract fun read(preferences: Preferences): T

    internal abstract fun write(preferences: MutablePreferences, value: T)
}

/** An on/off setting. */
fun booleanSetting(key: String, default: Boolean): Setting<Boolean> = object : Setting<Boolean>(default) {
    private val preferencesKey = booleanPreferencesKey(key)

    override fun read(preferences: Preferences) = preferences[preferencesKey] ?: default

    override fun write(preferences: MutablePreferences, value: Boolean) {
        preferences[preferencesKey] = value
    }
}

/** A choice between the constants of [E], stored by constant name. */
inline fun <reified E : Enum<E>> enumSetting(key: String, default: E): Setting<E> =
    enumSetting(key, default, enumValues<E>().toList())

/** A choice between [values], stored by constant name. */
fun <E : Enum<E>> enumSetting(key: String, default: E, values: List<E>): Setting<E> = object : Setting<E>(default) {
    private val preferencesKey = stringPreferencesKey(key)

    override fun read(preferences: Preferences) =
        preferences[preferencesKey]?.let { name -> values.find { it.name == name } } ?: default

    override fun write(preferences: MutablePreferences, value: E) {
        preferences[preferencesKey] = value.name
    }
}

/** A length of time within [range], stored in milliseconds. */
fun durationSetting(key: String, default: Duration, range: ClosedRange<Duration>): DurationSetting =
    DurationSetting(key, default, range)

/** A [Setting] for a length of time; [range] is what the settings screen offers. */
class DurationSetting internal constructor(
    key: String,
    default: Duration,
    /** The shortest and longest value the setting accepts. */
    val range: ClosedRange<Duration>,
) : Setting<Duration>(default) {

    private val preferencesKey = longPreferencesKey(key)

    override fun read(preferences: Preferences): Duration =
        preferences[preferencesKey]?.let { Duration.ofMillis(it).coerceIn(range) } ?: default

    override fun write(preferences: MutablePreferences, value: Duration) {
        preferences[preferencesKey] = value.coerceIn(range).toMillis()
    }
}

/** A clock time to the minute, stored as minutes since midnight. */
fun timeOfDaySetting(key: String, default: LocalTime): Setting<LocalTime> = object : Setting<LocalTime>(default) {
    private val preferencesKey = intPreferencesKey(key)

    override fun read(preferences: Preferences): LocalTime =
        preferences[preferencesKey]?.let { LocalTime.ofSecondOfDay(it.coerceIn(0, LAST_MINUTE) * 60L) } ?: default

    override fun write(preferences: MutablePreferences, value: LocalTime) {
        preferences[preferencesKey] = value.hour * 60 + value.minute
    }
}

/** 23:59 in minutes since midnight. */
private const val LAST_MINUTE = 24 * 60 - 1
