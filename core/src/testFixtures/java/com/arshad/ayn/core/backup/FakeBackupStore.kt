package com.arshad.ayn.core.backup

import android.net.Uri
import com.arshad.ayn.core.lock.OwnerPass
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [BackupStore] for the layers above it: [reads] answers [open] per file, [plan]
 * answers [restorePlan] (by default every profile added, room for all, not a first launch),
 * [restoreResult] answers [restore] (held by [restoreGate] when set), and every restore is
 * recorded in [restored] with its choices in [restoredChoices]. [openGate] and [exportGate], when
 * set, hold every [open] and [export] (the export counted first). [exportResult] answers [export]
 * (counted in [exports], its profiles in [exportedIds]), [saved] is what [savedBackups] lists, and
 * [importResult] answers [importSubscriptions] (each file recorded in [imported] and its profile
 * in [importedInto], then held by [importGate] when set). [deleteBackup] takes the backup out of
 * [saved] (each ask recorded in [deleted], held by [deleteGate] when set) unless [deleteFails].
 */
class FakeBackupStore : BackupStore {
    val reads: MutableMap<Uri, BackupRead> = mutableMapOf()
    val restored: MutableList<ValidBackup> = mutableListOf()
    val restoredChoices: MutableList<List<RestoreChoice>> = mutableListOf()
    var plan: (ValidBackup) -> RestorePlan? = { backup ->
        RestorePlan(firstLaunch = false, roomForNew = backup.profiles.size, profiles = backup.profiles.map { PlannedProfile(it, replaces = null) })
    }
    var restoreResult: RestoreResult = RestoreResult.Restored(ownerId = 1)
    var restoreGate: CompletableDeferred<Unit>? = null
    var openGate: CompletableDeferred<Unit>? = null
    var exportResult: ExportResult = ExportResult.Failed
    var exportGate: CompletableDeferred<Unit>? = null
    var exports: Int = 0
    val exportedIds: MutableList<Set<Long>?> = mutableListOf()
    val saved: MutableList<SavedBackup> = mutableListOf()
    var importResult: ImportResult = ImportResult.Unreadable
    val imported: MutableList<Uri> = mutableListOf()
    val importedInto: MutableList<Long> = mutableListOf()
    var importGate: CompletableDeferred<Unit>? = null
    val deleted: MutableList<Uri> = mutableListOf()
    var deleteFails: Boolean = false
    var deleteGate: CompletableDeferred<Unit>? = null

    override suspend fun export(owner: OwnerPass, profileIds: Set<Long>?): ExportResult {
        exports++
        exportedIds += profileIds
        exportGate?.await()
        return exportResult
    }

    override suspend fun savedBackups(): List<SavedBackup> = saved.toList()

    override suspend fun open(uri: Uri): BackupRead {
        openGate?.await()
        return reads[uri] ?: BackupRead.Refused(BackupRefusal.Unreadable)
    }

    override suspend fun deleteBackup(owner: OwnerPass, uri: Uri): Boolean {
        deleted += uri
        deleteGate?.await()
        return !deleteFails && saved.removeAll { it.uri == uri }
    }

    override suspend fun restorePlan(backup: ValidBackup): RestorePlan? = plan(backup)

    override suspend fun restore(owner: OwnerPass, backup: ValidBackup, choices: List<RestoreChoice>): RestoreResult {
        restoreGate?.await()
        restored += backup
        restoredChoices += choices
        return restoreResult
    }

    override suspend fun importSubscriptions(uri: Uri, profileId: Long): ImportResult {
        imported += uri
        importedInto += profileId
        importGate?.await()
        return importResult
    }
}

/** A valid backup holding profiles named [names], the first the owner unless [withOwner] is false
 *  (an export that left the owner out, KP-D24). */
fun testBackup(vararg names: String, exportedAtMillis: Long = 0, withOwner: Boolean = true): ValidBackup = ValidBackup(
    BackupBundle(
        bundleVersion = 1,
        app = "Ayn",
        exportedAtMillis = exportedAtMillis,
        profiles = names.mapIndexed { index, name ->
            BackupProfile(
                name = name,
                tint = "amber",
                owner = withOwner && index == 0,
                createdAtMillis = 0,
                updatedAtMillis = 0,
                subscriptions = emptyList(),
                history = emptyList(),
                playlists = emptyList(),
            )
        },
    ),
)
