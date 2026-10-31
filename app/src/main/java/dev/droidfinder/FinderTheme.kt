package dev.droidfinder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalFinderDark = staticCompositionLocalOf { false }
val Ink: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFFE4E9F1) else Color(0xFF273341)
val Muted: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFF9BA8BA) else Color(0xFF7B8491)
val Border: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFF343D4B) else Color(0xFFE1E5EA)
val Sidebar: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFF222A35) else Color(0xFFF0F3F7)
val CanvasBackground: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFF1B222C) else Color.White
val AppBackground: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFF252D39) else Color(0xFFF7F8FA)
val SubtleSurface: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFF202833) else Color(0xFFFAFBFC)
val SelectionSurface: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFF2A4368) else Color(0xFFE4EEFC)
val ToolbarSurface: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFF343E4D) else Color(0xFFE9ECF0)
val SelectedInk: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFFB6D6FF) else Color(0xFF235B9B)
