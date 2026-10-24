package com.google.jetstream.data.util

import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * A list loaded by [block] off the main thread (sorting and mapping a whole library there got
 * the app killed for ANR on the Chromecast); a failed load emits an empty list.
 */
fun <T> listFlow(block: suspend () -> List<T>): Flow<List<T>> = flow {
    val list = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
    emit(list)
}.flowOn(Dispatchers.Default)

/**
 * A–Z key: case- and accent-insensitive, with punctuation ignored, so "Airplane!" sorts
 * before "Airplane II" and "Amélie" with the A's.
 */
fun sortKey(title: String): String =
    Normalizer.normalize(title, Normalizer.Form.NFD)
        .replace(Accents, "")
        .lowercase(Locale.ROOT)
        .replace(NonAlphanumerics, " ")
        .trim()

/** Sorts by [sortKey] of [title], computing each key once rather than per comparison. */
fun <T> List<T>.sortedByTitle(title: (T) -> String): List<T> =
    map { sortKey(title(it)) to it }.sortedBy { it.first }.map { it.second }

private val Accents = Regex("\\p{M}+")
private val NonAlphanumerics = Regex("[^\\p{L}\\p{N}]+")
