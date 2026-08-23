package com.geoalign.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Every raw colour value in the app. Named by role, not by shade number, so a token can be
 * retuned without its name becoming a lie.
 *
 * Ground is a warm neutral rather than pure white; the single accent is a deep teal. Green is
 * deliberately absent from the [ColorScheme] below and lives only in [GeoStatusColors] — see the
 * note there.
 */
object GeoPalette {
    // Accent.
    val TealDeep = Color(0xFF0E5C63)        // 7.70:1 on white, 7.08:1 on WarmBg
    val TealContainer = Color(0xFFCFE6E6)
    val OnTealContainer = Color(0xFF043034)
    val TealInverse = Color(0xFF7CD1D0)

    // Verified / aligned. Reserved — see GeoStatusColors.
    val VerifiedGreen = Color(0xFF17733C)   // 5.91:1 on white
    val VerifiedContainer = Color(0xFFDDF1E3)
    val OnVerifiedContainer = Color(0xFF0B4423)

    // Attention.
    val Amber = Color(0xFF8A5A00)
    val AmberContainer = Color(0xFFFBEED0)
    val OnAmberContainer = Color(0xFF402A00)

    // Blocked / error.
    val ErrorRed = Color(0xFFB3261E)
    val ErrorContainer = Color(0xFFF9DEDC)
    val OnErrorContainer = Color(0xFF410E0B)

    // Neutrals.
    val WarmBg = Color(0xFFF7F5F2)
    val WarmSurface = Color(0xFFFFFFFF)
    val WarmSurfaceVariant = Color(0xFFEFEAE4)
    val InkPrimary = Color(0xFF1E1C1A)      // ~15:1 on WarmSurface
    val InkSecondary = Color(0xFF5C574F)    // 7.17:1 on white
    val OutlineStrong = Color(0xFF8E877C)   // 3.55:1 — meets WCAG 1.4.11 for interactive borders
    val OutlineSoft = Color(0xFFDED8CF)     // dividers only; not for interactive edges

    // Elevated-container ramp. M3 uses these for DropdownMenu, AlertDialog and elevated Card.
    val SurfaceDim = Color(0xFFE3DED7)
    val SurfaceBright = Color(0xFFFFFFFF)
    val SurfaceContainerLowest = Color(0xFFFFFFFF)
    val SurfaceContainerLow = Color(0xFFFBF9F6)
    val SurfaceContainer = Color(0xFFF5F2ED)
    val SurfaceContainerHigh = Color(0xFFEFEBE5)
    val SurfaceContainerHighest = Color(0xFFE9E4DD)

    // Secondary: a desaturated teal-slate. Drives FilterChip selected state in the browser tab strip.
    val Slate = Color(0xFF445653)
    val SlateContainer = Color(0xFFE2E8E6)
    val OnSlateContainer = Color(0xFF17211F)

    val InverseSurface = Color(0xFF322F2B)
    val InverseOnSurface = Color(0xFFF5F2EE)
    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)
}

/**
 * Every slot is assigned explicitly. Slots left unspecified fall back to the M3 *baseline*
 * palette, which is a cool purple-grey — that is how menus and dialogs end up looking like they
 * belong to a different app while the rest of the screen looks correct.
 */
val GeoLightColors: ColorScheme = lightColorScheme(
    primary = GeoPalette.TealDeep,
    onPrimary = GeoPalette.White,
    primaryContainer = GeoPalette.TealContainer,
    onPrimaryContainer = GeoPalette.OnTealContainer,

    secondary = GeoPalette.Slate,
    onSecondary = GeoPalette.White,
    secondaryContainer = GeoPalette.SlateContainer,
    onSecondaryContainer = GeoPalette.OnSlateContainer,

    tertiary = GeoPalette.Amber,
    onTertiary = GeoPalette.White,
    tertiaryContainer = GeoPalette.AmberContainer,
    onTertiaryContainer = GeoPalette.OnAmberContainer,

    error = GeoPalette.ErrorRed,
    onError = GeoPalette.White,
    errorContainer = GeoPalette.ErrorContainer,
    onErrorContainer = GeoPalette.OnErrorContainer,

    background = GeoPalette.WarmBg,
    onBackground = GeoPalette.InkPrimary,
    surface = GeoPalette.WarmSurface,
    onSurface = GeoPalette.InkPrimary,
    surfaceVariant = GeoPalette.WarmSurfaceVariant,
    onSurfaceVariant = GeoPalette.InkSecondary,

    surfaceDim = GeoPalette.SurfaceDim,
    surfaceBright = GeoPalette.SurfaceBright,
    surfaceContainerLowest = GeoPalette.SurfaceContainerLowest,
    surfaceContainerLow = GeoPalette.SurfaceContainerLow,
    surfaceContainer = GeoPalette.SurfaceContainer,
    surfaceContainerHigh = GeoPalette.SurfaceContainerHigh,
    surfaceContainerHighest = GeoPalette.SurfaceContainerHighest,

    outline = GeoPalette.OutlineStrong,
    outlineVariant = GeoPalette.OutlineSoft,

    inverseSurface = GeoPalette.InverseSurface,
    inverseOnSurface = GeoPalette.InverseOnSurface,
    inversePrimary = GeoPalette.TealInverse,
    scrim = GeoPalette.Black,

    // Not `primary`. M3 tints elevated surfaces with surfaceTint; leaving the default would put a
    // teal wash on every Card, DropdownMenu and AlertDialog. Equal to `surface` makes tonal
    // elevation a visual no-op, which is what this flat design wants.
    surfaceTint = GeoPalette.WarmSurface,
)

/**
 * Status colours, deliberately kept **outside** [ColorScheme].
 *
 * The rule for this app is that green means one specific thing — the browser is verified as
 * aligned with the current exit — and nothing else. Putting green in a ColorScheme slot would let
 * any Button, Chip or Switch inherit it by accident and quietly erode that meaning. Reaching these
 * requires naming [GeoTheme.status], so every use site is greppable.
 */
@Immutable
data class GeoStatusColors(
    val verified: Color,
    val onVerified: Color,
    val verifiedContainer: Color,
    val onVerifiedContainer: Color,
    val attention: Color,
    val attentionContainer: Color,
    val onAttentionContainer: Color,
    val blocked: Color,
    val blockedContainer: Color,
    val onBlockedContainer: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color,
    val monoText: Color,
)

val GeoStatusLight = GeoStatusColors(
    verified = GeoPalette.VerifiedGreen,
    onVerified = GeoPalette.White,
    verifiedContainer = GeoPalette.VerifiedContainer,
    onVerifiedContainer = GeoPalette.OnVerifiedContainer,
    attention = GeoPalette.Amber,
    attentionContainer = GeoPalette.AmberContainer,
    onAttentionContainer = GeoPalette.OnAmberContainer,
    blocked = GeoPalette.ErrorRed,
    blockedContainer = GeoPalette.ErrorContainer,
    onBlockedContainer = GeoPalette.OnErrorContainer,
    neutralContainer = GeoPalette.WarmSurfaceVariant,
    onNeutralContainer = GeoPalette.InkSecondary,
    monoText = GeoPalette.InkSecondary,
)
