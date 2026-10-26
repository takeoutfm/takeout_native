package com.takeoutfm.mobile.ui.common

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle

/**
 * An app bar title with an optional smaller [subtitle] on the same line ("Music · Recently
 * added"): one line fits the bar's fixed height at any font size, where two lines get clipped.
 */
@Composable
fun BarTitle(title: String, subtitle: String? = null) {
    val small = MaterialTheme.typography.titleSmall
    Text(
        buildAnnotatedString {
            append(title)
            if (!subtitle.isNullOrEmpty()) {
                withStyle(SpanStyle(fontSize = small.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append("  ·  ")
                    append(subtitle)
                }
            }
        },
        style = LocalTextStyle.current,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
