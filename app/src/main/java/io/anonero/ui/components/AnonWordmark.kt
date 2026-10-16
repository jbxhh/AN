package io.anonero.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * Reusable ANON wordmark extracted from the home-screen title, without brackets.
 */
@Composable
fun AnonWordmark(
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge,
    color: Color = Color.Unspecified,
) {
    Text(
        text = "ΛИ0И",
        modifier = modifier,
        style = style,
        color = color,
    )
}
