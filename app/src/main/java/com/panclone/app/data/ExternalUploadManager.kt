package com.panclone.app.data

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingExternalUpload(val uris: List<Uri>)

/** 暂存第三方 App 通过 ACTION_SEND / SEND_MULTIPLE 分享来的文件。 */
class ExternalUploadManager {
    private val _pending = MutableStateFlow<PendingExternalUpload?>(null)
    val pending: StateFlow<PendingExternalUpload?> = _pending.asStateFlow()

    fun submit(uris: List<Uri>) {
        val unique = uris.distinct()
        if (unique.isNotEmpty()) _pending.value = PendingExternalUpload(unique)
    }

    fun consume() {
        _pending.value = null
    }
}
