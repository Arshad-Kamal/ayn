package com.arshad.ayn.core.store

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Each migration from the previous exported schema, validated against the next exported schema
 * (ARCH-8): the rows a device holds survive the step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeMigrationsTest {

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = RuntimeEnvironment.getApplication().getDatabasePath("migration-test.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = KeDatabase::class,
    )

    @Test
    fun v1SubscriptionsMoveToANewOwnerWithEveryFieldKept() = runTest {
        helper.createDatabase(1).use { v1 ->
            v1.execSQL("INSERT INTO subscriptions VALUES ('$CHANNEL_A', 'A', 'https://img/a', 1000, 2000)")
            v1.execSQL("INSERT INTO subscriptions VALUES ('$CHANNEL_B', 'B', NULL, 3000, 4000)")
        }

        helper.runMigrationsAndValidate(2, KeMigrations.all.toList()).use { v2 ->
            assertEquals(listOf(listOf("1", "Owner", "amber", "1")), v2.rows("SELECT id, name, tint, ownerMark FROM profiles"))
            assertEquals(
                listOf(
                    listOf("1", CHANNEL_A, "A", "https://img/a", "1000", "2000"),
                    listOf("1", CHANNEL_B, "B", null, "3000", "4000"),
                ),
                v2.rows("SELECT * FROM subscriptions ORDER BY channelUrl"),
            )
        }
    }

    @Test
    fun anEmptyV1StillGetsItsOwner() = runTest {
        helper.createDatabase(1).close()

        helper.runMigrationsAndValidate(2, KeMigrations.all.toList()).use { v2 ->
            assertEquals(listOf(listOf("Owner", "1")), v2.rows("SELECT name, ownerMark FROM profiles"))
            assertEquals(emptyList<List<String?>>(), v2.rows("SELECT * FROM subscriptions"))
        }
    }

    @Test
    fun theMigratedSubscriptionsGoWithTheirProfile() = runTest {
        helper.createDatabase(1).use { v1 ->
            v1.execSQL("INSERT INTO subscriptions VALUES ('$CHANNEL_A', 'A', NULL, 1000, 2000)")
        }

        helper.runMigrationsAndValidate(2, KeMigrations.all.toList()).use { v2 ->
            v2.execSQL("PRAGMA foreign_keys = ON")
            v2.execSQL("DELETE FROM profiles")
            assertEquals(emptyList<List<String?>>(), v2.rows("SELECT * FROM subscriptions"))
        }
    }

    @Test
    fun v2KeepsItsProfilesAndSubscriptionsAndGainsAnEmptyWatchState() = runTest {
        helper.createDatabase(2).use { v2 ->
            v2.execSQL(
                "INSERT INTO profiles (id, name, tint, ownerMark, lockIterations, failedTries, createdAtMillis, updatedAtMillis) " +
                    "VALUES (1, 'Owner', 'amber', 1, 0, 0, 10, 20), (2, 'Yuri', 'teal', NULL, 0, 0, 30, 40)",
            )
            v2.execSQL("INSERT INTO subscriptions VALUES (2, '$CHANNEL_A', 'A', NULL, 1000, 2000)")
        }

        helper.runMigrationsAndValidate(3, KeMigrations.all.toList()).use { v3 ->
            assertEquals(listOf(listOf("1", "Owner"), listOf("2", "Yuri")), v3.rows("SELECT id, name FROM profiles ORDER BY id"))
            assertEquals(listOf(listOf("2", CHANNEL_A, "A", null, "1000", "2000")), v3.rows("SELECT * FROM subscriptions"))
            assertEquals(emptyList<List<String?>>(), v3.rows("SELECT * FROM watch_entries"))
        }
    }

    @Test
    fun theWatchStateGoesWithItsProfile() = runTest {
        helper.createDatabase(2).use { v2 -> v2.execSQL(INSERT_OWNER) }

        helper.runMigrationsAndValidate(3, KeMigrations.all.toList()).use { v3 ->
            v3.execSQL("PRAGMA foreign_keys = ON")
            v3.execSQL(
                "INSERT INTO watch_entries (profileId, videoId, positionMs, watched, lastWatchedAtMillis, updatedAtMillis) " +
                    "VALUES (1, 'aaaaaaaaaaa', 0, 0, 1, 1)",
            )
            v3.execSQL("DELETE FROM profiles")
            assertEquals(emptyList<List<String?>>(), v3.rows("SELECT * FROM watch_entries"))
        }
    }

    @Test
    fun v3KeepsEveryRowAndGainsEmptyPlaylists() = runTest {
        helper.createDatabase(3).use { v3 ->
            v3.execSQL(INSERT_OWNER)
            v3.execSQL("INSERT INTO subscriptions VALUES (1, '$CHANNEL_A', 'A', NULL, 1000, 2000)")
            v3.execSQL(
                "INSERT INTO watch_entries (profileId, videoId, title, positionMs, durationMs, watched, lastWatchedAtMillis, updatedAtMillis) " +
                    "VALUES (1, 'aaaaaaaaaaa', 'Title', 60000, 600000, 0, 5, 6)",
            )
        }

        helper.runMigrationsAndValidate(4, KeMigrations.all.toList()).use { v4 ->
            assertEquals(listOf(listOf("1", CHANNEL_A, "A", null, "1000", "2000")), v4.rows("SELECT * FROM subscriptions"))
            assertEquals(
                listOf(listOf("1", "aaaaaaaaaaa", "Title", "60000", "600000", "0", "5", "6")),
                v4.rows("SELECT profileId, videoId, title, positionMs, durationMs, watched, lastWatchedAtMillis, updatedAtMillis FROM watch_entries"),
            )
            assertEquals(emptyList<List<String?>>(), v4.rows("SELECT * FROM playlists"))
            assertEquals(emptyList<List<String?>>(), v4.rows("SELECT * FROM playlist_items"))
        }
    }

    @Test
    fun playlistsGoWithTheirProfileAndItemsWithTheirPlaylist() = runTest {
        helper.createDatabase(3).use { v3 -> v3.execSQL(INSERT_OWNER) }

        helper.runMigrationsAndValidate(4, KeMigrations.all.toList()).use { v4 ->
            v4.execSQL("PRAGMA foreign_keys = ON")
            v4.execSQL("INSERT INTO playlists (id, profileId, name, watchLaterMark, createdAtMillis, updatedAtMillis) VALUES (7, 1, 'Mix', NULL, 1, 1)")
            v4.execSQL(
                "INSERT INTO playlist_items (playlistId, videoId, position, title, durationSeconds, addedAtMillis, updatedAtMillis) " +
                    "VALUES (7, 'aaaaaaaaaaa', 0, 'Title', 60, 1, 1)",
            )
            v4.execSQL("DELETE FROM profiles")
            assertEquals(emptyList<List<String?>>(), v4.rows("SELECT * FROM playlists"))
            assertEquals(emptyList<List<String?>>(), v4.rows("SELECT * FROM playlist_items"))
        }
    }

    /** The Shield's v4 rows from the M5.10 pass (M5P-1): Bijan Bowen twice, Veritasium once. */
    @Test
    fun v4KeepsOneRowPerChannelUnderItsChannelUrl() = runTest {
        helper.createDatabase(4).use { v4 ->
            v4.execSQL(INSERT_OWNER)
            v4.execSQL("INSERT INTO subscriptions VALUES (1, '$BIJAN/videos', 'Bijan Bowen', 'https://img/new', 1791104025908, 1791104025908)")
            v4.execSQL("INSERT INTO subscriptions VALUES (1, '$BIJAN', 'Bijan Bowen', 'https://img/old', 1791104005906, 1791104005906)")
            v4.execSQL("INSERT INTO subscriptions VALUES (1, '$VERITASIUM', 'Veritasium', NULL, 3000, 4000)")
        }

        helper.runMigrationsAndValidate(5, KeMigrations.all.toList()).use { v5 ->
            assertEquals(
                listOf(
                    listOf("1", VERITASIUM, "Veritasium", null, "3000", "4000"),
                    listOf("1", BIJAN, "Bijan Bowen", "https://img/new", "1791104005906", "1791104025908"),
                ),
                v5.rows("SELECT * FROM subscriptions ORDER BY channelUrl"),
            )
        }
    }

    @Test
    fun v4RowsOfOtherProfilesOtherTablesAndHandlesAreKept() = runTest {
        helper.createDatabase(4).use { v4 ->
            v4.execSQL(INSERT_OWNER)
            v4.execSQL(
                "INSERT INTO profiles (id, name, tint, ownerMark, lockIterations, failedTries, createdAtMillis, updatedAtMillis) " +
                    "VALUES (2, 'Yuri', 'teal', NULL, 0, 0, 30, 40)",
            )
            v4.execSQL("INSERT INTO subscriptions VALUES (1, '$BIJAN/videos', 'B', NULL, 10, 10)")
            v4.execSQL("INSERT INTO subscriptions VALUES (2, '$BIJAN', 'B', NULL, 20, 20)")
            v4.execSQL("INSERT INTO subscriptions VALUES (2, '$HANDLE', 'H', NULL, 30, 30)")
            v4.execSQL(
                "INSERT INTO watch_entries (profileId, videoId, positionMs, watched, lastWatchedAtMillis, updatedAtMillis) " +
                    "VALUES (2, 'aaaaaaaaaaa', 5, 0, 1, 1)",
            )
        }

        helper.runMigrationsAndValidate(5, KeMigrations.all.toList()).use { v5 ->
            assertEquals(
                listOf(
                    listOf("1", BIJAN, "B", null, "10", "10"),
                    listOf("2", HANDLE, "H", null, "30", "30"),
                    listOf("2", BIJAN, "B", null, "20", "20"),
                ),
                v5.rows("SELECT * FROM subscriptions ORDER BY profileId, channelUrl"),
            )
            assertEquals(listOf(listOf("2", "aaaaaaaaaaa", "5")), v5.rows("SELECT profileId, videoId, positionMs FROM watch_entries"))
        }
    }

    /** An install that never stored a second URL form (most of them) comes through untouched. */
    @Test
    fun v4RowsAlreadyOnePerChannelAreUnchanged() = runTest {
        val insert = listOf(
            "(1, '$BIJAN', 'Bijan Bowen', 'https://img/b', 10, 11)",
            "(1, '$HANDLE', 'H', NULL, 20, 21)",
            "(2, '$BIJAN', 'Bijan', NULL, 30, 31)",
        )
        helper.createDatabase(4).use { v4 ->
            v4.execSQL(INSERT_OWNER)
            v4.execSQL(
                "INSERT INTO profiles (id, name, tint, ownerMark, lockIterations, failedTries, createdAtMillis, updatedAtMillis) " +
                    "VALUES (2, 'Yuri', 'teal', NULL, 0, 0, 30, 40)",
            )
            insert.forEach { v4.execSQL("INSERT INTO subscriptions VALUES $it") }
        }

        helper.runMigrationsAndValidate(5, KeMigrations.all.toList()).use { v5 ->
            assertEquals(
                listOf(
                    listOf("1", HANDLE, "H", null, "20", "21"),
                    listOf("1", BIJAN, "Bijan Bowen", "https://img/b", "10", "11"),
                    listOf("2", BIJAN, "Bijan", null, "30", "31"),
                ),
                v5.rows("SELECT * FROM subscriptions ORDER BY profileId, channelUrl"),
            )
        }
    }

    @Test
    fun v5KeepsEveryRowAndGainsEmptySettings() = runTest {
        helper.createDatabase(5).use { v5 ->
            v5.execSQL(INSERT_OWNER)
            v5.execSQL("INSERT INTO subscriptions VALUES (1, '$CHANNEL_A', 'A', NULL, 1000, 2000)")
            v5.execSQL(
                "INSERT INTO watch_entries (profileId, videoId, positionMs, watched, lastWatchedAtMillis, updatedAtMillis) " +
                    "VALUES (1, 'aaaaaaaaaaa', 5, 0, 1, 1)",
            )
            v5.execSQL("INSERT INTO playlists (id, profileId, name, watchLaterMark, createdAtMillis, updatedAtMillis) VALUES (7, 1, 'Mix', NULL, 1, 1)")
        }

        helper.runMigrationsAndValidate(6, KeMigrations.all.toList()).use { v6 ->
            assertEquals(listOf(listOf("1", "Owner")), v6.rows("SELECT id, name FROM profiles"))
            assertEquals(listOf(listOf("1", CHANNEL_A, "A", null, "1000", "2000")), v6.rows("SELECT * FROM subscriptions"))
            assertEquals(listOf(listOf("1", "aaaaaaaaaaa", "5")), v6.rows("SELECT profileId, videoId, positionMs FROM watch_entries"))
            assertEquals(listOf(listOf("7", "Mix")), v6.rows("SELECT id, name FROM playlists"))
            assertEquals(emptyList<List<String?>>(), v6.rows("SELECT * FROM profile_settings"))
        }
    }

    @Test
    fun settingsGoWithTheirProfile() = runTest {
        helper.createDatabase(5).use { v5 -> v5.execSQL(INSERT_OWNER) }

        helper.runMigrationsAndValidate(6, KeMigrations.all.toList()).use { v6 ->
            v6.execSQL("PRAGMA foreign_keys = ON")
            v6.execSQL(
                "INSERT INTO profile_settings VALUES (1, 100, 1, NULL, 1, 'button', 'skip', 'skip', 'button', 'button', " +
                    "'button', 'bar', 'button', 'off', 1, 1, 1, 1, 0, 5)",
            )
            v6.execSQL("DELETE FROM profiles")
            assertEquals(emptyList<List<String?>>(), v6.rows("SELECT * FROM profile_settings"))
        }
    }

    @Test
    fun v6KeepsEveryRowGainsEmptyForYouTablesAndForYouIsOn() = runTest {
        helper.createDatabase(6).use { v6 ->
            v6.execSQL(INSERT_OWNER)
            v6.execSQL("INSERT INTO subscriptions VALUES (1, '$CHANNEL_A', 'A', NULL, 1000, 2000)")
            v6.execSQL(
                "INSERT INTO watch_entries (profileId, videoId, positionMs, watched, lastWatchedAtMillis, updatedAtMillis) " +
                    "VALUES (1, 'aaaaaaaaaaa', 5, 0, 1, 1)",
            )
            v6.execSQL("INSERT INTO playlists (id, profileId, name, watchLaterMark, createdAtMillis, updatedAtMillis) VALUES (7, 1, 'Mix', NULL, 1, 1)")
            v6.execSQL(
                "INSERT INTO profile_settings VALUES (1, 150, 0, 'pt-BR', 1, 'button', 'skip', 'skip', 'button', 'button', " +
                    "'button', 'bar', 'button', 'off', 1, 1, 1, 1, 1, 5)",
            )
        }

        helper.runMigrationsAndValidate(7, KeMigrations.all.toList()).use { v7 ->
            assertEquals(listOf(listOf("1", CHANNEL_A, "A", null, "1000", "2000")), v7.rows("SELECT * FROM subscriptions"))
            assertEquals(listOf(listOf("1", "aaaaaaaaaaa", "5")), v7.rows("SELECT profileId, videoId, positionMs FROM watch_entries"))
            assertEquals(listOf(listOf("7", "Mix")), v7.rows("SELECT id, name FROM playlists"))
            assertEquals(
                listOf(listOf("1", "150", "0", "pt-BR", "1", "5", "1")),
                v7.rows("SELECT profileId, speedPercent, resume, captionsLanguage, showShorts, updatedAtMillis, forYou FROM profile_settings"),
            )
            listOf("related_lists", "hidden_videos", "hidden_channels", "impressions", "fetch_attempts").forEach { table ->
                assertEquals(table, emptyList<List<String?>>(), v7.rows("SELECT * FROM $table"))
            }
        }
    }

    /** FY-D4/D6: every For you record but the device-wide request log goes with its profile. */
    @Test
    fun forYouRecordsGoWithTheirProfileAndTheRequestLogStays() = runTest {
        helper.createDatabase(6).use { v6 -> v6.execSQL(INSERT_OWNER) }

        helper.runMigrationsAndValidate(7, KeMigrations.all.toList()).use { v7 ->
            v7.execSQL("PRAGMA foreign_keys = ON")
            v7.execSQL("INSERT INTO related_lists VALUES (1, 'aaaaaaaaaaa', '[]', 5, NULL)")
            v7.execSQL("INSERT INTO hidden_videos VALUES (1, 'bbbbbbbbbbb', 5)")
            v7.execSQL("INSERT INTO hidden_channels VALUES (1, '$CHANNEL_A', 5)")
            v7.execSQL("INSERT INTO impressions VALUES (1, 'ccccccccccc', '$CHANNEL_B', 2, 5, NULL)")
            v7.execSQL("INSERT INTO fetch_attempts (atMillis, outcome) VALUES (5, 'fetched')")

            v7.execSQL("DELETE FROM profiles")

            listOf("related_lists", "hidden_videos", "hidden_channels", "impressions").forEach { table ->
                assertEquals(table, emptyList<List<String?>>(), v7.rows("SELECT * FROM $table"))
            }
            assertEquals(listOf(listOf("5", "fetched")), v7.rows("SELECT atMillis, outcome FROM fetch_attempts"))
        }
    }

    @Test
    fun v7KeepsEveryRowAsAStandardProfileWithNoFilters() = migratedFromV7 { v8 ->
        assertEquals(listOf(listOf("1", "Owner", "0")), v8.rows("SELECT id, name, kids FROM profiles"))
        assertEquals(listOf(listOf("1", CHANNEL_A)), v8.rows("SELECT profileId, channelUrl FROM subscriptions"))
        assertEquals(listOf(listOf("1", "bbbbbbbbbbb")), v8.rows("SELECT profileId, videoId FROM hidden_videos"))
        assertEquals(
            "a kept related list was fetched without the header (KP-D15)",
            listOf(listOf("1", "aaaaaaaaaaa", "[]", null)),
            v8.rows("SELECT profileId, videoId, items, restrictedMode FROM related_lists"),
        )
        assertEquals(
            listOf(listOf("1", "150", "0", null, "0", "0", null, null)),
            v8.rows(
                "SELECT profileId, speedPercent, forYou, restrictedMode, hideLive, hideMembersOnly, minLengthMinutes, " +
                    "maxLengthMinutes FROM profile_settings",
            ),
        )
        listOf("profile_restrictions", "content_rules", "kids_time").forEach { table ->
            assertEquals(table, emptyList<List<String?>>(), v8.rows("SELECT * FROM $table"))
        }
    }

    /** KP-D18: the owner's restrictions, list rules and watch time go with their profile. */
    @Test
    fun filtersAndWatchTimeGoWithTheirProfile() = migratedFromV7 { v8 ->
        v8.execSQL("PRAGMA foreign_keys = ON")
        v8.execSQL(
            "INSERT INTO profile_restrictions (profileId, restrictedMode, hideShorts, hideLive, hideMembersOnly, updatedAtMillis) " +
                "VALUES (1, 'strict', 1, 0, 0, 5)",
        )
        v8.execSQL("INSERT INTO content_rules (profileId, byOwner, kind, value, addedAtMillis) VALUES (1, 1, 'keyword', 'prank', 5)")
        v8.execSQL("INSERT INTO kids_time VALUES (1, '2026-10-10', 600, 15, 1500)")

        v8.execSQL("DELETE FROM profiles")

        listOf("profile_restrictions", "content_rules", "kids_time").forEach { table ->
            assertEquals(table, emptyList<List<String?>>(), v8.rows("SELECT * FROM $table"))
        }
    }

    /** A v7 database with the owner, a subscription, a hidden video, a related list and settings,
     *  migrated to v8. */
    private fun migratedFromV7(check: suspend (SQLiteConnection) -> Unit): TestResult = runTest {
        helper.createDatabase(7).use { v7 ->
            v7.execSQL(INSERT_OWNER)
            v7.execSQL("INSERT INTO subscriptions VALUES (1, '$CHANNEL_A', 'A', NULL, 1000, 2000)")
            v7.execSQL("INSERT INTO hidden_videos VALUES (1, 'bbbbbbbbbbb', 5)")
            v7.execSQL("INSERT INTO related_lists VALUES (1, 'aaaaaaaaaaa', '[]', 5, NULL)")
            v7.execSQL(
                "INSERT INTO profile_settings (profileId, speedPercent, resume, captionsLanguage, sponsorBlock, sb_sponsor, " +
                    "sb_selfpromo, sb_interaction, sb_intro, sb_outro, sb_preview, sb_hook, sb_music_offtopic, sb_filler, " +
                    "skipNote, dearrowTitles, dearrowThumbnails, dislikes, showShorts, forYou, updatedAtMillis) VALUES " +
                    "(1, 150, 0, NULL, 1, 'button', 'skip', 'skip', 'button', 'button', 'button', 'bar', 'button', 'off', " +
                    "1, 1, 1, 1, 0, 0, 5)",
            )
        }

        helper.runMigrationsAndValidate(8, KeMigrations.all.toList()).use { v8 -> check(v8) }
    }

    @Test
    fun v8KeepsEveryRowAndGainsEmptyChannelFeeds() = migratedFromV8 { v9 ->
        assertEquals(listOf(listOf("1", "Owner", "0")), v9.rows("SELECT id, name, kids FROM profiles"))
        assertEquals(listOf(listOf("1", CHANNEL_A)), v9.rows("SELECT profileId, channelUrl FROM subscriptions"))
        assertEquals(listOf(listOf("1", "aaaaaaaaaaa", "strict")), v9.rows("SELECT profileId, videoId, restrictedMode FROM related_lists"))
        assertEquals(listOf(listOf("1", "prank")), v9.rows("SELECT profileId, value FROM content_rules"))
        assertEquals(emptyList<List<String?>>(), v9.rows("SELECT * FROM channel_feeds"))
    }

    @Test
    fun channelFeedsGoWithTheirProfile() = migratedFromV8 { v9 ->
        v9.execSQL("PRAGMA foreign_keys = ON")
        v9.execSQL("INSERT INTO channel_feeds VALUES (1, '$CHANNEL_A', '[]', '[]', 5, 'off')")

        v9.execSQL("DELETE FROM profiles")

        assertEquals(emptyList<List<String?>>(), v9.rows("SELECT * FROM channel_feeds"))
    }

    /** A v8 database with the owner, a subscription, a related list and a list rule, migrated to v9. */
    private fun migratedFromV8(check: suspend (SQLiteConnection) -> Unit): TestResult = runTest {
        helper.createDatabase(8).use { v8 ->
            v8.execSQL(INSERT_OWNER)
            v8.execSQL("INSERT INTO subscriptions VALUES (1, '$CHANNEL_A', 'A', NULL, 1000, 2000)")
            v8.execSQL("INSERT INTO related_lists VALUES (1, 'aaaaaaaaaaa', '[]', 5, NULL, 'strict')")
            v8.execSQL("INSERT INTO content_rules (profileId, byOwner, kind, value, addedAtMillis) VALUES (1, 1, 'keyword', 'prank', 5)")
        }

        helper.runMigrationsAndValidate(9, KeMigrations.all.toList()).use { v9 -> check(v9) }
    }

    @Test
    fun v9WatchEntriesKeepEveryColumnAndAreNotShorts() = runTest {
        helper.createDatabase(9).use { v9 ->
            v9.execSQL(INSERT_OWNER)
            v9.execSQL(
                "INSERT INTO profiles (id, name, tint, lockIterations, failedTries, createdAtMillis, updatedAtMillis, kids) " +
                    "VALUES (2, 'Yuri', 'rose', 0, 0, 10, 20, 0)",
            )
            v9.execSQL(
                "INSERT INTO watch_entries (profileId, videoId, title, uploaderName, uploaderUrl, thumbnailUrl, positionMs, " +
                    "durationMs, watched, lastWatchedAtMillis, updatedAtMillis) " +
                    "VALUES (1, 'aaaaaaaaaaa', 'Title a', 'A', '$CHANNEL_A', 'https://img/a', 60000, 600000, 0, 100, 200)",
            )
            v9.execSQL(
                "INSERT INTO watch_entries (profileId, videoId, title, uploaderName, uploaderUrl, thumbnailUrl, positionMs, " +
                    "durationMs, watched, lastWatchedAtMillis, updatedAtMillis) " +
                    "VALUES (2, 'bbbbbbbbbbb', NULL, NULL, NULL, NULL, 0, NULL, 1, 300, 400)",
            )
        }

        helper.runMigrationsAndValidate(10, KeMigrations.all.toList()).use { v10 ->
            assertEquals(
                listOf(
                    listOf("1", "aaaaaaaaaaa", "Title a", "A", CHANNEL_A, "https://img/a", "60000", "600000", "0", "100", "200", "0"),
                    listOf("2", "bbbbbbbbbbb", null, null, null, null, "0", null, "1", "300", "400", "0"),
                ),
                v10.rows(
                    "SELECT profileId, videoId, title, uploaderName, uploaderUrl, thumbnailUrl, positionMs, durationMs, " +
                        "watched, lastWatchedAtMillis, updatedAtMillis, isShort FROM watch_entries ORDER BY profileId",
                ),
            )
        }
    }

    /** FPK-D17: settings and restrictions keep every column; every profile, a kids one included,
     *  has its packs off. */
    @Test
    fun v10SettingsAndRestrictionsKeepEveryColumnWithPacksOff() = runTest {
        helper.createDatabase(10).use { v10 ->
            v10.execSQL(INSERT_OWNER)
            v10.execSQL(
                "INSERT INTO profiles (id, name, tint, lockIterations, failedTries, createdAtMillis, updatedAtMillis, kids) " +
                    "VALUES (2, 'Zainab', 'violet', 0, 0, 10, 20, 1)",
            )
            v10.execSQL(
                "INSERT INTO profile_settings (profileId, speedPercent, resume, captionsLanguage, sponsorBlock, sb_sponsor, " +
                    "sb_selfpromo, sb_interaction, sb_intro, sb_outro, sb_preview, sb_hook, sb_music_offtopic, sb_filler, " +
                    "skipNote, dearrowTitles, dearrowThumbnails, dislikes, showShorts, forYou, updatedAtMillis, " +
                    "restrictedMode, hideLive, hideMembersOnly, minLengthMinutes, maxLengthMinutes) VALUES " +
                    "(1, 150, 0, 'en', 1, 'button', 'skip', 'skip', 'button', 'button', 'button', 'bar', 'button', 'off', " +
                    "1, 1, 1, 1, 0, 0, 5, 'strict', 1, 0, 2, 60)",
            )
            v10.execSQL(
                "INSERT INTO profile_restrictions VALUES (2, 'strict', 1, 1, 0, NULL, 30, 60, NULL, NULL, NULL, NULL, 90, NULL, " +
                    "1170, 420, 7)",
            )
        }

        helper.runMigrationsAndValidate(11, KeMigrations.all.toList()).use { v11 ->
            assertEquals(
                listOf(listOf("1", "150", "en", "0", "5", "strict", "1", "2", "60")),
                v11.rows(
                    "SELECT profileId, speedPercent, captionsLanguage, forYou, updatedAtMillis, restrictedMode, hideLive, " +
                        "minLengthMinutes, maxLengthMinutes FROM profile_settings",
                ),
            )
            assertEquals(
                listOf(listOf("2", "strict", "1", "1", "30", "60", "90", "1170", "420", "7")),
                v11.rows(
                    "SELECT profileId, restrictedMode, hideShorts, hideLive, maxLengthMinutes, limit_mon, limit_sat, " +
                        "bedtimeStartMinute, bedtimeEndMinute, updatedAtMillis FROM profile_restrictions",
                ),
            )
            val packsOff = listOf("0", "0", "0", "0", "0", "0", "0", "0", null)
            listOf("profile_settings" to "1", "profile_restrictions" to "2").forEach { (table, id) ->
                assertEquals(
                    table,
                    listOf(listOf(id) + packsOff),
                    v11.rows(
                        "SELECT profileId, fp_harmfulForKids, fp_overstimulating, fp_challengesAndVlogs, fp_halalOnly, " +
                            "fp_stageMagic, fp_festivals, fp_lootBoxes, fp_islamicOnly, fp_islamicChannels FROM $table",
                    ),
                )
            }
        }
    }

    /** M8-D12: every profile and playlist keeps its row and gets its own sync id; every Watch
     *  later gets the shared one. */
    @Test
    fun v11RowsKeepEveryColumnAndGetSyncIds() = runTest {
        helper.createDatabase(11).use { v11 ->
            v11.execSQL(INSERT_OWNER)
            v11.execSQL(
                "INSERT INTO profiles (id, name, tint, lockIterations, failedTries, createdAtMillis, updatedAtMillis, kids) " +
                    "VALUES (2, 'Zainab', 'violet', 0, 0, 30, 40, 1)",
            )
            v11.execSQL(
                "INSERT INTO playlists (id, profileId, name, watchLaterMark, createdAtMillis, updatedAtMillis) VALUES " +
                    "(7, 1, 'Mix', NULL, 1, 2), (8, 1, 'Watch later', 1, 3, 4), (9, 2, 'Watch later', 1, 5, 6), " +
                    "(10, 2, 'Mix', NULL, 7, 8)",
            )
            v11.execSQL(
                "INSERT INTO playlist_items (playlistId, videoId, position, title, durationSeconds, addedAtMillis, updatedAtMillis) " +
                    "VALUES (7, 'aaaaaaaaaaa', 0, 'Title', 60, 1, 1)",
            )
        }

        helper.runMigrationsAndValidate(12, KeMigrations.all.toList()).use { v12 ->
            val profiles = v12.rows("SELECT id, name, tint, ownerMark, kids, createdAtMillis, updatedAtMillis, syncId FROM profiles ORDER BY id")
            assertEquals(
                listOf(listOf("1", "Owner", "amber", "1", "0", "10", "20"), listOf("2", "Zainab", "violet", null, "1", "30", "40")),
                profiles.map { it.dropLast(1) },
            )
            val playlists = v12.rows("SELECT id, profileId, name, watchLaterMark, createdAtMillis, updatedAtMillis, syncId FROM playlists ORDER BY id")
            assertEquals(
                listOf(
                    listOf("7", "1", "Mix", null, "1", "2"),
                    listOf("8", "1", "Watch later", "1", "3", "4"),
                    listOf("9", "2", "Watch later", "1", "5", "6"),
                    listOf("10", "2", "Mix", null, "7", "8"),
                ),
                playlists.map { it.dropLast(1) },
            )
            assertEquals(listOf(WATCH_LATER_SYNC_ID, WATCH_LATER_SYNC_ID), listOf(playlists[1].last(), playlists[2].last()))
            val randomIds = profiles.map { it.last() } + listOf(playlists[0].last(), playlists[3].last())
            assertEquals(4, randomIds.toSet().size)
            randomIds.forEach { assertTrue("$it is a random UUID", UUID_FORM.matches(checkNotNull(it))) }
            assertEquals(listOf(listOf("7", "aaaaaaaaaaa")), v12.rows("SELECT playlistId, videoId FROM playlist_items"))
            assertEquals(emptyList<List<String?>>(), v12.rows("SELECT * FROM removals"))
        }
    }

    /** LIVE-D21: a profile's settings keep every column and show live chat beside the video. */
    @Test
    fun v12SettingsKeepEveryColumnWithChatBesideTheVideo() = runTest {
        helper.createDatabase(12).use { v12 ->
            v12.execSQL(
                "INSERT INTO profiles (id, name, tint, ownerMark, lockIterations, failedTries, createdAtMillis, updatedAtMillis, syncId) " +
                    "VALUES (1, 'Owner', 'amber', 1, 0, 0, 10, 20, 'owner-sync-id')",
            )
            v12.execSQL(
                "INSERT INTO profile_settings (profileId, speedPercent, resume, captionsLanguage, sponsorBlock, sb_sponsor, " +
                    "sb_selfpromo, sb_interaction, sb_intro, sb_outro, sb_preview, sb_hook, sb_music_offtopic, sb_filler, " +
                    "skipNote, dearrowTitles, dearrowThumbnails, dislikes, showShorts, forYou, updatedAtMillis, " +
                    "restrictedMode, hideLive, hideMembersOnly, minLengthMinutes, maxLengthMinutes, fp_halalOnly, fp_islamicChannels) VALUES " +
                    "(1, 150, 0, 'en', 1, 'button', 'skip', 'skip', 'button', 'button', 'button', 'bar', 'button', 'off', " +
                    "1, 1, 1, 1, 0, 0, 5, 'strict', 1, 0, 2, 60, 1, '{}')",
            )
        }

        helper.runMigrationsAndValidate(13, KeMigrations.all.toList()).use { v13 ->
            assertEquals(
                listOf(listOf("1", "150", "0", "en", "off", "0", "5", "strict", "1", "2", "60", "1", "{}", "beside")),
                v13.rows(
                    "SELECT profileId, speedPercent, resume, captionsLanguage, sb_filler, forYou, updatedAtMillis, restrictedMode, " +
                        "hideLive, minLengthMinutes, maxLengthMinutes, fp_halalOnly, fp_islamicChannels, chatPlacement FROM profile_settings",
                ),
            )
        }
    }

    /** ARCH-8's all-migrations test: a v1 install reaches the current schema with its rows. */
    @Test
    fun aV1InstallReachesTheCurrentSchemaWithEachChannelOnceUnderTheOwner() = runTest {
        helper.createDatabase(1).use { v1 ->
            v1.execSQL("INSERT INTO subscriptions VALUES ('$CHANNEL_A', 'A', 'https://img/a', 1000, 2000)")
            v1.execSQL("INSERT INTO subscriptions VALUES ('$CHANNEL_A/videos', 'A', NULL, 3000, 3000)")
        }

        helper.runMigrationsAndValidate(KeDatabase.SCHEMA_VERSION, KeMigrations.all.toList()).use { current ->
            assertEquals(
                listOf(listOf("Owner", CHANNEL_A, "A", "https://img/a", "1000", "3000")),
                current.rows(
                    "SELECT p.name, s.channelUrl, s.name, s.avatarUrl, s.subscribedAtMillis, s.updatedAtMillis " +
                        "FROM subscriptions s JOIN profiles p ON p.id = s.profileId WHERE p.ownerMark IS NOT NULL",
                ),
            )
        }
    }
}

/** Every row of [sql] as text columns (`null` for SQL NULL). */
private fun SQLiteConnection.rows(sql: String): List<List<String?>> = prepare(sql).use { statement ->
    buildList {
        while (statement.step()) {
            add(List(statement.getColumnCount()) { i -> if (statement.isNull(i)) null else statement.getText(i) })
        }
    }
}

/** The owner (id 1) in a v2+ schema. */
private const val INSERT_OWNER =
    "INSERT INTO profiles (id, name, tint, ownerMark, lockIterations, failedTries, createdAtMillis, updatedAtMillis) " +
        "VALUES (1, 'Owner', 'amber', 1, 0, 0, 10, 20)"

private const val CHANNEL_A = "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"
private const val CHANNEL_B = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"
private const val BIJAN = "https://www.youtube.com/channel/UCOCahKBCEUuzDJawM7yN1dg"
private const val VERITASIUM = "https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"
private const val HANDLE = "https://www.youtube.com/@BijanBowen"
private val UUID_FORM = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
