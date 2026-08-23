package com.geoalign.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalGeoStatusColors = staticCompositionLocalOf { GeoStatusLight }

/**
 * The app theme. Light only for now, and deliberately not dynamic-colour: the palette carries
 * meaning here (green = verified, amber = attention, red = blocked), so letting the device
 * wallpaper recolour it would break the thing the colours are for.
 *
 * Adding dark mode later is a `GeoDarkColors` + `GeoStatusDark` pair and one `if` in this
 * function — nothing outside this package names a colour.
 */
@Composable
fun GeoAlignTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGeoStatusColors provides GeoStatusLight) {
        MaterialTheme(
            colorScheme = GeoLightColors,
            typography = GeoTypography,
            shapes = GeoShapes,
            content = content,
        )
    }
}

/** Accessor for the tokens that intentionally sit outside [MaterialTheme]. */
object GeoTheme {
    val status: GeoStatusColors
        @Composable @ReadOnlyComposable get() = LocalGeoStatusColors.current
}
