package com.arshad.ayn.feature.library

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

internal const val LIBRARY_LOG_TAG = "KeLibrary"

/**
 * Runs a call and returns its result; a failure is logged under [tag] and returns `null`, leaving
 * the screen as it was. The Library's and Home's writes are the user's own edits on local data,
 * retried by doing them again; Home's related-list save is For you's cache, written again by the
 * next play or Home load; the parent review's writes and uploads load are retried from its page.
 */
internal suspend fun <T> attempt(what: String, tag: String = LIBRARY_LOG_TAG, call: suspend () -> T): T? =
    try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "$what failed: ${e.javaClass.simpleName}")
        null
    }

/** This flow, ending in [failed] (logged) when the store can't be read. */
internal fun <T> Flow<T>.orFailed(what: String, failed: T): Flow<T> = catch { e ->
    Log.w(LIBRARY_LOG_TAG, "$what failed: ${e.javaClass.simpleName}")
    emit(failed)
}
