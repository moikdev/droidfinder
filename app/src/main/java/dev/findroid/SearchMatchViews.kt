package dev.findroid

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun searchAnnotated(text: String, ranges: List<MatchRange>): AnnotatedString {
    val style = SpanStyle(background = SelectionSurface, color = SelectedInk, fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { range ->
            val start = range.start.coerceIn(0, text.length)
            val end = range.end.coerceIn(start, text.length)
            if (end > start) addStyle(style, start, end)
        }
    }
}

/** Compact layouts show the reason; the details pane always offers the complete bounded snippet. */
@Composable
internal fun SearchMatchDetails(
    entry: Entry,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    snippet: Boolean = true,
    centered: Boolean = false,
) {
    val evidence = entry.searchEvidence ?: return
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(modifier.testTag("search-evidence-${entry.path}")) {
        Text(
            "Matched: " + evidence.reasons.joinToString(" · "),
            color = Muted,
            fontSize = if (compact) 10.sp else 12.sp,
            lineHeight = if (compact) 14.sp else 18.sp,
            maxLines = if (compact) 1 else 8,
            textAlign = align,
            overflow = TextOverflow.Ellipsis,
        )
        if (snippet) evidence.snippet?.let { value ->
            val prefix = if (value.leading) "…" else ""
            Text(
                searchAnnotated(prefix + value.text + if (value.trailing) "…" else "",
                    value.ranges.map { MatchRange(it.start + prefix.length, it.end + prefix.length) }),
                Modifier.padding(top = 2.dp).testTag("search-snippet-${entry.path}"),
                color = Ink,
                fontSize = if (compact) 11.sp else 12.sp,
                lineHeight = if (compact) 15.sp else 18.sp,
                maxLines = if (compact) 1 else 6,
                textAlign = align,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
