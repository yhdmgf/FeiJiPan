package com.panclone.app.data

import com.panclone.app.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 应用内文件/目录上传，目录模式保持根文件夹和完整相对树。 */
object UploadCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun uploadFiles(files: List<File>, remoteDir: String, result: (Int, String?) -> Unit) {
        scope.launch {
            var count = 0
            files.filter { it.isFile && it.canRead() }.forEach { file ->
                val task = App.instance.transfers.addUpload(file, remoteDir)
                App.instance.transfers.start(task.id)
                count++
            }
            withContext(Dispatchers.Main) { result(count, null) }
        }
    }

    fun uploadFolder(folder: File, remoteDir: String, result: (Int, String?) -> Unit) {
        scope.launch {
            runCatching {
                require(folder.isDirectory && folder.canRead()) { "文件夹不可读取" }
                val rootRemote = join(remoteDir, folder.name.ifBlank { "文件夹" })
                ensureDirectory(rootRemote)
                val directories = folder.walkTopDown()
                    .filter { it.isDirectory && it != folder }
                    .sortedBy { it.relativeTo(folder).invariantSeparatorsPath.count { c -> c == '/' } }
                    .toList()
                directories.forEach { dir ->
                    ensureDirectory(join(rootRemote, dir.relativeTo(folder).invariantSeparatorsPath))
                }
                var count = 0
                folder.walkTopDown().filter { it.isFile && it.canRead() }.forEach { file ->
                    val relativeParent = file.parentFile?.relativeTo(folder)?.invariantSeparatorsPath.orEmpty()
                    val targetDir = if (relativeParent == "." || relativeParent.isBlank()) rootRemote
                    else join(rootRemote, relativeParent)
                    val task = App.instance.transfers.addUpload(file, targetDir)
                    App.instance.transfers.start(task.id)
                    count++
                }
                count
            }.onSuccess { count ->
                withContext(Dispatchers.Main) { result(count, null) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) { result(0, error.message ?: "文件夹上传失败") }
            }
        }
    }

    private suspend fun ensureDirectory(path: String) {
        if (path == "/") return
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        val name = path.substringAfterLast('/')
        val response = App.instance.api.createFolder(parent, name)
        if (response.errno != 0 && response.errno != -8 && response.errno != -12) {
            error(App.instance.api.errnoMessage(response.errno, response.showMsg))
        }
    }

    private fun join(parent: String, child: String): String {
        val base = if (parent == "/") "" else parent.trimEnd('/')
        return "$base/${child.trim('/')}".replace(Regex("/+"), "/")
    }
}
