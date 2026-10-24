package com.google.jetstream.presentation.screens.search

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Voice search inside the app: Android's speech recognizer with no system UI, so the search
 * screen shows the listening state and the words as they're heard. Needs the microphone
 * permission, asked for the first time the mic is pressed.
 */
@Stable
class VoiceSearchState internal constructor(
    /** False when the device has no recognition service; the mic button is hidden then. */
    val isAvailable: Boolean,
) {
    var isListening by mutableStateOf(false)
        internal set

    internal var toggleAction: () -> Unit = {}

    /** Starts listening, or stops early when already listening. */
    fun toggle() = toggleAction()
}

/**
 * [onPartial] gets the words so far while speaking; [onResult] gets the final text once
 * speaking stops (not called when nothing was understood).
 */
@Composable
fun rememberVoiceSearch(
    onPartial: (String) -> Unit,
    onResult: (String) -> Unit,
): VoiceSearchState {
    val context = LocalContext.current
    val currentOnPartial by rememberUpdatedState(onPartial)
    val currentOnResult by rememberUpdatedState(onResult)
    val state = remember {
        VoiceSearchState(isAvailable = SpeechRecognizer.isRecognitionAvailable(context))
    }
    if (!state.isAvailable) return state

    val recognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(recognizer) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                state.isListening = true
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.bestMatch()?.let { currentOnPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                state.isListening = false
                results.bestMatch()?.let { currentOnResult(it) }
            }

            override fun onError(error: Int) {
                // Includes "nothing heard" and "didn't understand": just stop listening.
                state.isListening = false
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose { recognizer.destroy() }
    }

    val start = {
        state.isListening = true
        recognizer.startListening(recognizerIntent(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) start() }

    state.toggleAction = {
        when {
            state.isListening -> {
                // Stop now and use what was heard so far.
                recognizer.stopListening()
            }
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> start()
            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    return state
}

private fun recognizerIntent(context: Context) =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

private fun Bundle?.bestMatch(): String? =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.trim()
        ?.ifEmpty { null }
