package com.liquid.browser.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.Modifier
import com.liquid.browser.core.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * iOS 式按壓高亮（取代預設的灰色閃一下）：按下立即蓋一層半透明，放開後淡出。
 * 很快的點擊也至少亮 90ms，讓使用者看得到回饋。全 App 透過 LocalIndication 提供，
 * 所有 clickable／combinedClickable（分組清單列、網站列、下載卡片…）自動套用。
 * 疊加色用中性灰，淺色／深色背景都看得出來。
 */
object PressHighlight : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7

    private class Node(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private val alpha = Animatable(0f)

        override fun onAttach() {
            coroutineScope.launch {
                var pressedAt = 0L
                source.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> {
                            pressedAt = System.currentTimeMillis()
                            launch { alpha.animateTo(1f, if (Motion.reduced) tween(0) else tween(60)) }
                        }
                        is PressInteraction.Release, is PressInteraction.Cancel -> launch {
                            val held = System.currentTimeMillis() - pressedAt
                            if (held < 90) delay(90 - held)
                            alpha.animateTo(0f, if (Motion.reduced) tween(0) else tween(260))
                        }
                    }
                }
            }
            coroutineScope.launch { androidx.compose.runtime.snapshotFlow { alpha.value }.collect { invalidateDraw() } }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (alpha.value > 0f) drawRect(Color(0xFF8E8E93).copy(alpha = 0.22f * alpha.value))
        }
    }
}
