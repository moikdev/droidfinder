package dev.droidfinder

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Same visual weight and touch area in every segment; selected inset surface is distinct. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewModeSelector(model: FinderModel) {
    Row(
        Modifier.height(44.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(ToolbarSurface)
            .border(.5.dp, Border, RoundedCornerShape(9.dp))
            .padding(3.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewMode.entries.forEachIndexed { index, mode ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val selected = model.mode == mode
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("${mode.title} view · Ctrl ${index + 1}") } },
                state = rememberTooltipState(),
                focusable = false,
            ) {
                Box(
                    Modifier.size(38.dp)
                        .shadow(if (selected) 1.dp else 0.dp, RoundedCornerShape(6.dp))
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (selected) CanvasBackground
                            else if (hovered) AppBackground else Color.Transparent,
                        )
                        .hoverable(interaction)
                        .selectable(
                            selected,
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                            role = Role.RadioButton,
                            onClick = { model.changeMode(mode) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when (mode) {
                            ViewMode.ICONS -> Icons.Outlined.GridView
                            ViewMode.LIST -> Icons.AutoMirrored.Outlined.FormatListBulleted
                            ViewMode.COLUMNS -> Icons.Outlined.ViewColumn
                            ViewMode.GALLERY -> Icons.Outlined.CropOriginal
                        },
                        "${mode.title} view",
                        Modifier.size(20.dp),
                        tint = if (selected) SelectedInk else Muted,
                    )
                }
            }
        }
    }
}
