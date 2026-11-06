package com.arshad.ayn.core.store

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Every schema step, in order. Room runs a step and its version bump in one exclusive
 * transaction (rolled back on failure), so an interrupted upgrade leaves the old version intact.
 * Each step's DDL is the exported schema's `createSql`; `KeMigrationsTest` validates against it.
 */
object KeMigrations {

    /**
     * v1 → v2: profiles (B-D9). Creates the owner ("Owner", amber; renamable) and moves every
     * subscription to it. A manual step: `@AutoMigration` checks foreign keys before its
     * post-migrate hook could create the owner (Room 3 migrations).
     */
    val V1_TO_V2: Migration = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL COLLATE NOCASE, `tint` TEXT NOT NULL, `ownerMark` INTEGER, " +
                    "`lockKind` TEXT, `lockHash` TEXT, `lockSalt` TEXT, `lockIterations` INTEGER NOT NULL, " +
                    "`failedTries` INTEGER NOT NULL, `waitUntilMillis` INTEGER, `createdAtMillis` INTEGER NOT NULL, " +
                    "`updatedAtMillis` INTEGER NOT NULL)",
            )
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_profiles_name` ON `profiles` (`name`)")
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_profiles_ownerMark` ON `profiles` (`ownerMark`)")
            connection.execSQL(
                "INSERT INTO profiles (name, tint, ownerMark, lockIterations, failedTries, createdAtMillis, updatedAtMillis) " +
                    "VALUES ('$OWNER_NAME', '${ProfileTint.Amber.key}', $OWNER_MARK, 0, 0, " +
                    "CAST(strftime('%s','now') AS INTEGER) * 1000, CAST(strftime('%s','now') AS INTEGER) * 1000)",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `_new_subscriptions` (`profileId` INTEGER NOT NULL, " +
                    "`channelUrl` TEXT NOT NULL, `name` TEXT NOT NULL, `avatarUrl` TEXT, " +
                    "`subscribedAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`profileId`, `channelUrl`), FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL(
                "INSERT INTO `_new_subscriptions` (profileId, channelUrl, name, avatarUrl, subscribedAtMillis, updatedAtMillis) " +
                    "SELECT (SELECT id FROM profiles WHERE ownerMark IS NOT NULL), channelUrl, name, avatarUrl, " +
                    "subscribedAtMillis, updatedAtMillis FROM subscriptions",
            )
            connection.execSQL("DROP TABLE subscriptions")
            connection.execSQL("ALTER TABLE `_new_subscriptions` RENAME TO `subscriptions`")
        }
    }

    /** v2 → v3: watch state (M5-D7/D8), a new table; existing rows are untouched. */
    val V2_TO_V3: Migration = object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `watch_entries` (`profileId` INTEGER NOT NULL, `videoId` TEXT NOT NULL, " +
                    "`title` TEXT, `uploaderName` TEXT, `uploaderUrl` TEXT, `thumbnailUrl` TEXT, " +
                    "`positionMs` INTEGER NOT NULL, `durationMs` INTEGER, `watched` INTEGER NOT NULL, " +
                    "`lastWatchedAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`profileId`, `videoId`), FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }
    }

    /** v3 → v4: playlists and Watch later (M5-D13), new tables; existing rows are untouched. */
    val V3_TO_V4: Migration = object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`profileId` INTEGER NOT NULL, `name` TEXT NOT NULL, `watchLaterMark` INTEGER, " +
                    "`createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlists_profileId_watchLaterMark` " +
                    "ON `playlists` (`profileId`, `watchLaterMark`)",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlist_items` (`playlistId` INTEGER NOT NULL, `videoId` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL, `title` TEXT NOT NULL, `uploaderName` TEXT, `uploaderUrl` TEXT, " +
                    "`durationSeconds` INTEGER NOT NULL, `uploadDateMillis` INTEGER, `thumbnailUrl` TEXT, " +
                    "`addedAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`playlistId`, `videoId`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }
    }

    /**
     * v4 → v5: one row per channel (M5-D19), no DDL. Every row is read first (SQLite has no regex
     * for the canonical key), merged by [collapseByChannel], and only when that changed a row (a
     * merge, or a URL re-keyed to its canonical form) is the table rewritten: emptied, then the
     * merged rows inserted (no key can collide mid-rewrite).
     */
    val V4_TO_V5: Migration = object : Migration(4, 5) {
        override suspend fun migrate(connection: SQLiteConnection) {
            val rows = connection.prepare(
                "SELECT profileId, channelUrl, name, avatarUrl, subscribedAtMillis, updatedAtMillis FROM subscriptions",
            ).use { select ->
                buildList {
                    while (select.step()) {
                        add(
                            SubscriptionEntity(
                                profileId = select.getLong(0),
                                channelUrl = select.getText(1),
                                name = select.getText(2),
                                avatarUrl = if (select.isNull(3)) null else select.getText(3),
                                subscribedAtMillis = select.getLong(4),
                                updatedAtMillis = select.getLong(5),
                            ),
                        )
                    }
                }
            }
            val merged = collapseByChannel(rows)
            if (merged == rows) return
            connection.execSQL("DELETE FROM subscriptions")
            connection.prepare(
                "INSERT INTO subscriptions (profileId, channelUrl, name, avatarUrl, subscribedAtMillis, updatedAtMillis) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { insert ->
                merged.forEach { row ->
                    insert.bindLong(1, row.profileId)
                    insert.bindText(2, row.channelUrl)
                    insert.bindText(3, row.name)
                    row.avatarUrl?.let { insert.bindText(4, it) } ?: insert.bindNull(4)
                    insert.bindLong(5, row.subscribedAtMillis)
                    insert.bindLong(6, row.updatedAtMillis)
                    insert.step()
                    insert.reset()
                }
            }
        }
    }

    /** v5 → v6: per-profile settings (M7-D9), a new empty table: a profile without a row has the
     *  defaults, so nothing is backfilled. */
    val V5_TO_V6: Migration = object : Migration(5, 6) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `profile_settings` (`profileId` INTEGER NOT NULL, " +
                    "`speedPercent` INTEGER NOT NULL, `resume` INTEGER NOT NULL, `captionsLanguage` TEXT, " +
                    "`sponsorBlock` INTEGER NOT NULL, `sb_sponsor` TEXT NOT NULL, `sb_selfpromo` TEXT NOT NULL, " +
                    "`sb_interaction` TEXT NOT NULL, `sb_intro` TEXT NOT NULL, `sb_outro` TEXT NOT NULL, " +
                    "`sb_preview` TEXT NOT NULL, `sb_hook` TEXT NOT NULL, `sb_music_offtopic` TEXT NOT NULL, " +
                    "`sb_filler` TEXT NOT NULL, `skipNote` INTEGER NOT NULL, `dearrowTitles` INTEGER NOT NULL, " +
                    "`dearrowThumbnails` INTEGER NOT NULL, `dislikes` INTEGER NOT NULL, `showShorts` INTEGER NOT NULL, " +
                    "`updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`profileId`), " +
                    "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }
    }

    /**
     * v6 → v7: For you (FY-D4, D5, D6, D10). New empty tables (related lists, hidden videos and channels,
     * impressions per profile; the device-wide request log) and the For you switch, on for every
     * existing profile.
     */
    val V6_TO_V7: Migration = object : Migration(6, 7) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `profile_settings` ADD COLUMN `forYou` INTEGER NOT NULL DEFAULT 1")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `related_lists` (`profileId` INTEGER NOT NULL, `videoId` TEXT NOT NULL, " +
                    "`items` TEXT NOT NULL, `fetchedAtMillis` INTEGER, `failedAtMillis` INTEGER, " +
                    "PRIMARY KEY(`profileId`, `videoId`), FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `hidden_videos` (`profileId` INTEGER NOT NULL, `videoId` TEXT NOT NULL, " +
                    "`hiddenAtMillis` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `videoId`), " +
                    "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `hidden_channels` (`profileId` INTEGER NOT NULL, `channelUrl` TEXT NOT NULL, " +
                    "`hiddenAtMillis` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `channelUrl`), " +
                    "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `impressions` (`profileId` INTEGER NOT NULL, `videoId` TEXT NOT NULL, " +
                    "`channelUrl` TEXT, `shownCount` INTEGER NOT NULL, `lastShownAtMillis` INTEGER NOT NULL, " +
                    "`openedAtMillis` INTEGER, PRIMARY KEY(`profileId`, `videoId`), " +
                    "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `fetch_attempts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`atMillis` INTEGER NOT NULL, `outcome` TEXT NOT NULL)",
            )
        }
    }

    /**
     * v7 → v8: content filters and kids profiles (KP-D18). Every existing profile is a standard one
     * with no filters (the new settings columns read as off); kept related lists carry the
     * Restricted Mode they were fetched under (KP-D15; NULL = Off, as every one so far); new empty
     * tables for the owner's restrictions, list rules and kids watch time.
     */
    val V7_TO_V8: Migration = object : Migration(7, 8) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `profiles` ADD COLUMN `kids` INTEGER NOT NULL DEFAULT 0")
            connection.execSQL("ALTER TABLE `profile_settings` ADD COLUMN `restrictedMode` TEXT")
            connection.execSQL("ALTER TABLE `profile_settings` ADD COLUMN `hideLive` INTEGER NOT NULL DEFAULT 0")
            connection.execSQL("ALTER TABLE `profile_settings` ADD COLUMN `hideMembersOnly` INTEGER NOT NULL DEFAULT 0")
            connection.execSQL("ALTER TABLE `profile_settings` ADD COLUMN `minLengthMinutes` INTEGER")
            connection.execSQL("ALTER TABLE `profile_settings` ADD COLUMN `maxLengthMinutes` INTEGER")
            connection.execSQL("ALTER TABLE `related_lists` ADD COLUMN `restrictedMode` TEXT")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `profile_restrictions` (`profileId` INTEGER NOT NULL, " +
                    "`restrictedMode` TEXT NOT NULL, `hideShorts` INTEGER NOT NULL, `hideLive` INTEGER NOT NULL, " +
                    "`hideMembersOnly` INTEGER NOT NULL, `minLengthMinutes` INTEGER, `maxLengthMinutes` INTEGER, " +
                    "`limit_mon` INTEGER, `limit_tue` INTEGER, `limit_wed` INTEGER, `limit_thu` INTEGER, " +
                    "`limit_fri` INTEGER, `limit_sat` INTEGER, `limit_sun` INTEGER, `bedtimeStartMinute` INTEGER, " +
                    "`bedtimeEndMinute` INTEGER, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`profileId`), " +
                    "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `content_rules` (`profileId` INTEGER NOT NULL, `byOwner` INTEGER NOT NULL, " +
                    "`kind` TEXT NOT NULL, `value` TEXT NOT NULL, `title` TEXT, `channelName` TEXT, `channelUrl` TEXT, " +
                    "`thumbnailUrl` TEXT, `durationSeconds` INTEGER, `addedAtMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`profileId`, `byOwner`, `kind`, `value`), FOREIGN KEY(`profileId`) REFERENCES " +
                    "`profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `kids_time` (`profileId` INTEGER NOT NULL, `day` TEXT NOT NULL, " +
                    "`usedSeconds` INTEGER NOT NULL, `moreTimeMinutes` INTEGER NOT NULL, " +
                    "`allowedUntilSeconds` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `day`), " +
                    "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }
    }

    /** v8 → v9: each profile's last fetched channel feeds (PERF P9c), a new empty table: the first
     *  start after the upgrade loads as before. */
    val V8_TO_V9: Migration = object : Migration(8, 9) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `channel_feeds` (`profileId` INTEGER NOT NULL, `channelUrl` TEXT NOT NULL, " +
                    "`videos` TEXT NOT NULL, `shorts` TEXT NOT NULL, `fetchedAtMillis` INTEGER NOT NULL, " +
                    "`restrictedMode` TEXT NOT NULL, PRIMARY KEY(`profileId`, `channelUrl`), " +
                    "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }
    }

    /** v9 → v10: a watch entry records whether it played as a Short (PAF-D6); existing entries are
     *  not Shorts. */
    val V9_TO_V10: Migration = object : Migration(9, 10) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `watch_entries` ADD COLUMN `isShort` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v10 → v11: each setter's filter packs (FPK-D16, D17) on the profile's settings and the
     *  owner's restrictions; every existing profile, kids included, has them off. */
    val V10_TO_V11: Migration = object : Migration(10, 11) {
        override suspend fun migrate(connection: SQLiteConnection) {
            for (table in listOf("profile_settings", "profile_restrictions")) {
                for (column in listOf(
                    "fp_harmfulForKids", "fp_overstimulating", "fp_challengesAndVlogs", "fp_halalOnly",
                    "fp_stageMagic", "fp_festivals", "fp_lootBoxes", "fp_islamicOnly",
                )) {
                    connection.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` INTEGER NOT NULL DEFAULT 0")
                }
                connection.execSQL("ALTER TABLE `$table` ADD COLUMN `fp_islamicChannels` TEXT")
            }
        }
    }

    /** v11 → v12: sync ids on profiles and playlists (M8-D5, M8-D12), each row its own random one
     *  and every Watch later the shared one; a new empty table of removal records. */
    val V11_TO_V12: Migration = object : Migration(11, 12) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `profiles` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
            connection.execSQL("ALTER TABLE `playlists` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
            connection.execSQL("UPDATE playlists SET syncId = '$WATCH_LATER_SYNC_ID' WHERE watchLaterMark IS NOT NULL")
            for (table in listOf("profiles", "playlists")) {
                val ids = connection.prepare("SELECT id FROM $table WHERE syncId = ''").use { select ->
                    buildList { while (select.step()) add(select.getLong(0)) }
                }
                connection.prepare("UPDATE $table SET syncId = ? WHERE id = ?").use { update ->
                    ids.forEach { id ->
                        update.bindText(1, newSyncId())
                        update.bindLong(2, id)
                        update.step()
                        update.reset()
                    }
                }
            }
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_profiles_syncId` ON `profiles` (`syncId`)")
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlists_profileId_syncId` ON `playlists` (`profileId`, `syncId`)",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `removals` (`profileSyncId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`itemKey` TEXT NOT NULL, `removedAtMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`profileSyncId`, `kind`, `itemKey`))",
            )
        }
    }

    /** v12 → v13: where a profile's live chat shows (LIVE-D21); every existing profile has it
     *  beside the video. */
    val V12_TO_V13: Migration = object : Migration(12, 13) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `profile_settings` ADD COLUMN `chatPlacement` TEXT NOT NULL DEFAULT 'beside'")
        }
    }

    val all: Array<Migration> =
        arrayOf(
            V1_TO_V2, V2_TO_V3, V3_TO_V4, V4_TO_V5, V5_TO_V6, V6_TO_V7, V7_TO_V8, V8_TO_V9, V9_TO_V10, V10_TO_V11, V11_TO_V12,
            V12_TO_V13,
        )
}
