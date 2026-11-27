package com.panclone.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用内文件/目录上传协调器。
 *
 * 小飞机网盘不开放上传接口，这里保留入口但统一返回「暂不支持」，
 * 由调用方（设置页/外部分享上传）展示提示。
 */
object UploadCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val UNSUPPORTED = "小飞机网盘暂不支持上传"

    fun uploadFiles(files: List<File>, remoteDir: String, result: (Int, String?) -> Unit) {
        scope.launch {
            withContext(Dispatchers.Main) { result(0, UNSUPPORTED) }
        }
    }

    fun uploadFolder(folder: File, remoteDir: String, result: (Int, String?) -> Unit) {
        scope.launch {
            withContext(Dispatchers.Main) { result(0, UNSUPPORTED) }
        }
    }
}
