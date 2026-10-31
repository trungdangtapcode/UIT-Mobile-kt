package vn.edu.uit.mobile.designsystem

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vn.edu.uit.mobile.core.designsystem.R

@Composable fun UitEmblem(modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.uit_emblem), stringResource(R.string.content_description_emblem), Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit)
    }
}

/** A single, quiet campus photograph behind the page masthead. */
@Composable fun CampusBackdrop(modifier: Modifier = Modifier) {
    val paper = MaterialTheme.colorScheme.background
    val dark = paper.luminance() < .2f
    Box(modifier) {
        Image(
            painterResource(R.drawable.uit_campus),
            null,
            Modifier.matchParentSize().blur(8.dp),
            contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd,
            alpha = if (dark) .12f else .18f
        )
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(paper, paper.copy(alpha = .2f)))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(paper.copy(alpha = .05f), paper))))
    }
}

fun courseIcon(code: String): ImageVector {
    val name = code.uppercase()
    return when {
        name.contains("IT004") || name.contains("DATABASE") || name.contains("SQL") -> Icons.Outlined.Storage
        name.contains("IT003") || name.contains("ALGORITHM") -> Icons.Outlined.AccountTree
        name.startsWith("SE") || name.contains("SOFTWARE") -> Icons.Outlined.DataObject
        name.startsWith("CS") || name.contains("MACHINE") || name.contains("AI") -> Icons.Outlined.Memory
        name.startsWith("NT") || name.contains("NETWORK") -> Icons.Outlined.Router
        name.contains("SECURITY") -> Icons.Outlined.Security
        else -> Icons.Outlined.Terminal
    }
}
