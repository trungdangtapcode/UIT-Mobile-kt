package vn.edu.uit.mobile.designsystem

import androidx.compose.foundation.shape.AbsoluteCutCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Physical corner geometry: the larger cut always belongs to the bottom-right. */
object TechnicalShapes {
    private fun cut(small: Dp, bottomRight: Dp) = AbsoluteCutCornerShape(
        topLeft = small,
        topRight = small,
        bottomRight = bottomRight,
        bottomLeft = small
    )

    val Tile = cut(3.dp, 8.dp)
    val Control = cut(4.dp, 12.dp)
    val Field = cut(6.dp, 16.dp)
    val Panel = cut(8.dp, 28.dp)
    val Dialog = cut(8.dp, 32.dp)
}
