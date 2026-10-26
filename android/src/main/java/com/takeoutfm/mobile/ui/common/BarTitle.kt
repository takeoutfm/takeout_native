package com.takeoutfm.mobile.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle

/**
 * An app bar title with an optional smaller [subtitle] underneath, as the Flutter app. With
 * large fonts the two lines don't fit the bar's fixed height, so the subtitle goes on the same
 * line instead ("Music · Recently added").
 */
@Composable
fun BarTitle(title: String, subtitle: String? = null) {
    val subtitleStyle = MaterialTheme.typography.labelMedium
    val subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant
    if (subtitle.isNullOrEmpty()) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else if (LocalDensity.current.fontScale <= LargeFontScale) {
        Column {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = subtitleStyle, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Text(
            buildAnnotatedString {
                append(title)
                withStyle(SpanStyle(fontSize = subtitleStyle.fontSize, color = subtitleColor)) {
                    append("  ·  ")
                    append(subtitle)
                }
            },
            style = LocalTextStyle.current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Font scales above this put the subtitle on the title's line. */
private const val LargeFontScale = 1.3f
