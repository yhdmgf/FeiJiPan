package com.panclone.app.ui.ios

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/** 无涟漪点击（iOS 风格按压反馈由调用方自绘） */
inline fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    crossinline onClick: () -> Unit,
): Modifier = composed {
    clickable(
        enabled = enabled,
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
    ) { onClick() }
}

fun Context.copyText(text: String?) {
    text ?: return
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("text", text))
}
