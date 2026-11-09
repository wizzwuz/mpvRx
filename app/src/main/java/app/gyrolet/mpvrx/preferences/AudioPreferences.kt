/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import androidx.annotation.StringRes
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEnum
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class AudioPreferences(
  preferenceStore: PreferenceStore,
) {
  val preferredLanguages = preferenceStore.getString("audio_preferred_languages")
  val defaultAudioDelay = preferenceStore.getInt("audio_delay_default")
  val pickerPath = preferenceStore.getString("audio_picker_path")
  val audioPitchCorrection = preferenceStore.getBoolean("audio_pitch_correction", true)
  val audioChannels = preferenceStore.getEnum("audio_channels", AudioChannels.AutoSafe)
  val volumeBoostCap = preferenceStore.getInt("audio_volume_boost_cap", 30)
  val ignoreAudioFocus = preferenceStore.getBoolean("ignore_audio_focus", false)
  val backgroundPlayback = preferenceStore.getBoolean("automatic_background_playback", false)
  val backgroundPlaybackBehavior = preferenceStore.getEnum("background_playback_behavior", BackgroundPlaybackBehavior.Remember)
  private val videoBackgroundPlaybackIds = preferenceStore.getStringSet("background_playback_video_ids", emptySet())
  private val videoBackgroundPlaybackLock = Any()
  /** Audio-player-only background playback; video retains [backgroundPlayback]. */
  val audioBackgroundPlayback = preferenceStore.getBoolean("audio_player_background_playback", true)
  val miniPlayerTrackSwitching = preferenceStore.getBoolean("audio_mini_player_track_switching", false)
  val volumeNormalization = preferenceStore.getBoolean("audio_volume_normalization", false)
  val spatialAudio = preferenceStore.getBoolean("audio_spatial_audio", false)
  val omniphonyEnabled = preferenceStore.getBoolean("audio_omniphony_enabled", false)
  val omniphonyOutputMode = preferenceStore.getEnum("audio_omniphony_output_mode", OmniphonyOutputMode.Binaural)
  val omniphonySpeakerLayout =
    preferenceStore.getEnum("audio_omniphony_speaker_layout", OmniphonySpeakerLayout.Surround51)
  val omniphonyHeadphoneRendering =
    preferenceStore.getEnum("audio_omniphony_headphone_rendering", OmniphonyHeadphoneRendering.BuiltIn)
  val omniphonyRoomSimulation = preferenceStore.getBoolean("audio_omniphony_room_simulation", false)
  val omniphonySpatializeChannels = preferenceStore.getBoolean("audio_omniphony_spatialize_channels", true)
  val omniphonyOverlay = preferenceStore.getBoolean("audio_omniphony_overlay", false)
  val drcEnabled = preferenceStore.getBoolean("audio_drc_enabled", false)
  val equalizerEnabled = preferenceStore.getBoolean("audio_equalizer_enabled", false)
  val equalizerMode = preferenceStore.getString("audio_equalizer_mode", "DYNAMIC")
  val equalizerBands =
    preferenceStore.getObject(
      key = "audio_equalizer_bands",
      defaultValue = List(7) { 0f },
      serializer = { gains -> gains.joinToString(",") },
      deserializer = { stored ->
        val values = stored.split(',')
        List(7) { index -> values.getOrNull(index)?.trim()?.toFloatOrNull()?.coerceIn(-12f, 12f) ?: 0f }
      },
    )
  val equalizerToneX = preferenceStore.getInt("audio_equalizer_tone_x", 0)
  val equalizerToneY = preferenceStore.getInt("audio_equalizer_tone_y", 0)
  val equalizerFocused = preferenceStore.getBoolean("audio_equalizer_focused", false)
  val equalizerVolumeBoost = preferenceStore.getInt("audio_equalizer_volume_boost", 0)
  val showAudioVisualizer = preferenceStore.getBoolean("show_audio_visualizer", true)
  val audioVisualizerStyle = preferenceStore.getEnum("audio_visualizer_style", AudioVisualizerStyle.Blob)
  val audioOrientation = preferenceStore.getEnum("audio_player_orientation", AudioPlayerOrientation.Auto)
  val audioAmbientMode = preferenceStore.getBoolean("audio_ambient_mode", true)
  val animatedAlbumArtwork = preferenceStore.getBoolean("audio_animated_album_artwork", false)
  val audioWavySeekbar = preferenceStore.getBoolean("audio_wavy_seekbar", true)
  val enabledMusicTabs = preferenceStore.getStringSet(
    "enabled_music_tabs",
    setOf("SONGS", "ALBUMS", "ARTISTS", "PLAYLISTS", "FOLDERS"),
  )
  val musicTabOrder = preferenceStore.getObject(
    key = "music_tab_order",
    defaultValue = listOf("SONGS", "ALBUMS", "ARTISTS", "PLAYLISTS", "FOLDERS"),
    serializer = { list -> list.joinToString(",") },
    deserializer = { str ->
      val parsed = str.split(",").map { it.trim() }.filter { it.isNotEmpty() }
      val missing = listOf("SONGS", "ALBUMS", "ARTISTS", "PLAYLISTS", "FOLDERS") - parsed.toSet()
      parsed + missing
    },
  )

  /** Fetch from external lyric providers automatically only when local lyrics are absent. */
  val lyricsAutoFetchOnline = preferenceStore.getBoolean("lyrics_auto_fetch_online", true)
  val lyricsAutoTranslate = preferenceStore.getBoolean("lyrics_auto_translate", false)
  val lyricsTargetLanguage = preferenceStore.getString("lyrics_target_language", "en")
  val lyricsTranslationDisplayMode = preferenceStore.getEnum("lyrics_translation_display_mode", LyricsTranslationDisplayMode.DualLine)
  val paxsenixApiKey = preferenceStore.getString("paxsenix_api_key", "")

  fun getVideoBackgroundPlayback(mediaId: String?): Boolean =
    resolveVideoBackgroundPlayback(backgroundPlaybackBehavior.get(), backgroundPlayback.get(), videoBackgroundPlaybackIds.get(), mediaId)

  fun setVideoBackgroundPlayback(mediaId: String?, enabled: Boolean) {
    if (backgroundPlaybackBehavior.get() == BackgroundPlaybackBehavior.Remember) {
      backgroundPlayback.set(enabled)
      return
    }
    if (mediaId.isNullOrBlank()) return
    synchronized(videoBackgroundPlaybackLock) {
      val enabledVideos = videoBackgroundPlaybackIds.get()
      videoBackgroundPlaybackIds.set(if (enabled) enabledVideos + mediaId else enabledVideos - mediaId)
    }
  }

  fun videoBackgroundPlaybackChanges(mediaIds: Flow<String?>): Flow<Boolean> =
    combine(
      backgroundPlaybackBehavior.changes(),
      backgroundPlayback.changes(),
      videoBackgroundPlaybackIds.changes(),
      mediaIds,
    ) { behavior, globalChoice, enabledVideos, mediaId ->
      resolveVideoBackgroundPlayback(behavior, globalChoice, enabledVideos, mediaId)
    }.distinctUntilChanged()

  private fun resolveVideoBackgroundPlayback(
    behavior: BackgroundPlaybackBehavior,
    globalChoice: Boolean,
    enabledVideos: Set<String>,
    mediaId: String?,
  ): Boolean =
    if (behavior == BackgroundPlaybackBehavior.Remember) globalChoice else mediaId != null && mediaId in enabledVideos

  init {
    if (preferenceStore.getString("background_playback_behavior").get() == "ResetAfterVideo") {
      backgroundPlaybackBehavior.set(BackgroundPlaybackBehavior.PerVideo)
    }
    // Consolidate the old audio-only screen-lock switch into the single global setting.
    val legacyScreenLockPlayback = preferenceStore.getBoolean("play_audio_after_screen_lock", false)
    if (legacyScreenLockPlayback.get()) backgroundPlayback.set(true)
    if (legacyScreenLockPlayback.isSet()) legacyScreenLockPlayback.delete()
  }
}

enum class BackgroundPlaybackBehavior(
  @StringRes val titleRes: Int,
  @StringRes val summaryRes: Int,
) {
  Remember(R.string.pref_background_playback_remember, R.string.pref_background_playback_remember_summary),
  PerVideo(R.string.pref_background_playback_per_video, R.string.pref_background_playback_per_video_summary),
}

enum class LyricsTranslationDisplayMode(
  @StringRes val title: Int,
) {
  DualLine(R.string.pref_lyrics_display_dual_line),
  Replace(R.string.pref_lyrics_display_replace),
}

enum class AudioPlayerOrientation(
  @StringRes val titleRes: Int,
) {
  Auto(R.string.pref_audio_channels_auto),
  Portrait(R.string.pref_player_orientation_portrait),
  Landscape(R.string.pref_player_orientation_landscape),
  Free(R.string.pref_player_orientation_free),
}

enum class AudioVisualizerStyle(
  @StringRes val title: Int,
) {
  Blob(R.string.pref_audio_visualizer_style_blob),
  Galaxy(R.string.pref_audio_visualizer_style_galaxy),
  Cuboid(R.string.pref_audio_visualizer_style_cuboid),
  Particle(R.string.pref_audio_visualizer_style_particle),
}

enum class OmniphonyOutputMode(
  @StringRes val title: Int,
) {
  Binaural(R.string.pref_omniphony_output_binaural),
  Speakers(R.string.pref_omniphony_output_speakers),
}

/** Omniphony speaker layouts in normalized room coordinates, LFE never spatialized. */
enum class OmniphonySpeakerLayout(
  val label: String,
  val speakers: List<OmniphonySpeaker>,
) {
  Surround51("5.1", OmniphonySpeaker.bed(sides = false)),
  Surround71("7.1", OmniphonySpeaker.bed(sides = true)),
  Surround512("5.1.2", OmniphonySpeaker.bed(sides = false) + OmniphonySpeaker.frontHeights),
  Surround712("7.1.2", OmniphonySpeaker.bed(sides = true) + OmniphonySpeaker.frontHeights),
  Surround714(
    "7.1.4",
    OmniphonySpeaker.bed(sides = true) + OmniphonySpeaker.frontHeights + OmniphonySpeaker.rearHeights,
  ),
}

data class OmniphonySpeaker(
  val name: String,
  val x: Float,
  val y: Float,
  val z: Float,
  val spatialize: Boolean = true,
) {
  companion object {
    fun bed(sides: Boolean): List<OmniphonySpeaker> =
      buildList {
        add(OmniphonySpeaker("FL", -1f, 1f, 0f))
        add(OmniphonySpeaker("FR", 1f, 1f, 0f))
        add(OmniphonySpeaker("C", 0f, 1f, 0f))
        add(OmniphonySpeaker("LFE", 1f, 1f, -1f, spatialize = false))
        if (sides) {
          add(OmniphonySpeaker("SL", -1f, 0f, 0f))
          add(OmniphonySpeaker("SR", 1f, 0f, 0f))
        }
        add(OmniphonySpeaker("BL", -1f, -1f, 0f))
        add(OmniphonySpeaker("BR", 1f, -1f, 0f))
      }

    val frontHeights = listOf(OmniphonySpeaker("TFL", -1f, 1f, 1f), OmniphonySpeaker("TFR", 1f, 1f, 1f))
    val rearHeights = listOf(OmniphonySpeaker("TBL", -1f, -1f, 1f), OmniphonySpeaker("TBR", 1f, -1f, 1f))
  }
}

enum class OmniphonyHeadphoneRendering(
  @StringRes val title: Int,
) {
  BuiltIn(R.string.pref_omniphony_headphones_builtin),
  PersonalHrtf(R.string.pref_omniphony_headphones_hrtf),
  MeasuredRoom(R.string.pref_omniphony_headphones_brir),
}


enum class AudioChannels(
  @StringRes val title: Int,
  val property: String,
  val value: String,
) {
  /** Let mpv prefer the source layout when the output device reports that it is supported. */
  Auto(R.string.pref_audio_channels_auto, "audio-channels", "auto"),
  /** Use the system-preferred layout and safely fall back to stereo. This is mpv's default. */
  AutoSafe(R.string.pref_audio_channels_auto_safe, "audio-channels", "auto-safe"),
  Mono(R.string.pref_audio_channels_mono, "audio-channels", "mono"),
  Stereo(R.string.pref_audio_channels_stereo, "audio-channels", "stereo"),
  ReverseStereo(R.string.pref_audio_channels_stereo_reversed, "af", "pan=[stereo|c0=c1|c1=c0]"),
}
