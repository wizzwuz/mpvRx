/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.omniphony.OmniphonyEngine
import app.gyrolet.mpvrx.domain.omniphony.OmniphonyEngine.ImportProblem
import app.gyrolet.mpvrx.domain.omniphony.OmniphonyEngine.SofaKind
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.OmniphonyHeadphoneRendering
import app.gyrolet.mpvrx.preferences.OmniphonyOutputMode
import app.gyrolet.mpvrx.preferences.OmniphonySpeakerLayout
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ConfirmDialog
import app.gyrolet.mpvrx.ui.components.AppToast
import app.gyrolet.mpvrx.ui.preferences.components.PreferenceAction
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import app.gyrolet.mpvrx.ui.preferences.components.AppListPreference as ListPreference
import app.gyrolet.mpvrx.ui.preferences.components.AppPreference as Preference

private const val OMNIPHONY_URL = "https://github.com/mgth/Omniphony"
private const val HARLETTY_BRIDGE_URL = "https://github.com/harletty/harletty-bridge"

/** Settings and user-imported native libraries of the Omniphony spatial audio decoder. */
@Serializable
object OmniphonyPreferencesScreen : Screen {
  private class Removal(
    val name: String,
    val remove: OmniphonyEngine.() -> Unit,
  )

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val preferences = koinInject<AudioPreferences>()
    val engine = koinInject<OmniphonyEngine>()
    val installation by engine.installation.collectAsState()
    var removal by remember { mutableStateOf<Removal?>(null) }
    var confirmRemoveAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { engine.refresh() } }

    val libraryPicker =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
          val result = withContext(Dispatchers.IO) { engine.importLibraries(uris) }
          val message = importMessage(context, result, engine.deviceAbi)
          if (result.imported) AppToast.success(context, message) else AppToast.error(context, message)
        }
      }

    fun importSofa(
      kind: SofaKind,
      uri: Uri?,
    ) {
      if (uri == null) return
      scope.launch {
        if (withContext(Dispatchers.IO) { engine.importSofa(kind, uri) }) {
          AppToast.success(context, context.getString(R.string.pref_omniphony_sofa_imported))
        } else {
          AppToast.error(context, context.getString(R.string.pref_omniphony_sofa_invalid))
        }
      }
    }
    val hrtfPicker =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importSofa(SofaKind.Hrtf, it) }
    val brirPicker =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importSofa(SofaKind.Brir, it) }

    removal?.let { pending ->
      ConfirmDialog(
        title = stringResource(R.string.pref_omniphony_remove_title, pending.name),
        subtitle = stringResource(R.string.pref_omniphony_remove_message),
        confirmText = stringResource(R.string.pref_omniphony_remove),
        onConfirm = {
          removal = null
          scope.launch(Dispatchers.IO) { pending.remove(engine) }
        },
        onCancel = { removal = null },
      )
    }
    if (confirmRemoveAll) {
      ConfirmDialog(
        title = stringResource(R.string.pref_omniphony_remove_all_title),
        subtitle = stringResource(R.string.pref_omniphony_remove_all_message),
        confirmText = stringResource(R.string.pref_omniphony_remove),
        onConfirm = {
          confirmRemoveAll = false
          scope.launch(Dispatchers.IO) { engine.removeAll() }
        },
        onCancel = { confirmRemoveAll = false },
      )
    }

    fun sizeOf(file: OmniphonyEngine.InstalledFile): String = Formatter.formatShortFileSize(context, file.sizeBytes)

    Scaffold(
      contentWindowInsets = settingsContentWindowInsets(),
      topBar = {
        SettingsTopAppBar(
          title = stringResource(R.string.pref_omniphony_title),
          onBack = { backstack.popSafely() },
        )
      },
    ) { padding ->
      ProvideExpressivePreferenceLocals {
        val (listState, highlight) =
          rememberSettingsSearchList(OmniphonyPreferencesScreen, MaterialTheme.colorScheme.primary)
        LazyColumn(
          state = listState,
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .then(highlight),
        ) {
          item {
            PreferenceSectionHeader(
              position = PreferenceSectionPosition.First,
              title = stringResource(R.string.pref_omniphony_section_engine),
            )
          }
          item {
            PreferenceCard {
              val enabled by preferences.omniphonyEnabled.collectAsState()
              val hasEngine = installation.engine != null
              SwitchPreference(
                value = enabled && hasEngine,
                onValueChange = preferences.omniphonyEnabled::set,
                enabled = hasEngine,
                title = { Text(stringResource(R.string.pref_omniphony_enable_title)) },
                summary = {
                  Text(
                    stringResource(
                      if (hasEngine) R.string.pref_omniphony_enable_summary else R.string.pref_omniphony_enable_needs_engine,
                    ),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val spatializeChannels by preferences.omniphonySpatializeChannels.collectAsState()
              SwitchPreference(
                value = spatializeChannels,
                onValueChange = preferences.omniphonySpatializeChannels::set,
                title = { Text(stringResource(R.string.pref_omniphony_spatialize_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_omniphony_spatialize_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val overlay by preferences.omniphonyOverlay.collectAsState()
              SwitchPreference(
                value = overlay,
                onValueChange = preferences.omniphonyOverlay::set,
                title = { Text(stringResource(R.string.pref_omniphony_overlay_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_omniphony_overlay_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          item {
            PreferenceSectionHeader(
              position = PreferenceSectionPosition.Middle,
              title = stringResource(R.string.pref_omniphony_section_output),
            )
          }
          item {
            PreferenceCard {
              val outputMode by preferences.omniphonyOutputMode.collectAsState()
              ExpressivePreferenceItem {
                ListPreference(
                  value = outputMode,
                  onValueChange = preferences.omniphonyOutputMode::set,
                  values = OmniphonyOutputMode.entries,
                  valueToText = { AnnotatedString(stringResource(it.title)) },
                  title = { Text(stringResource(R.string.pref_omniphony_output_title)) },
                  summary = {
                    Text(stringResource(outputMode.title), color = MaterialTheme.colorScheme.outline)
                  },
                )
              }

              PreferenceDivider()
              if (outputMode == OmniphonyOutputMode.Speakers) {
                val speakerLayout by preferences.omniphonySpeakerLayout.collectAsState()
                ExpressivePreferenceItem {
                  ListPreference(
                    value = speakerLayout,
                    onValueChange = preferences.omniphonySpeakerLayout::set,
                    values = OmniphonySpeakerLayout.entries,
                    valueToText = { AnnotatedString(it.label) },
                    title = { Text(stringResource(R.string.pref_omniphony_layout_title)) },
                    summary = {
                      Text(
                        stringResource(R.string.pref_omniphony_layout_summary, speakerLayout.label),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                  )
                }
              } else {
                val rendering by preferences.omniphonyHeadphoneRendering.collectAsState()
                ExpressivePreferenceItem {
                  ListPreference(
                    value = rendering,
                    onValueChange = preferences.omniphonyHeadphoneRendering::set,
                    values = OmniphonyHeadphoneRendering.entries,
                    valueToText = { AnnotatedString(stringResource(it.title)) },
                    title = { Text(stringResource(R.string.pref_omniphony_headphones_title)) },
                    summary = {
                      Text(stringResource(rendering.title), color = MaterialTheme.colorScheme.outline)
                    },
                  )
                }

                val sofaKind =
                  when (rendering) {
                    OmniphonyHeadphoneRendering.BuiltIn -> null
                    OmniphonyHeadphoneRendering.PersonalHrtf -> SofaKind.Hrtf
                    OmniphonyHeadphoneRendering.MeasuredRoom -> SofaKind.Brir
                  }
                if (sofaKind != null) {
                  val sofa = if (sofaKind == SofaKind.Hrtf) installation.hrtf else installation.brir
                  PreferenceDivider()
                  ExpressivePreferenceItem {
                    Preference(
                      title = {
                        Text(
                          stringResource(
                            if (sofaKind == SofaKind.Hrtf) {
                              R.string.pref_omniphony_sofa_hrtf_title
                            } else {
                              R.string.pref_omniphony_sofa_brir_title
                            },
                          ),
                        )
                      },
                      summary = {
                        Text(
                          if (sofa == null) {
                            stringResource(R.string.pref_omniphony_sofa_missing)
                          } else {
                            stringResource(R.string.pref_omniphony_remove_hint, "${sofa.name} · ${sizeOf(sofa)}")
                          },
                          color = MaterialTheme.colorScheme.outline,
                        )
                      },
                      onClick = {
                        when {
                          sofa != null -> removal = Removal(sofa.name) { removeSofa(sofaKind) }
                          sofaKind == SofaKind.Hrtf -> hrtfPicker.launch(arrayOf("*/*"))
                          else -> brirPicker.launch(arrayOf("*/*"))
                        }
                      },
                      action = PreferenceAction.None,
                    )
                  }
                }

                PreferenceDivider()
                val roomSimulation by preferences.omniphonyRoomSimulation.collectAsState()
                SwitchPreference(
                  value = roomSimulation,
                  onValueChange = preferences.omniphonyRoomSimulation::set,
                  title = { Text(stringResource(R.string.pref_omniphony_room_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_omniphony_room_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )
              }
            }
          }

          item {
            PreferenceSectionHeader(
              position = PreferenceSectionPosition.Middle,
              title = stringResource(R.string.pref_omniphony_section_plugins),
            )
          }
          item {
            PreferenceCard {
              ExpressivePreferenceItem {
                Preference(
                  title = { Text(stringResource(R.string.pref_omniphony_import_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_omniphony_import_summary, engine.deviceAbi),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  onClick = { libraryPicker.launch(arrayOf("*/*")) },
                  action = PreferenceAction.None,
                )
              }

              PreferenceDivider()
              val engineFile = installation.engine
              ExpressivePreferenceItem {
                Preference(
                  title = { Text(stringResource(R.string.pref_omniphony_engine_title)) },
                  summary = {
                    Text(
                      if (engineFile == null) {
                        stringResource(R.string.pref_omniphony_not_installed)
                      } else {
                        stringResource(R.string.pref_omniphony_remove_hint, sizeOf(engineFile))
                      },
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  onClick = engineFile?.let { file -> { removal = Removal(file.name) { removeEngine() } } },
                  action = PreferenceAction.None,
                )
              }

              if (installation.bridges.isEmpty()) {
                PreferenceDivider()
                ExpressivePreferenceItem {
                  Preference(
                    title = { Text(stringResource(R.string.pref_omniphony_no_bridges)) },
                    action = PreferenceAction.None,
                  )
                }
              }
              installation.bridges.forEach { bridge ->
                PreferenceDivider()
                ExpressivePreferenceItem {
                  Preference(
                    title = { Text(bridge.name) },
                    summary = {
                      Text(
                        stringResource(R.string.pref_omniphony_remove_hint, sizeOf(bridge)),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                    onClick = { removal = Removal(bridge.name) { removeBridge(bridge.name) } },
                    action = PreferenceAction.None,
                  )
                }
              }

              if (installation != OmniphonyEngine.Installation()) {
                PreferenceDivider()
                ExpressivePreferenceItem {
                  Preference(
                    title = { Text(stringResource(R.string.pref_omniphony_remove_all_title)) },
                    summary = {
                      Text(
                        stringResource(R.string.pref_omniphony_remove_all_summary),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                    onClick = { confirmRemoveAll = true },
                    action = PreferenceAction.None,
                  )
                }
              }
            }
          }

          item {
            PreferenceSectionHeader(
              position = PreferenceSectionPosition.Last,
              title = stringResource(R.string.pref_omniphony_section_about),
            )
          }
          item {
            PreferenceCard {
              ExpressivePreferenceItem {
                Preference(
                  title = { Text(stringResource(R.string.pref_omniphony_link_engine)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_omniphony_about_note),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  onClick = { runCatching { uriHandler.openUri(OMNIPHONY_URL) } },
                )
              }

              PreferenceDivider()
              ExpressivePreferenceItem {
                Preference(
                  title = { Text(stringResource(R.string.pref_omniphony_link_bridge)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_omniphony_link_bridge_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  onClick = { runCatching { uriHandler.openUri(HARLETTY_BRIDGE_URL) } },
                )
              }
            }
          }
        }
      }
    }
  }

  private fun importMessage(
    context: Context,
    result: OmniphonyEngine.ImportResult,
    deviceAbi: String,
  ): String =
    buildList {
      if (result.engineImported) add(context.getString(R.string.pref_omniphony_imported_engine))
      if (result.bridgesImported > 0) {
        add(context.getString(R.string.pref_omniphony_imported_bridges, result.bridgesImported))
      }
      result.problems.forEach { problem ->
        add(
          when (problem) {
            ImportProblem.WrongArchitecture -> context.getString(R.string.pref_omniphony_problem_architecture, deviceAbi)
            ImportProblem.NotALibrary -> context.getString(R.string.pref_omniphony_problem_not_library)
            ImportProblem.UnknownLibrary -> context.getString(R.string.pref_omniphony_problem_unknown)
            ImportProblem.TooLarge -> context.getString(R.string.pref_omniphony_problem_too_large)
            ImportProblem.Unreadable -> context.getString(R.string.pref_omniphony_problem_unreadable)
          },
        )
      }
    }.joinToString("\n")
}
