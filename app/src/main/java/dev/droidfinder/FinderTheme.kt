package dev.droidfinder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalFinderDark = staticCompositionLocalOf { false }
val Ink: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFFEDEDEF) else Color(0xFF282A2F)
val Muted: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFFA6A8AF) else Color(0xFF747880)
val Border: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFF3A3B3F) else Color(0xFFE5E6E9)
val Sidebar: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFF292A2D) else Color(0xFFF1F2F4)
val CanvasBackground: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFF202123) else Color.White
val AppBackground: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFF26272A) else Color(0xFFFAFAFB)
val SubtleSurface: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFF252628) else Color(0xFFFAFAFB)
val SelectionSurface: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFF304365) else Color(0xFFE8F0FE)
val ToolbarSurface: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFF303135) else Color(0xFFEFF0F2)
val SelectedInk: Color
    @Composable get() = if (LocalFinderDark.current) Color(0xFFB7CEFC) else Color(0xFF2465D4)
