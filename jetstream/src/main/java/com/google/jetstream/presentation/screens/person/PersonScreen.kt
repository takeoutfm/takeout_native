package com.google.jetstream.presentation.screens.person

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.jetstream.R
import com.google.jetstream.data.entities.CreditKind
import com.google.jetstream.data.entities.Movie
import com.google.jetstream.data.entities.PersonDetails
import com.google.jetstream.presentation.common.Error
import com.google.jetstream.presentation.common.Loading
import com.google.jetstream.presentation.common.MoviesRow
import com.google.jetstream.presentation.screens.dashboard.rememberChildPadding
import com.google.jetstream.presentation.theme.JetStreamCardShape

object PersonScreen {
    const val PersonIdBundleKey = "personId"
}

@Composable
fun PersonScreen(
    onBackPressed: () -> Unit,
    onMovieSelected: (Movie) -> Unit,
    personScreenViewModel: PersonScreenViewModel = hiltViewModel()
) {
    val uiState by personScreenViewModel.uiState.collectAsStateWithLifecycle()

    when (val s = uiState) {
        is PersonScreenUiState.Loading -> Loading(modifier = Modifier.fillMaxSize())
        is PersonScreenUiState.Error -> Error(modifier = Modifier.fillMaxSize())
        is PersonScreenUiState.Done -> PersonDetailsContent(
            person = s.person,
            onBackPressed = onBackPressed,
            onMovieSelected = onMovieSelected,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun PersonDetailsContent(
    person: PersonDetails,
    onBackPressed: () -> Unit,
    onMovieSelected: (Movie) -> Unit,
    modifier: Modifier = Modifier,
) {
    val childPadding = rememberChildPadding()
    // Start in the header (on the bio) so it stays on screen; fall back to the first credit row.
    val initialFocus = remember { FocusRequester() }
    var showFullBio by remember { mutableStateOf(false) }

    BackHandler(onBack = onBackPressed)
    LazyColumn(
        contentPadding = PaddingValues(bottom = 135.dp),
        modifier = modifier,
    ) {
        item {
            PersonHeader(
                person = person,
                bioFocusRequester = initialFocus,
                onBioClick = { showFullBio = true },
                modifier = Modifier
                    .padding(horizontal = childPadding.start)
                    .padding(top = 72.dp, bottom = 16.dp)
            )
        }
        itemsIndexed(person.credits, key = { _, credits -> credits.kind }) { index, credits ->
            MoviesRow(
                title = stringResource(credits.kind.titleRes),
                titleStyle = MaterialTheme.typography.titleMedium,
                movieList = credits.titles,
                onMovieSelected = onMovieSelected,
                modifier = if (index == 0 && person.bio.isEmpty()) {
                    Modifier.focusRequester(initialFocus)
                } else {
                    Modifier
                }
            )
        }
    }
    if (person.bio.isNotEmpty() || person.credits.isNotEmpty()) {
        LaunchedEffect(Unit) { initialFocus.requestFocus() }
    }
    if (showFullBio) {
        FullBioDialog(person = person, onDismissRequest = { showFullBio = false })
    }
}

@Composable
private fun PersonHeader(
    person: PersonDetails,
    bioFocusRequester: FocusRequester,
    onBioClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .crossfade(true)
                .data(person.photoUri.ifEmpty { null })
                .build(),
            contentDescription = person.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(200.dp)
                .aspectRatio(2f / 3f)
                .clip(JetStreamCardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(40.dp))
        Column(modifier = Modifier.fillMaxWidth(0.75f)) {
            Text(
                text = person.name,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (person.lifeSummary.isNotEmpty()) {
                Text(
                    text = person.lifeSummary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .alpha(0.75f)
                )
            }
            if (person.bio.isNotEmpty()) {
                // Focusable so the page opens on the header; selecting it shows the whole bio.
                Surface(
                    onClick = onBioClick,
                    shape = ClickableSurfaceDefaults.shape(JetStreamCardShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        focusedContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .focusRequester(bioFocusRequester)
                ) {
                    Text(
                        text = person.bio,
                        style = BioTextStyle(),
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(8.dp)
                            .alpha(0.85f)
                    )
                }
            }
        }
    }
}

/** The whole bio, one focusable paragraph at a time so the D-pad scrolls through it. */
@Composable
private fun FullBioDialog(person: PersonDetails, onDismissRequest: () -> Unit) {
    val paragraphs = remember(person.bio) {
        person.bio.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    }
    val first = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = JetStreamCardShape,
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .fillMaxHeight(0.8f)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = person.name,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                    )
                }
                itemsIndexed(paragraphs) { index, paragraph ->
                    Surface(
                        onClick = {},
                        shape = ClickableSurfaceDefaults.shape(JetStreamCardShape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor =
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            focusedContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier
                    ) {
                        Text(
                            text = paragraph,
                            style = BioTextStyle(),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
        LaunchedEffect(Unit) { first.requestFocus() }
    }
}

@Composable
private fun BioTextStyle() = MaterialTheme.typography.titleSmall.copy(
    fontSize = 15.sp,
    fontWeight = FontWeight.Normal,
    lineHeight = 22.sp
)

private val CreditKind.titleRes: Int
    get() = when (this) {
        CreditKind.MoviesActing -> R.string.person_movies_acting
        CreditKind.MoviesDirecting -> R.string.person_movies_directing
        CreditKind.MoviesWriting -> R.string.person_movies_writing
        CreditKind.ShowsActing -> R.string.person_shows_acting
        CreditKind.ShowsDirecting -> R.string.person_shows_directing
        CreditKind.ShowsWriting -> R.string.person_shows_writing
    }
