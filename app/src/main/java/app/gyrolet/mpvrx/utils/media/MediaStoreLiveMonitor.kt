/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import app.gyrolet.mpvrx.utils.storage.MediaStoreGenerationGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground-only MediaStore observer. Reacts to indexed media changes without periodically
 * walking the entire filesystem. On foreground return compares generations to recover missed
 * changes while the observer was unregistered.
 */
class MediaStoreLiveMonitor(
  private val context: Context,
  private val scope: CoroutineScope,
) {
  private val handler = Handler(Looper.getMainLooper())
  private val publishScheduled = AtomicBoolean(false)
  private val publish = Runnable {
    publishScheduled.set(false)
    if (registered) MediaLibraryEvents.notifyChanged() else pendingRefresh = true
  }
  private var registered = false
  private var pendingRefresh = false
  private var lastGeneration: String? = null
  private val observer = object : ContentObserver(handler) {
    override fun onChange(selfChange: Boolean) = schedule()
    override fun onChange(selfChange: Boolean, uri: android.net.Uri?) = schedule()
  }

  private fun schedule() {
    if (!registered) {
      pendingRefresh = true
      return
    }
    // A trailing debounce never fires while a downloader keeps adding/updating rows.
    // Coalesce a bounded window instead, so an ongoing import can still become visible.
    if (publishScheduled.compareAndSet(false, true)) handler.postDelayed(publish, 350L)
  }

  fun start() {
    if (registered) return
    registered = true
    try {
      val resolver = context.contentResolver
      resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
      resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
    } catch (error: Exception) {
      Log.w(TAG, "Could not register media observers", error)
      stop()
    }
    if (pendingRefresh) {
      pendingRefresh = false
      schedule()
    }
    val previous = lastGeneration
    scope.launch(Dispatchers.IO) {
      // Compare against the playable library only: a raw generation also moves for every photo,
      // screenshot or download, which used to force a full rescan on each foreground return.
      val changed = previous != null && !MediaStoreGenerationGuard.isTokenCurrent(context, previous)
      val current = if (previous == null || changed) MediaStoreGenerationGuard.currentToken(context) else previous
      handler.post {
        if (!registered) return@post
        if (changed) schedule()
        if (current != null) lastGeneration = current
      }
    }
  }

  fun stop() {
    if (!registered) return
    registered = false
    runCatching { context.contentResolver.unregisterContentObserver(observer) }
    handler.removeCallbacks(publish)
    if (publishScheduled.getAndSet(false)) pendingRefresh = true
  }

  private companion object {
    const val TAG = "MediaStoreLiveMonitor"
  }
}

