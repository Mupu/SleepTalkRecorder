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
import java.time.Instant

/**
 * The app's database: sleeps, their recordings, and the annotations found in them with the event
 * kinds those refer to. Audio and feature tracks are not stored here but in files next to it (see
 * [SleepRepository]).
 */
@Database(
    entities = [SleepEntity::class, RecordingEntity::class, EventKindEntity::class, AnnotationEntity::class],
    version = 1,
)
@TypeConverters(InstantConverter::class)
abstract class SleepDatabase : RoomDatabase() {

    abstract fun sleepDao(): SleepDao

    companion object {
        /** Opens the database stored in the app's private storage. */
        fun open(context: Context): SleepDatabase =
            Room.databaseBuilder(context, SleepDatabase::class.java, "sleeps.db")
                .addCallback(AddBuiltInKinds)
                .build()
    }
}

/** Adds the [EventKinds.builtIn] kinds that are missing, so a new app version can ship new kinds. */
private object AddBuiltInKinds : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        for (kind in EventKinds.builtIn) {
            val values = ContentValues().apply {
                put("id", kind.id)
                put("name", kind.name)
                put("color", kind.color)
                put("has_filter", kind.hasFilter)
            }
            db.insert("event_kind", SQLiteDatabase.CONFLICT_IGNORE, values)
        }
    }
}

/** Stores instants as UTC milliseconds since the epoch. */
class InstantConverter {

    @TypeConverter
    fun toEpochMillis(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun fromEpochMillis(epochMillis: Long?): Instant? = epochMillis?.let(Instant::ofEpochMilli)
}
