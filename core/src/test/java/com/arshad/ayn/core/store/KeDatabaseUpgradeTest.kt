package com.arshad.ayn.core.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Installs built before Room 3 hold a `ke.db` written by Room 2.8. The file is rebuilt here
 * from Room 2's own setup SQL (the committed `core/schemas/.../1.json` as Room 2.8.4 exported
 * it), including its identity hash, so a hash or schema drift fails Room's integrity check in
 * this test instead of on a device holding subscriptions; opening it runs every migration the
 * app ships, as an upgrading install does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeDatabaseUpgradeTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var database: KeDatabase? = null

    @After
    fun closeDatabase() {
        database?.close()
    }

    @Test
    fun aRoom2VersionOneFileOpensInPlaceWithItsRowsUnderTheOwner() = runTest {
        writeRoom2VersionOneFile(
            "INSERT INTO subscriptions VALUES ('$CHANNEL', 'Channel', 'https://img/a', 1000, 2000)",
        )

        // The container's start-up path: ensure the owner, select it, read through the store.
        val opened = KeDatabase.open(context).also { database = it }
        val profiles = ProfileRepository(opened)
        val session = ProfileSession().apply { select(profiles.ensureOwner().id) }
        val rows = SubscriptionRepository(opened, session).subscriptions().first()

        assertEquals(listOf(Subscription(CHANNEL, "Channel", "https://img/a", 1000, 2000)), rows)
        assertEquals(listOf(Profile(1, "Owner", ProfileTint.Amber, isOwner = true, lock = null)), profiles.profiles().first())
    }

    private fun writeRoom2VersionOneFile(insertSql: String) {
        val file = context.getDatabasePath(KeDatabase.NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // Room 2's default journal mode (AUTOMATIC) is WAL on the devices holding installs.
            db.enableWriteAheadLogging()
            ROOM2_V1_SETUP.forEach(db::execSQL)
            db.execSQL(insertSql)
            db.version = 1
        }
    }
}

private const val CHANNEL = "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"

private val ROOM2_V1_SETUP = listOf(
    "CREATE TABLE IF NOT EXISTS `subscriptions` (`channelUrl` TEXT NOT NULL, `name` TEXT NOT NULL, " +
        "`avatarUrl` TEXT, `subscribedAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, " +
        "PRIMARY KEY(`channelUrl`))",
    "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
    "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'e870e804a4d573bbff42fc08f10bda75')",
)
