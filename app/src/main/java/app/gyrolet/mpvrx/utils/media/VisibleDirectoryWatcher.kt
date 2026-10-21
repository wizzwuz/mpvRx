/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.media.MediaScannerConnection
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Watches only the directory currently visible in a browser, never an entire storage tree.
 * Coalesces writes/renames without waiting for a long import to finish. Stop when leaving RESUMED.
 */
class VisibleDirectoryWatcher(private val onChanged: () -> Unit) {
  private val handler = Handler(Looper.getMainLooper())
  private val dispatchScheduled = AtomicBoolean(false)
  private val dispatch = Runnable {
    dispatchScheduled.set(false)
    onChanged()
  }
  private var observer: FileObserver? = null
  private var watchedPath: String? = null
  @Volatile private var scanContext: Context? = null
  private val completedPaths = linkedSetOf<String>()
  private val scanScheduled = AtomicBoolean(false)
  private val scanCompleted = Runnable { scanCompletedFiles() }

  fun watch(path: String, context: Context? = null) {
    if (context != null) scanContext = context.applicationContext
    if (path == watchedPath) return
    stop()
    val directory = File(path)
    if (!directory.isDirectory || !directory.canRead()) return
    val next = object : FileObserver(path, EVENTS) {
      override fun onEvent(event: Int, path: String?) {
        if ((event and EVENTS) == 0) return
        // Index final names only: CREATE may be an empty file or an unfinished download.
        if (!BuildConfig.SCOPED_STORAGE_ONLY && scanContext != null && path != null &&
          (event and (FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO)) != 0) {
          val file = File(directory, path)
          if (file.isFile && (FileTypeUtils.isVideoFile(file) || FileTypeUtils.isAudioFile(file))) {
            synchronized(completedPaths) { completedPaths += file.absolutePath }
            if (scanScheduled.compareAndSet(false, true)) handler.postDelayed(scanCompleted, 350L)
          }
        }
        if (dispatchScheduled.compareAndSet(false, true)) handler.postDelayed(dispatch, 350L)
      }
    }
    try {
      next.startWatching()
      observer = next
      watchedPath = path
    } catch (error: Exception) {
      Log.w(TAG, "Cannot watch $path", error)
    }
  }

  fun stop() {
    handler.removeCallbacks(dispatch)
    dispatchScheduled.set(false)
    observer?.stopWatching()
    observer = null
    watchedPath = null
    // Navigation must not discard completed files before the other browser modes can see them.
    handler.removeCallbacks(scanCompleted)
    scanCompletedFiles()
  }

  private fun scanCompletedFiles() {
    scanScheduled.set(false)
    val context = scanContext ?: return
    val paths = synchronized(completedPaths) {
      completedPaths.toTypedArray().also { completedPaths.clear() }
    }
    if (paths.isEmpty()) return
    val remaining = AtomicInteger(paths.size)
    runCatching {
      MediaScannerConnection.scanFile(context, paths, null) { _, _ ->
        if (remaining.decrementAndGet() == 0) MediaLibraryEvents.notifyChanged()
      }
    }.onFailure { Log.w(TAG, "Could not index completed media files", it) }
  }

  private companion object {
    const val TAG = "VisibleDirectoryWatcher"
    val EVENTS = FileObserver.CREATE or FileObserver.DELETE or
      FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or
      FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
  }
}

