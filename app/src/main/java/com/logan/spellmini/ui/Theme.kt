package com.logan.spellmini.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Palette lifted from the Spell rush mockups: white canvas, near-black ink, soft grey bubbles. */
object Ink {
    val Black = Color(0xFF1C1C1E)
    val Body = Color(0xFF2C2C2E)
    val Muted = Color(0xFF8E8E93)
    val Faint = Color(0xFFC7C7CC)
    val Bubble = Color(0xFFF2F2F4)
    val Line = Color(0xFFE9E9EC)
    val Blue = Color(0xFF2F6FED)
    val Amber = Color(0xFFB7791F)
    val Red = Color(0xFFC0392B)
    val Green = Color(0xFF2E8B57)
}

private val colors = lightColorScheme(
    primary = Ink.Black,
    onPrimary = Color.White,
    secondary = Ink.Blue,
    background = Color.White,
    onBackground = Ink.Black,
    surface = Color.White,
    onSurface = Ink.Black,
    surfaceVariant = Ink.Bubble,
    onSurfaceVariant = Ink.Muted,
    outline = Ink.Line,
    error = Ink.Red,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SpellTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
}
