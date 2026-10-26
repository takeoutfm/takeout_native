package com.takeoutfm.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.os.Build

private val TakeoutBlue = Color(0xFF3F6CB0)

/**
 * Material 3 following the system's light/dark setting, with the wallpaper's dynamic colours
 * (Material You) on Android 12+, as the Flutter app does; a blue seed scheme before that.
 */
@Composable
fun TakeoutTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = TakeoutBlue.copy(alpha = 1f), secondary = TakeoutBlue)
        else -> lightColorScheme(primary = TakeoutBlue, secondary = TakeoutBlue)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
