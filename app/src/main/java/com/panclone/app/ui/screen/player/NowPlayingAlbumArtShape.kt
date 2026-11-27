package com.panclone.app.ui.screen.player

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 当前播放封面的缓慢波浪唱片轮廓；编译类名为 NowPlayingAlbumArtShape$Waved。 */
sealed class NowPlayingAlbumArtShape : Shape {
    data object Waved : NowPlayingAlbumArtShape() {
        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
        ): Outline {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val base = min(size.width, size.height) * 0.485f
            val count = 24
            val points = List(count) { index ->
                val angle = -PI / 2 + 2 * PI * index / count
                val radius = base * if (index % 2 == 0) 1f else 0.92f
                androidx.compose.ui.geometry.Offset(
                    x = centerX + cos(angle).toFloat() * radius,
                    y = centerY + sin(angle).toFloat() * radius,
                )
            }
            fun midpoint(a: androidx.compose.ui.geometry.Offset, b: androidx.compose.ui.geometry.Offset) =
                androidx.compose.ui.geometry.Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)

            val path = Path()
            path.moveTo(midpoint(points.last(), points.first()).x, midpoint(points.last(), points.first()).y)
            points.forEachIndexed { index, point ->
                val next = points[(index + 1) % count]
                val mid = midpoint(point, next)
                path.quadraticBezierTo(point.x, point.y, mid.x, mid.y)
            }
            path.close()
            return Outline.Generic(path)
        }
    }
}
