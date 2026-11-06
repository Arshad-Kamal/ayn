package com.arshad.ayn

import com.arshad.ayn.core.store.KeDatabase
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5-D2: Android refuses to install a lower `versionCode` over a higher one, which only keeps an
 *  older build off a newer database while the code keeps pace with the schema. */
class VersionCodeTest {

    @Test
    fun theVersionCodeIsNeverBelowTheDatabaseSchemaVersion() {
        assertTrue(
            "versionCode ${BuildConfig.VERSION_CODE} < schema ${KeDatabase.SCHEMA_VERSION}: raise versionCode in app/build.gradle.kts",
            BuildConfig.VERSION_CODE >= KeDatabase.SCHEMA_VERSION,
        )
    }
}
