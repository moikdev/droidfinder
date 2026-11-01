package dev.droidfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Noninteractive feedback must not intercept file gestures behind its text/background. */
@Composable
internal fun FinderFeedback(data: SnackbarData) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier.padding(horizontal = 12.dp)
            .widthIn(max = 560.dp)
            .shadow(5.dp, shape, clip = false)
            .background(MaterialTheme.colorScheme.inverseSurface, shape)
            .testTag("finder-feedback")
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                paneTitle = "Notification"
            }
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            data.visuals.message,
            Modifier.weight(1f, fill = false).padding(end = 8.dp),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        data.visuals.actionLabel?.let { label ->
            TextButton(onClick = data::performAction) {
                Text(label, color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
        if (data.visuals.withDismissAction)
            IconButton(onClick = data::dismiss) {
                Icon(
                    Icons.Outlined.Close,
                    "Dismiss notification",
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
    }
}
