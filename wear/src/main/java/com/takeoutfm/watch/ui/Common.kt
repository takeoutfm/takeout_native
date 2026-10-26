package com.takeoutfm.watch.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import coil.compose.AsyncImage
import com.takeoutfm.watch.R
import kotlin.coroutines.cancellation.CancellationException

/** A page's data: loading, loaded, or failed. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Loaded<T>(val value: T) : Load<T>
    data object Failed : Load<Nothing>
}

/** Loads [block] once per [key]. */
@Composable
fun <T> load(key: Any?, block: suspend () -> T): Load<T> =
    produceState<Load<T>>(Load.Loading, key) {
        value = try {
            Load.Loaded(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed
        }
    }.value

/** A scrolling watch page: time at the top, a header, then [content]; rotary-scrollable. */
@Composable
fun ListPage(title: String, content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        ScalingLazyColumn(Modifier.fillMaxSize(), state = state) {
            item { ListHeader { Text(title, textAlign = TextAlign.Center, maxLines = 2) } }
            content()
        }
    }
}

/** Loading spinner, "couldn't load" or "nothing here" for a list page. */
fun ScalingLazyListScope.status(state: Load<List<*>>) {
    when {
        state is Load.Loading -> item { CircularProgressIndicator() }
        state is Load.Failed -> item { Text(stringResource(R.string.cant_load), textAlign = TextAlign.Center) }
        (state as Load.Loaded).value.isEmpty() -> item { Text(stringResource(R.string.nothing_here), textAlign = TextAlign.Center) }
    }
}

/** A list entry: artwork or icon, a title and optional second line. */
@Composable
fun Entry(
    label: String,
    onClick: () -> Unit,
    secondary: String? = null,
    imageUri: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = secondary?.takeIf { it.isNotEmpty() }?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colors.onSurfaceVariant) }
        },
        icon = when {
            !imageUri.isNullOrEmpty() -> {
                {
                    AsyncImage(
                        imageUri, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(ChipDefaults.LargeIconSize).clip(CircleShape),
                    )
                }
            }
            icon != null -> { { Icon(icon, contentDescription = null, modifier = Modifier.size(ChipDefaults.IconSize)) } }
            trailing != null -> null
            else -> null
        },
    )
}
