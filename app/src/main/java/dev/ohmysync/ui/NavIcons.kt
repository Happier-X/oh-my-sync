package dev.ohmysync.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private val navIcons = listOf("任务", "记录", "账户").associateWith { item ->
    ImageVector.Builder(item, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            when (item) {
                "任务" -> {
                    moveTo(3f, 5f); lineTo(9f, 5f); lineTo(11f, 8f); lineTo(21f, 8f)
                    lineTo(21f, 20f); lineTo(3f, 20f); close()
                }
                "记录" -> {
                    moveTo(12f, 3f); arcTo(9f, 9f, 0f, true, true, 12f, 21f)
                    arcTo(9f, 9f, 0f, true, true, 12f, 3f)
                    moveTo(12f, 6f); lineTo(12f, 12f); lineTo(16f, 14f)
                }
                else -> {
                    moveTo(6f, 19f); curveTo(0f, 19f, 0f, 10f, 6f, 10f)
                    curveTo(6f, 2f, 18f, 2f, 19f, 11f)
                    curveTo(25f, 11f, 25f, 19f, 19f, 19f); close()
                }
            }
        }
    }.build()
}

fun navIcon(label: String): ImageVector = navIcons.getValue(label)
