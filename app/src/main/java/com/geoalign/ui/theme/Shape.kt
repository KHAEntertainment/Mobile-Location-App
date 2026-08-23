package com.geoalign.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val GeoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Named shapes for components that shouldn't have to pick a size slot. */
object GeoShapeTokens {
    val button = RoundedCornerShape(14.dp)
    val statusSurface = RoundedCornerShape(20.dp)
    val row = RoundedCornerShape(14.dp)
}
