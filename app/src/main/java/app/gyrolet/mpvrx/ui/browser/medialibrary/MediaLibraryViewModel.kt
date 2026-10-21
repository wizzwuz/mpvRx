/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.medialibrary

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.preferences.HiddenScanScope
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.ui.browser.base.BaseBrowserViewModel
import app.gyrolet.mpvrx.ui.browser.videolist.VideoWithPlaybackInfo
import app.gyrolet.mpvrx.ui.browser.videolist.buildVideoWithPlaybackInfo
import app.gyrolet.mpvrx.ui.browser.videolist.videoPlaybackIdentifiers
import app.gyrolet.mpvrx.utils.media.MetadataRetrieval
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.media.PlaybackStateOps
import app.gyrolet.mpvrx.utils.storage.MediaStoreGenerationGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MediaLibraryViewModel(
  application: Application,
) : BaseBrowserViewModel(application),
  KoinComponent {
  private val appearancePreferences: AppearancePreferences by inject()
  private val browserPreferences: BrowserPreferences by inject()
  private val foldersPreferences: FoldersPreferences by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()

  private enum class DeletionState { Pending, Confirmed }

  private val hiddenVideoPaths = MutableStateFlow<Map<String, DeletionState>>(emptyMap())
  private val scanRequests = Channel<Unit>(Channel.CONFLATED)
  private val forceNextScan = AtomicBoolean(false)
  private val scanRevision = AtomicLong()
  @Volatile private var enrichmentJob: Job? = null
  private val _videos = MutableStateFlow<List<Video>>(emptyList())
  val videos: StateFlow<List<Video>> =
    combine(
      _videos,
      hiddenVideoPaths,
      foldersPreferences.blacklistedFolders.changes(),
      foldersPreferences.blacklistedAudioFolders.changes(),
    ) { items, hidden, videoBlacklist, audioBlacklist ->
      items.filter { isVisible(it, hidden, videoBlacklist, audioBlacklist) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  private val _videosWithPlaybackInfo = MutableStateFlow<List<VideoWithPlaybackInfo>>(emptyList())
  val videosWithPlaybackInfo: StateFlow<List<VideoWithPlaybackInfo>> =
    combine(
      _videosWithPlaybackInfo,
      hiddenVideoPaths,
      foldersPreferences.blacklistedFolders.changes(),
      foldersPreferences.blacklistedAudioFolders.changes(),
    ) { items, hidden, videoBlacklist, audioBlacklist ->
      items.filter { isVisible(it.video, hidden, videoBlacklist, audioBlacklist) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  private val _isLoading = MutableStateFlow(true)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
  private var playbackIndexByIdentifier: Map<String, Int> = emptyMap()

  private val tag = "MediaLibraryViewModel"

  private fun isVisible(
    video: Video,
    hidden: Map<String, DeletionState>,
    videoBlacklist: Set<String>,
    audioBlacklist: Set<String>,
  ): Boolean {
    if (video.path in hidden) return false
    val blacklist = if (video.isAudio) audioBlacklist else videoBlacklist
    return blacklist.none { folder ->
      app.gyrolet.mpvrx.utils.storage.isMediaPathInFolder(video.bucketId, folder) ||
        app.gyrolet.mpvrx.utils.storage.isMediaPathInFolder(video.path, folder)
    }
  }

  init {
    // Try the persisted list first so an unchanged library needs no scan at all. Without this, every
    // cold boot queried MediaStore once per folder before the screen had anything to show.
    val cacheRestore = viewModelScope.launch(Dispatchers.IO) {
      val restored = loadCachedVideos()
      if (!restored) {
        loadData()
      } else if (foldersPreferences.hiddenScanEnabled(HiddenScanScope.LIBRARY)) {
        // MediaStore generations cannot detect changes inside .nomedia or dot folders. Display
        // the cached list now and verify the hidden sources without blocking the first frame.
        loadData(force = true)
      }
    }
    viewModelScope.launch(Dispatchers.IO) {
      cacheRestore.join()
      for (request in scanRequests) {
        scanIndexedVideos(forceNextScan.getAndSet(false), scanRevision.get())
      }
    }
    viewModelScope.launch(Dispatchers.IO) {
      val hiddenPreferences = combine(
        foldersPreferences.includeNoMediaFolders.changes(),
        foldersPreferences.libraryHiddenScanEnabled.changes(),
        foldersPreferences.hiddenFolderMarkerNames.changes(),
        foldersPreferences.sharedScanMarkedFolders.changes(),
        foldersPreferences.sharedScanDotFolders.changes(),
        foldersPreferences.sharedIgnoredMarkers.changes(),
      ) { _ -> Unit }.drop(1)
      merge(
        app.gyrolet.mpvrx.utils.media.MediaLibraryEvents.changes.map { true },
        hiddenPreferences.map { false },
      ).collectLatest { mediaChanged ->
        // Changing Library's scan policy must not destroy the saved Tree snapshot.
        MediaLibraryCache.clear(getApplication())
        MediaFileRepository.invalidateFolderCache()
        if (mediaChanged) MediaStoreGenerationGuard.invalidate(getApplication())
        loadData(force = true)
      }
    }
    viewModelScope.launch(Dispatchers.IO) {
      PlaybackStateEvents.changes.collectLatest { mediaIdentifier ->
        if (_videos.value.isNotEmpty()) updatePlaybackInfo(mediaIdentifier)
      }
    }
  }

  /**
   * A mode-specific key prevents a snapshot created with one hidden-folder policy from being
   * presented under another. Full video metadata is persisted separately from this key.
   */
  private fun currentLibraryCacheKey(): String =
    "library_v3|scoped=${BuildConfig.SCOPED_STORAGE_ONLY}" +
      "|hidden=${foldersPreferences.libraryHiddenScanEnabled.get()}" +
      "|nomedia=${foldersPreferences.includeNoMediaFolders.get()}" +
      "|marked=${foldersPreferences.sharedScanMarkedFolders.get()}" +
      "|dot=${foldersPreferences.sharedScanDotFolders.get()}" +
      "|markers=${foldersPreferences.hiddenFolderMarkerNames.get().sorted().joinToString(",")}" +
      "|ignored=${foldersPreferences.sharedIgnoredMarkers.get().sorted().joinToString(",")}" +
      "|uris=${foldersPreferences.scopedHiddenFolderUris.get().sorted().joinToString(",")}" +
      "|audioMin=${browserPreferences.minimumAudioDurationSeconds.get()}"

  /** Show a validated saved list immediately; background work must never blank it out. */
  private fun loadCachedVideos(): Boolean {
    val cached =
      MediaLibraryCache.load(
        getApplication(),
        scanOptionsKey = currentLibraryCacheKey(),
        requireMetadata = MetadataRetrieval.isVideoMetadataNeeded(browserPreferences),
      ) ?: return false
    publishVideos(cached)
    _isLoading.value = false
    viewModelScope.launch(Dispatchers.IO) {
      loadPlaybackInfo(cached)
    }
    return true
  }

  private fun loadData(force: Boolean = false) {
    if (force) forceNextScan.set(true)
    scanRevision.incrementAndGet()
    enrichmentJob?.cancel()
    scanRequests.trySend(Unit)
  }

  /** Conflated requests finish their quick indexed query instead of queuing full library scans. */
  private suspend fun scanIndexedVideos(force: Boolean, revision: Long) {
    try {
      enrichmentJob?.cancel()
      if (!force &&
        !foldersPreferences.hiddenScanEnabled(HiddenScanScope.LIBRARY) &&
        _videos.value.isNotEmpty() &&
        MediaStoreGenerationGuard.isUnchangedSinceLastScan(getApplication())
      ) return

      val observedGeneration = MediaStoreGenerationGuard.currentToken(getApplication())
      val scanCacheKey = currentLibraryCacheKey()
      val metadataRequired = MetadataRetrieval.isVideoMetadataNeeded(browserPreferences)
      if (_videos.value.isEmpty()) _isLoading.value = true
      val indexed = MediaFileRepository.getIndexedVideos(getApplication(), includeAudioOverride = true)
      currentCoroutineContext().ensureActive()
      val immediate = MetadataRetrieval.restoreCachedContentMetadata(
        MediaFileRepository.mergeVideos(indexed, _videos.value), metadataCache,
      )
      currentCoroutineContext().ensureActive()
      if (immediate.isNotEmpty()) {
        publishVideos(immediate)
        _isLoading.value = false
      }

      // Discovery, SAF providers and per-file metadata must not block the next indexed refresh.
      if (revision != scanRevision.get()) return
      enrichmentJob = viewModelScope.launch(Dispatchers.IO) {
        try {
          if (revision != scanRevision.get()) return@launch
          val complete = MediaFileRepository.getAllVideos(getApplication(), includeAudioOverride = true)
          currentCoroutineContext().ensureActive()
          var videoList = MetadataRetrieval.restoreCachedContentMetadata(
            MediaFileRepository.mergeVideos(indexed, complete), metadataCache,
          )
          if (revision != scanRevision.get()) return@launch
          publishVideos(videoList)
          _isLoading.value = false
          val scannedPaths = videoList.mapTo(hashSetOf()) { it.path }
          hiddenVideoPaths.update { states ->
            states.filter { (path, state) -> state == DeletionState.Pending || path in scannedPaths }
          }
          saveSnapshot(videoList, scanCacheKey, observedGeneration, metadataEnriched = false)
          loadPlaybackInfo(videoList)

          if (metadataRequired) {
            videoList = MetadataRetrieval.enrichVideosIfNeeded(
              context = getApplication(),
              videos = videoList,
              browserPreferences = browserPreferences,
              metadataCache = metadataCache,
            )
            currentCoroutineContext().ensureActive()
            if (revision != scanRevision.get()) return@launch
            publishVideos(videoList)
            loadPlaybackInfo(videoList)
            saveSnapshot(videoList, scanCacheKey, observedGeneration, metadataEnriched = true)
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          Log.e(tag, "Error verifying media library", error)
        } finally {
          if (revision == scanRevision.get()) _isLoading.value = false
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      Log.e(tag, "Error refreshing indexed media", error)
      _isLoading.value = false
    }
  }

  /** Publish card models together with rows; playback history and metadata fill in afterwards. */
  private fun publishVideos(videos: List<Video>) {
    val previous = _videosWithPlaybackInfo.value.associateBy { it.video.path }
    val now = System.currentTimeMillis()
    val thresholdDays = appearancePreferences.unplayedOldVideoDays.get()
    val watchedThreshold = browserPreferences.watchedThreshold.get()
    playbackIndexByIdentifier = buildMap(videos.size * 4) {
      videos.forEachIndexed { index, video ->
        videoPlaybackIdentifiers(video).forEach { identifier -> put(identifier, index) }
      }
    }
    _videos.value = videos
    _videosWithPlaybackInfo.value = videos.map { video ->
      previous[video.path]?.copy(video = video) ?: buildVideoWithPlaybackInfo(
        video, null, now, thresholdDays, watchedThreshold,
      )
    }
  }

  private fun saveSnapshot(
    videos: List<Video>,
    key: String,
    generation: String?,
    metadataEnriched: Boolean,
  ) {
    MediaLibraryCache.save(
      getApplication(),
      videos.filterNot { hiddenVideoPaths.value[it.path] == DeletionState.Confirmed },
      scanOptionsKey = key,
      observedGeneration = generation,
      metadataEnriched = metadataEnriched,
    )
    MediaStoreGenerationGuard.remember(getApplication(), generation)
  }

  override suspend fun deleteVideos(videos: List<Video>): Pair<Int, Int> {
    val targets = videos.distinctBy { it.path }.filterNot { it.path in hiddenVideoPaths.value }
    if (targets.isEmpty()) return 0 to 0
    val paths = targets.mapTo(hashSetOf()) { it.path }
    hiddenVideoPaths.update { it + paths.associateWith { DeletionState.Pending } }
    return try {
      super.deleteVideos(targets) { video ->
        hiddenVideoPaths.update { it + (video.path to DeletionState.Confirmed) }
      }
    } finally {
      hiddenVideoPaths.update { states ->
        states.filterNot { (path, state) -> path in paths && state == DeletionState.Pending }
      }
    }
  }

  override fun refresh() {
    // A user-driven refresh must never be satisfied by the persisted list.
    MediaLibraryCache.clear(getApplication())
    MediaFileRepository.invalidateFolderCache()
    MediaStoreGenerationGuard.invalidate(getApplication())
    loadData(force = true)
  }

  private suspend fun loadPlaybackInfo(videos: List<Video>) {
    val playbackStates = playbackStateRepository.getAllPlaybackStates()
    currentCoroutineContext().ensureActive()
    if (_videos.value != videos) return
    val currentTime = System.currentTimeMillis()
    val thresholdDays = appearancePreferences.unplayedOldVideoDays.get()
    val watchedThreshold = browserPreferences.watchedThreshold.get()
    val playbackByTitle = playbackStates.associateBy { it.mediaTitle }
    val playbackIndex =
      buildMap(videos.size * 4) {
        videos.forEachIndexed { index, video ->
          videoPlaybackIdentifiers(video).forEach { identifier -> put(identifier, index) }
        }
      }

    val videosWithInfo =
      videos.map { video ->
        buildVideoWithPlaybackInfo(
          video = video,
          playbackState = videoPlaybackIdentifiers(video).firstNotNullOfOrNull(playbackByTitle::get),
          currentTimeMillis = currentTime,
          newLabelDays = thresholdDays,
          watchedThreshold = watchedThreshold,
        )
      }
    currentCoroutineContext().ensureActive()
    if (_videos.value != videos) return
    playbackIndexByIdentifier = playbackIndex
    _videosWithPlaybackInfo.value = videosWithInfo
  }

  private suspend fun updatePlaybackInfo(mediaIdentifier: String) {
    if (mediaIdentifier.isBlank()) {
      loadPlaybackInfo(_videos.value)
      return
    }

    val index = playbackIndexByIdentifier[mediaIdentifier] ?: return
    val videos = _videos.value
    val video = videos.getOrNull(index) ?: return
    if (mediaIdentifier !in videoPlaybackIdentifiers(video)) {
      loadPlaybackInfo(videos)
      return
    }
    val currentItems = _videosWithPlaybackInfo.value
    if (currentItems.size != videos.size || currentItems.getOrNull(index)?.video?.path != video.path) {
      loadPlaybackInfo(videos)
      return
    }

    val updatedItem =
      buildVideoWithPlaybackInfo(
        video = video,
        playbackState = playbackStateRepository.getVideoDataByTitle(mediaIdentifier),
        currentTimeMillis = System.currentTimeMillis(),
        newLabelDays = appearancePreferences.unplayedOldVideoDays.get(),
        watchedThreshold = browserPreferences.watchedThreshold.get(),
      )
    if (currentItems[index] == updatedItem) return
    currentCoroutineContext().ensureActive()
    if (_videos.value != videos || _videosWithPlaybackInfo.value != currentItems) return

    _videosWithPlaybackInfo.value =
      currentItems.toMutableList().apply {
        this[index] = updatedItem
      }
  }

  fun setWatched(video: Video, watched: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
      PlaybackStateOps.setWatched(video, watched)
    }
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MediaLibraryViewModel(application) as T
      }
  }
}

