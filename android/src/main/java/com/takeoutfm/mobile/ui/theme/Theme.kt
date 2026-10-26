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
import com.takeoutfm.shared.settings.ThemeMode

private val TakeoutBlue = Color(0xFF3F6CB0)
private val TakeoutBlueLight = Color(0xFFA8C7FA)

/** Whether [mode] means dark right now. */
@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/**
 * Material 3, light or dark as [mode] says (the system's setting by default), with the
 * wallpaper's dynamic colours (Material You) on Android 12+, as the Flutter app does; a blue
 * seed scheme before that.
 */
@Composable
fun TakeoutTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = TakeoutBlueLight, secondary = TakeoutBlueLight)
        else -> lightColorScheme(primary = TakeoutBlue, secondary = TakeoutBlue)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
