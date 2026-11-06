package com.arshad.ayn.core.store

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver

/**
 * The app's one database for the D4 record collections (ARCH-6). Every record belongs to a
 * profile ([ProfileEntity], B-D9) and goes with it, except For you's device-wide request log
 * ([FetchAttemptEntity]) and the removal records for sync ([RemovalEntity]). Schemas are exported
 * to `core/schemas` (ARCH-8); each version bump ships a migration ([KeMigrations]) with a
 * `MigrationTestHelper` test, and `fallbackToDestructiveMigration` is never used on user data.
 */
@Database(
    entities = [
        ProfileEntity::class,
        SubscriptionEntity::class,
        WatchEntryEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        ProfileSettingsEntity::class,
        RelatedListEntity::class,
        HiddenVideoEntity::class,
        HiddenChannelEntity::class,
        ImpressionEntity::class,
        FetchAttemptEntity::class,
        ProfileRestrictionsEntity::class,
        ContentRuleEntity::class,
        KidsTimeEntity::class,
        ChannelFeedEntity::class,
        RemovalEntity::class,
    ],
    version = KeDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class KeDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    abstract fun subscriptionDao(): SubscriptionDao

    abstract fun watchDao(): WatchDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun profileSettingsDao(): ProfileSettingsDao

    abstract fun relatedListDao(): RelatedListDao

    abstract fun fetchAttemptDao(): FetchAttemptDao

    abstract fun forYouDao(): ForYouDao

    abstract fun filterDao(): FilterDao

    abstract fun kidsTimeDao(): KidsTimeDao

    abstract fun channelFeedDao(): ChannelFeedDao

    abstract fun removalDao(): RemovalDao

    companion object {
        const val NAME = "ke.db"

        /** The app's `versionCode` never falls below it (M5-D2; an `:app` test checks), so Android
         *  refuses an older build over a database it couldn't open. */
        const val SCHEMA_VERSION = 13

        /** Opens [NAME] in the app's databases directory, where the Room 2 builds wrote it, so
         *  existing installs open in place and migrate. Runs on the platform SQLite those builds
         *  used, not a bundled copy. */
        fun open(context: Context): KeDatabase =
            Room.databaseBuilder<KeDatabase>(context, NAME)
                .setDriver(AndroidSQLiteDriver())
                .addMigrations(*KeMigrations.all)
                .build()
    }
}

/** Room's row id for an `OnConflictStrategy.IGNORE` insert that wrote nothing. */
internal const val NOT_INSERTED = -1L
