package me.mupu.sleeptalkrecorder.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 sorts events with tags: it adds the `tag` table with Favourite and a `tag_id` column
 * to `annotation`, which is rebuilt for it because SQLite cannot add a foreign key to a table.
 * Every favourite mark of version 1 becomes a mark of the tag Favourite over the same stretch,
 * and its name, if it had one, a name mark beside it. The plain event kind becomes a system tag
 * of the filter, as speech already was.
 */
internal object Migration1To2 : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        for (statement in STATEMENTS) db.execSQL(statement)
    }

    /** The statements in the order they run. */
    private val STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `tag` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `name` TEXT COLLATE NOCASE)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_tag_name` ON `tag` (`name`)",
        "INSERT INTO `tag` (`id`, `type`, `name`) VALUES (${Tags.FAVOURITE}, 'FAVOURITE', NULL)",
        "CREATE TABLE IF NOT EXISTS `annotation_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`sleep_id` INTEGER NOT NULL, `type` TEXT NOT NULL, `start_ms` INTEGER NOT NULL, `end_ms` INTEGER NOT NULL, " +
            "`kind_id` TEXT, `value` REAL, `text` TEXT, `origin` TEXT NOT NULL, `tag_id` INTEGER, `producer` TEXT, " +
            "`producer_version` TEXT, " +
            "FOREIGN KEY(`sleep_id`) REFERENCES `sleep`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`kind_id`) REFERENCES `event_kind`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`tag_id`) REFERENCES `tag`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "INSERT INTO `annotation_new` (`id`, `sleep_id`, `type`, `start_ms`, `end_ms`, `kind_id`, `value`, `text`, " +
            "`origin`, `producer`, `producer_version`) " +
            "SELECT `id`, `sleep_id`, `type`, `start_ms`, `end_ms`, `kind_id`, `value`, `text`, `origin`, `producer`, " +
            "`producer_version` FROM `annotation` WHERE `type` != 'FAVOURITE'",
        "INSERT INTO `annotation_new` (`sleep_id`, `type`, `start_ms`, `end_ms`, `origin`, `tag_id`) " +
            "SELECT `sleep_id`, 'TAG', `start_ms`, `end_ms`, `origin`, ${Tags.FAVOURITE} FROM `annotation` " +
            "WHERE `type` = 'FAVOURITE' ORDER BY `id`",
        "INSERT INTO `annotation_new` (`sleep_id`, `type`, `start_ms`, `end_ms`, `text`, `origin`) " +
            "SELECT `sleep_id`, 'NAME', `start_ms`, `end_ms`, `text`, `origin` FROM `annotation` " +
            "WHERE `type` = 'FAVOURITE' AND `text` IS NOT NULL ORDER BY `id`",
        "DROP TABLE `annotation`",
        "ALTER TABLE `annotation_new` RENAME TO `annotation`",
        "CREATE INDEX IF NOT EXISTS `index_annotation_sleep_id_type_start_ms_end_ms` ON `annotation` (`sleep_id`, `type`, `start_ms`, `end_ms`)",
        "CREATE INDEX IF NOT EXISTS `index_annotation_kind_id` ON `annotation` (`kind_id`)",
        "CREATE INDEX IF NOT EXISTS `index_annotation_tag_id` ON `annotation` (`tag_id`)",
        "UPDATE `event_kind` SET `has_filter` = 1 WHERE `id` = '${EventKinds.NOISE}'",
    )
}
