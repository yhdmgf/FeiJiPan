package com.panclone.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap.Companion.Round
import androidx.compose.ui.graphics.StrokeJoin.Companion.Round as RoundJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * iOS 26 风格底栏图标。
 *
 * 规范：24x24 画布、1.8 线宽、圆头圆角、纯描边。
 * 选中态由调用方 tint 成主题色（stroke 用黑色占位，Icon 的 tint 会覆盖）。
 */
object IosIcons {

    /** 文件：带折角的文档 */
    val Files: ImageVector by lazy {
        Builder(
            name = "Files",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // 外框 + 折角
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = Round,
                strokeLineJoin = RoundJoin,
            ) {
                moveTo(6f, 3f)
                lineTo(14f, 3f)
                lineTo(19f, 8f)
                lineTo(19f, 21f)
                lineTo(6f, 21f)
                close()
            }
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = Round,
                strokeLineJoin = RoundJoin,
            ) {
                moveTo(14f, 3f)
                lineTo(14f, 8f)
                lineTo(19f, 8f)
            }
            // 内容线
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = Round,
                strokeLineJoin = RoundJoin,
            ) {
                moveTo(9f, 12.5f)
                lineTo(16f, 12.5f)
                moveTo(9f, 16.5f)
                lineTo(16f, 16.5f)
            }
        }.build()
    }

    /** 传输：上下双向箭头 */
    val Transfer: ImageVector by lazy {
        Builder(
            name = "Transfer",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = Round,
                strokeLineJoin = RoundJoin,
            ) {
                moveTo(8f, 20f)
                lineTo(8f, 4.5f)
                moveTo(4.5f, 8f)
                lineTo(8f, 4.5f)
                lineTo(11.5f, 8f)
                moveTo(16f, 4f)
                lineTo(16f, 19.5f)
                moveTo(12.5f, 16f)
                lineTo(16f, 19.5f)
                lineTo(19.5f, 16f)
            }
        }.build()
    }

    /** 分享：托盘 + 上箭头 */
    val Share: ImageVector by lazy {
        Builder(
            name = "Share",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = Round,
                strokeLineJoin = RoundJoin,
            ) {
                moveTo(12f, 3.5f)
                lineTo(12f, 14.5f)
                moveTo(8.2f, 7.3f)
                lineTo(12f, 3.5f)
                lineTo(15.8f, 7.3f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = Round,
                strokeLineJoin = RoundJoin,
            ) {
                moveTo(6.5f, 11f)
                lineTo(5f, 11f)
                lineTo(5f, 21f)
                lineTo(19f, 21f)
                lineTo(19f, 11f)
                lineTo(17.5f, 11f)
            }
        }.build()
    }

    /** 我的：头像轮廓（头 + 肩） */
    /** 音乐标签页：自绘音符，风格与底栏其它线性图标一致。 */
    val Music: ImageVector by lazy {
        Builder(
            name = "Music",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = Round,
                strokeLineJoin = RoundJoin,
            ) {
                // 单音符：收在 5~19 内，尺寸与文件/传输图标一致
                moveTo(10f, 17f)
                curveTo(10f, 18.1f, 9.1f, 19f, 8f, 19f)
                curveTo(6.9f, 19f, 6f, 18.1f, 6f, 17f)
                curveTo(6f, 15.9f, 6.9f, 15f, 8f, 15f)
                curveTo(8.55f, 15f, 9.05f, 15.2f, 9.4f, 15.5f)
                moveTo(10f, 17f)
                lineTo(10f, 5.5f)
                lineTo(19f, 3.5f)
                lineTo(19f, 14f)
            }
        }.build()
    }

    val Mine: ImageVector by lazy {
        Builder(
            name = "Mine",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = Round,
                strokeLineJoin = RoundJoin,
            ) {
                // 头
                moveTo(16.5f, 9f)
                curveTo(16.5f, 11.485f, 14.485f, 13.5f, 12f, 13.5f)
                curveTo(9.515f, 13.5f, 7.5f, 11.485f, 7.5f, 9f)
                curveTo(7.5f, 6.515f, 9.515f, 4.5f, 12f, 4.5f)
                curveTo(14.485f, 4.5f, 16.5f, 6.515f, 16.5f, 9f)
                close()
                // 肩
                moveTo(4f, 20.5f)
                curveTo(4f, 17.19f, 7.58f, 14.5f, 12f, 14.5f)
                curveTo(16.42f, 14.5f, 20f, 17.19f, 20f, 20.5f)
            }
        }.build()
    }
}
