/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSectionHeader
import app.gyrolet.mpvrx.ui.components.IconSwitch
import app.gyrolet.mpvrx.ui.player.AutoCropState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import org.koin.compose.koinInject
import kotlin.math.round

data class AspectRatio(
  val label: String,
  val ratio: Double,
  val isCustom: Boolean = false,
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AspectRatioSheet(
  currentRatio: Double?,
  customRatios: List<AspectRatio>,
  autoCropEnabled: Boolean,
  autoCropState: AutoCropState,
  autoCropControlEnabled: Boolean,
  onAutoCropChanged: (Boolean) -> Unit,
  videoZoom: Float,
  videoPanX: Float,
  videoPanY: Float,
  onZoomChange: (Float) -> Unit,
  onPanXChange: (Float) -> Unit,
  onPanYChange: (Float) -> Unit,
  advancedZoomEnabled: Boolean,
  videoScaleX: Float,
  videoScaleY: Float,
  onAdvancedZoomToggle: (Boolean) -> Unit,
  onScaleXChange: (Float) -> Unit,
  onScaleYChange: (Float) -> Unit,
  onResetAdvancedZoom: () -> Unit,
  onSelectRatio: (Double) -> Unit,
  onAddCustomRatio: (String, Double) -> Unit,
  onDeleteCustomRatio: (AspectRatio) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val presetRatios =
    listOf(
      AspectRatio("Default", -1.0),
      AspectRatio("4:3", 4.0 / 3.0),
      AspectRatio("16:9", 16.0 / 9.0),
      AspectRatio("16:10", 16.0 / 10.0),
      AspectRatio("21:9", 21.0 / 9.0),
      AspectRatio("32:9", 32.0 / 9.0),
      AspectRatio("1:1", 1.0),
      AspectRatio("2.35:1", 2.35),
      AspectRatio("2.39:1", 2.39),
    )

  PlayerSheet(onDismissRequest, title = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_aspect_ratio)) {
    Column(
      modifier =
        modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(bottom = 8.dp),
    ) {
      val autoCropSummary =
        when {
          !autoCropControlEnabled -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_managed
          !autoCropEnabled -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_summary
          autoCropState == AutoCropState.ANALYZING -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_analyzing
          autoCropState == AutoCropState.APPLIED -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_applied
          autoCropState == AutoCropState.NO_BARS -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_none
          autoCropState == AutoCropState.UNSUPPORTED -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_unsupported
          autoCropState == AutoCropState.ERROR -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_failed
          else -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_summary
        }
      ListItem(
        content = {
          Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars))
        },
        supportingContent = { Text(androidx.compose.ui.res.stringResource(autoCropSummary)) },
        trailingContent = {
          IconSwitch(
            checked = autoCropEnabled,
            onCheckedChange = null,
            enabled = autoCropControlEnabled,
          )
        },
        modifier =
          Modifier.clickable(enabled = autoCropControlEnabled) {
            onAutoCropChanged(!autoCropEnabled)
          },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
      )

      // Preset ratios
      PlayerSheetSectionHeader(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_presets))

      androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        presetRatios.forEach { ratio ->
          InputChip(
            selected = currentRatio?.let { abs(it - ratio.ratio) < 0.01 } ?: (ratio.ratio == -1.0),
            onClick = { onSelectRatio(ratio.ratio) },
            label = { Text(ratio.label) },
            leadingIcon = null,
          )
        }
      }

      // Custom ratios
      if (customRatios.isNotEmpty()) {
        PlayerSheetSectionHeader(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.pref_gesture_double_tap_custom))

        androidx.compose.foundation.layout.FlowRow(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          customRatios.forEach { ratio ->
            InputChip(
              selected = currentRatio?.let { abs(it - ratio.ratio) < 0.01 } ?: false,
              onClick = { onSelectRatio(ratio.ratio) },
              label = { Text(ratio.label) },
              leadingIcon = null,
              trailingIcon = {
                Icon(
                  Icons.RoundedFilled.Close,
                  null,
                  modifier = Modifier.clickable { onDeleteCustomRatio(ratio) },
                )
              },
            )
          }
        }
      }

      // Add custom ratio
      AddCustomRatioRow(
        onAdd = onAddCustomRatio,
        modifier = Modifier.padding(top = MaterialTheme.spacing.medium),
      )

      HorizontalDivider(
        modifier = Modifier.padding(vertical = MaterialTheme.spacing.medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
      )

      PlayerSheetSectionHeader(
        androidx.compose.ui.res.stringResource(
          app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_manual_zoom_pan_title,
        ),
      )

      val playerPreferences = koinInject<PlayerPreferences>()
      val rememberVideoZoom by playerPreferences.rememberVideoZoom.collectAsState()

      ListItem(
        content = {
          Text(
            androidx.compose.ui.res.stringResource(
              app.gyrolet.mpvrx.R.string.pref_player_remember_video_zoom_title,
            ),
          )
        },
        supportingContent = {
          Text(
            androidx.compose.ui.res.stringResource(
              app.gyrolet.mpvrx.R.string.pref_player_remember_video_zoom_summary,
            ),
          )
        },
        trailingContent = {
          IconSwitch(
            checked = rememberVideoZoom,
            onCheckedChange = null,
          )
        },
        modifier =
          Modifier.clickable {
            playerPreferences.rememberVideoZoom.set(!rememberVideoZoom)
          },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
      )

      ListItem(
        content = {
          Text(
            androidx.compose.ui.res.stringResource(
              app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_advanced_zoom_toggle,
            ),
          )
        },
        supportingContent = {
          Text(
            androidx.compose.ui.res.stringResource(
              app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_advanced_zoom_description,
            ),
          )
        },
        trailingContent = {
          IconSwitch(
            checked = advancedZoomEnabled,
            onCheckedChange = null,
          )
        },
        modifier =
          Modifier.clickable {
            onAdvancedZoomToggle(!advancedZoomEnabled)
          },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
      )

      if (advancedZoomEnabled) {
        AdvancedZoomAxisControl(
          label =
            androidx.compose.ui.res.stringResource(
              app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_horizontal_zoom_value,
              videoScaleX,
            ),
          value = videoScaleX,
          onValueChange = onScaleXChange,
          decreaseDescription =
            androidx.compose.ui.res.stringResource(
              app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_decrease_horizontal_zoom,
            ),
          increaseDescription =
            androidx.compose.ui.res.stringResource(
              app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_increase_horizontal_zoom,
            ),
          resetDescription =
            androidx.compose.ui.res.stringResource(
              app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_reset_horizontal_zoom,
            ),
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

        AdvancedZoomAxisControl(
          label =
            androidx.compose.ui.res.stringResource(
              app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_vertical_zoom_value,
              videoScaleY,
            ),
          value = videoScaleY,
          onValueChange = onScaleYChange,
          decreaseDescription =
            androidx.compose.ui.res.stringResource(
              app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_decrease_vertical_zoom,
            ),
          increaseDescription =
            androidx.compose.ui.res.stringResource(
              app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_increase_vertical_zoom,
            ),
          resetDescription =
            androidx.compose.ui.res.stringResource(
              app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_reset_vertical_zoom,
            ),
        )

        if (videoScaleX != 1f || videoScaleY != 1f) {
          Button(
            onClick = onResetAdvancedZoom,
            modifier =
              Modifier
                .padding(horizontal = 20.dp)
                .padding(top = MaterialTheme.spacing.small),
          ) {
            Text(
              text =
                androidx.compose.ui.res.stringResource(
                  app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_reset_advanced_zoom,
                ),
              style = MaterialTheme.typography.labelMedium,
            )
          }
        }
      }

      // Zoom Slider (when advanced zoom is disabled)
      if (!advancedZoomEnabled) {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 20.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
          ) {
            val zoomFactor = java.lang.Math.pow(2.0, videoZoom.toDouble())
            Text(
              text =
                androidx.compose.ui.res.stringResource(
                  app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_zoom_value,
                  zoomFactor,
                ),
              style = MaterialTheme.typography.bodyMedium,
            )

            if (videoZoom != 0f) {
              IconButton(
                onClick = { onZoomChange(0f) },
                modifier = Modifier.size(24.dp),
              ) {
                Icon(
                  Icons.RoundedFilled.Refresh,
                  contentDescription =
                    androidx.compose.ui.res.stringResource(
                      app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_reset_zoom,
                    ),
                  modifier = Modifier.size(16.dp),
                )
              }
            }
          }

          Slider(
            value = videoZoom,
            onValueChange = onZoomChange,
            valueRange = -1f..3f,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }

      Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

      // Horizontal Pan Slider
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text =
              androidx.compose.ui.res.stringResource(
                app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_horizontal_offset_value,
                videoPanX,
              ),
            style = MaterialTheme.typography.bodyMedium,
          )

          if (videoPanX != 0f) {
            IconButton(
              onClick = { onPanXChange(0f) },
              modifier = Modifier.size(24.dp),
            ) {
              Icon(
                Icons.RoundedFilled.Refresh,
                contentDescription =
                  androidx.compose.ui.res.stringResource(
                    app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_reset_pan,
                  ),
                modifier = Modifier.size(16.dp),
              )
            }
          }
        }

        Slider(
          value = videoPanX,
          onValueChange = onPanXChange,
          valueRange = -1f..1f,
          modifier = Modifier.fillMaxWidth(),
        )
      }

      Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

      // Vertical Pan Slider
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text =
              androidx.compose.ui.res.stringResource(
                app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_vertical_offset_value,
                videoPanY,
              ),
            style = MaterialTheme.typography.bodyMedium,
          )

          if (videoPanY != 0f) {
            IconButton(
              onClick = { onPanYChange(0f) },
              modifier = Modifier.size(24.dp),
            ) {
              Icon(
                Icons.RoundedFilled.Refresh,
                contentDescription =
                  androidx.compose.ui.res.stringResource(
                    app.gyrolet.mpvrx.R.string.player_sheets_aspect_ratio_reset_pan,
                  ),
                modifier = Modifier.size(16.dp),
              )
            }
          }
        }

        Slider(
          value = videoPanY,
          onValueChange = onPanYChange,
          valueRange = -1f..1f,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
private fun AddCustomRatioRow(
  onAdd: (String, Double) -> Unit,
  modifier: Modifier = Modifier,
) {
  var widthText by remember { mutableStateOf("") }
  var heightText by remember { mutableStateOf("") }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  val keyboardController = LocalSoftwareKeyboardController.current

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = MaterialTheme.spacing.medium),
  ) {
    Text(
      text =
        androidx.compose.ui.res
          .stringResource(app.gyrolet.mpvrx.R.string.ui_add_custom_ratio_e_g_16_9),
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier.padding(bottom = MaterialTheme.spacing.small),
    )

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
      modifier = Modifier.fillMaxWidth(),
    ) {
      // Width input
      OutlinedTextField(
        value = widthText,
        onValueChange = {
          widthText = it.filter { char -> char.isDigit() || char == '.' }
          errorMessage = null
        },
        label = {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_width),
          )
        },
        isError = errorMessage != null,
        keyboardOptions =
          KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next,
          ),
        modifier = Modifier.weight(1f),
        singleLine = true,
      )

      // Colon separator
      Text(
        text = ":",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.extraSmall),
      )

      // Height input
      OutlinedTextField(
        value = heightText,
        onValueChange = {
          heightText = it.filter { char -> char.isDigit() || char == '.' }
          errorMessage = null
        },
        label = {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_height),
          )
        },
        isError = errorMessage != null,
        keyboardOptions =
          KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
          ),
        keyboardActions =
          KeyboardActions(
            onDone = {
              val result = calculateRatio(widthText, heightText)
              if (result != null) {
                onAdd("$widthText:$heightText", result)
                widthText = ""
                heightText = ""
                keyboardController?.hide()
              } else {
                errorMessage = "Invalid"
              }
            },
          ),
        modifier = Modifier.weight(1f),
        singleLine = true,
      )

      // Add button
      FilledTonalIconButton(
        onClick = {
          val result = calculateRatio(widthText, heightText)
          if (result != null) {
            onAdd("$widthText:$heightText", result)
            widthText = ""
            heightText = ""
            keyboardController?.hide()
          } else {
            errorMessage = "Invalid"
          }
        },
      ) {
        Icon(
          Icons.RoundedFilled.Add,
          contentDescription =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_add),
        )
      }
    }

    errorMessage?.let { msg ->
      Text(
        text = msg,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = MaterialTheme.spacing.small, top = 4.dp),
      )
    }
  }
}

private fun calculateRatio(
  widthStr: String,
  heightStr: String,
): Double? {
  if (widthStr.isEmpty() || heightStr.isEmpty()) return null

  return try {
    val width = widthStr.toDouble()
    val height = heightStr.toDouble()
    if (width > 0 && height > 0) width / height else null
  } catch (_: NumberFormatException) {
    null
  }
}

private fun abs(value: Double): Double = if (value < 0) -value else value

private const val ADVANCED_ZOOM_MIN = 0.5f
private const val ADVANCED_ZOOM_MAX = 2.0f
private const val ADVANCED_ZOOM_STEP = 0.05f

@Composable
private fun AdvancedZoomAxisControl(
  label: String,
  value: Float,
  onValueChange: (Float) -> Unit,
  decreaseDescription: String,
  increaseDescription: String,
  resetDescription: String,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(text = label, style = MaterialTheme.typography.bodyMedium)

      if (value != 1f) {
        IconButton(
          onClick = { onValueChange(1f) },
          modifier = Modifier.size(24.dp),
        ) {
          Icon(
            Icons.RoundedFilled.Refresh,
            contentDescription = resetDescription,
            modifier = Modifier.size(16.dp),
          )
        }
      }
    }

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
      modifier = Modifier.fillMaxWidth(),
    ) {
      FilledTonalIconButton(
        onClick = {
          val rounded = (round((value - ADVANCED_ZOOM_STEP) * 100f) / 100f).coerceIn(ADVANCED_ZOOM_MIN, ADVANCED_ZOOM_MAX)
          onValueChange(rounded)
        },
        modifier = Modifier.size(36.dp),
      ) {
        Icon(
          Icons.RoundedFilled.Remove,
          contentDescription = decreaseDescription,
          modifier = Modifier.size(18.dp),
        )
      }

      Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = ADVANCED_ZOOM_MIN..ADVANCED_ZOOM_MAX,
        modifier = Modifier.weight(1f),
      )

      FilledTonalIconButton(
        onClick = {
          val rounded = (round((value + ADVANCED_ZOOM_STEP) * 100f) / 100f).coerceIn(ADVANCED_ZOOM_MIN, ADVANCED_ZOOM_MAX)
          onValueChange(rounded)
        },
        modifier = Modifier.size(36.dp),
      ) {
        Icon(
          Icons.RoundedFilled.Add,
          contentDescription = increaseDescription,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

