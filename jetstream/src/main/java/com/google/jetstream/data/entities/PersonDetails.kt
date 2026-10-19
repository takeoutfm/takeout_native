package com.google.jetstream.data.entities

/** A cast or crew member's page: who they are, then their credits by kind. */
data class PersonDetails(
    val id: String,
    val name: String,
    val photoUri: String,
    /** Year of birth (and death) and birthplace, e.g. "1956–2023 · Chicago, Illinois, USA". */
    val lifeSummary: String,
    val bio: String,
    /** Non-empty credit lists only, in [CreditKind] order. */
    val credits: List<Credits>,
)

data class Credits(
    val kind: CreditKind,
    val titles: MovieList,
)

enum class CreditKind {
    MoviesActing, MoviesDirecting, MoviesWriting, ShowsActing, ShowsDirecting, ShowsWriting
}
