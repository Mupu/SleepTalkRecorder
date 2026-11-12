package me.mupu.sleeptalkrecorder.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The app's database: sleeps, their recordings, and the annotations found in them with the event
 * kinds and tags those refer to. Audio and feature tracks are not stored here but in files next to it (see
 * [SleepRepository]).
 */
@Database(
    entities = [SleepEntity::class, RecordingEntity::class, EventKindEntity::class, TagEntity::class, AnnotationEntity::class],
    version = 2,
)
@TypeConverters(InstantConverter::class, LocalDateConverter::class, DurationConverter::class, KindColorConverter::class, TagIdsConverter::class)
abstract class SleepDatabase : RoomDatabase() {

    abstract fun sleepDao(): SleepDao

    companion object {
        /** Opens the database stored in the app's private storage. */
        fun open(context: Context): SleepDatabase =
            Room.databaseBuilder(context, SleepDatabase::class.java, "sleeps.db")
                .addMigrations(Migration1To2)
                .addCallback(AddBuiltInRows)
                .build()
    }
}

/**
 * Adds the [EventKinds.builtIn] kinds and the tag Favourite ([Tags.favourite]) when they are
 * missing, so a new app version can ship new kinds and a new database has Favourite before the
 * user makes a tag of their own.
 */
private object AddBuiltInRows : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        for (kind in EventKinds.builtIn) {
            val values = ContentValues().apply {
                put("id", kind.id)
                put("name", kind.name)
                put("color", kind.color.spec)
                put("has_filter", kind.hasFilter)
            }
            db.insert("event_kind", SQLiteDatabase.CONFLICT_IGNORE, values)
        }
        val favourite = ContentValues().apply {
            put("id", Tags.favourite.id)
            put("type", Tags.favourite.type.name)
            putNull("name")
        }
        db.insert("tag", SQLiteDatabase.CONFLICT_IGNORE, favourite)
    }
}

/** Stores the ids of an event's tags as a comma-separated list, as `GROUP_CONCAT` gives them. */
class TagIdsConverter {

    @TypeConverter
    fun toList(ids: Set<Long>?): String? = ids?.takeIf { it.isNotEmpty() }?.joinToString(",")

    @TypeConverter
    fun fromList(list: String?): Set<Long> = list?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty()
}

/** Stores instants as UTC milliseconds since the epoch. */
class InstantConverter {

    @TypeConverter
    fun toEpochMillis(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun fromEpochMillis(epochMillis: Long?): Instant? = epochMillis?.let(Instant::ofEpochMilli)
}

/** Stores calendar days as ISO dates such as `2026-11-08`, which sort in date order. */
class LocalDateConverter {

    @TypeConverter
    fun toIso(date: LocalDate?): String? = date?.toString()

    @TypeConverter
    fun fromIso(iso: String?): LocalDate? = iso?.let(LocalDate::parse)
}

/** Stores positions in a sleep's audio and other lengths of time in whole milliseconds. */
class DurationConverter {

    @TypeConverter
    fun toMillis(duration: Duration?): Long? = duration?.toMillis()

    @TypeConverter
    fun fromMillis(millis: Long?): Duration? = millis?.let(Duration::ofMillis)
}

/** Stores an event kind's colour as its [KindColor.spec]. */
class KindColorConverter {

    @TypeConverter
    fun toSpec(color: KindColor?): String? = color?.spec

    @TypeConverter
    fun fromSpec(spec: String?): KindColor? = spec?.let(KindColor::parse)
}
