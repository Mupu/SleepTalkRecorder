package me.mupu.sleeptalkrecorder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.Instant

/**
 * The app's database: sleeps and their recordings. Audio is not stored here but in chunk files
 * next to it (see [SleepRepository]).
 */
@Database(entities = [SleepEntity::class, RecordingEntity::class], version = 1)
@TypeConverters(InstantConverter::class)
abstract class SleepDatabase : RoomDatabase() {

    abstract fun sleepDao(): SleepDao

    companion object {
        /** Opens the database stored in the app's private storage. */
        fun open(context: Context): SleepDatabase =
            Room.databaseBuilder(context, SleepDatabase::class.java, "sleeps.db").build()
    }
}

/** Stores instants as UTC milliseconds since the epoch. */
class InstantConverter {

    @TypeConverter
    fun toEpochMillis(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun fromEpochMillis(epochMillis: Long?): Instant? = epochMillis?.let(Instant::ofEpochMilli)
}
