@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.droidfinder

import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DensityChangeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun attachedDragSourceSurvivesLiveDensityChanges() {
        val scale = mutableFloatStateOf(1f)
        var pixels = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale.floatValue)) {
                Box(
                    Modifier.size(100.dp)
                        .dragAndDropSource(block = { awaitEachGesture { awaitFirstDown() } })
                        .onGloballyPositioned { pixels = it.size.width },
                )
            }
        }
        compose.runOnIdle { assertEquals(100, pixels) }
        compose.runOnIdle { scale.floatValue = 2f }
        compose.runOnIdle { assertEquals(200, pixels) }
        compose.runOnIdle { scale.floatValue = 1f }
        compose.runOnIdle { assertEquals(100, pixels) }
    }
}
