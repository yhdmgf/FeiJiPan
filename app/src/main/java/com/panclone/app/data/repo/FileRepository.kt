package com.panclone.app.data.repo

import com.panclone.app.App
import com.panclone.app.data.model.PanFile
import com.panclone.app.data.model.RecycleItem
import com.panclone.app.data.model.ShareRecord
import com.panclone.app.data.model.ShareResponse
import com.panclone.app.data.net.PanApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件仓库：封装接口调用，把 errno 翻成中文提示，并把"登录失效"单独标出来。
 */
class FileRepository(private val api: PanApi) {

    sealed interface Result<out T> {
        data class Ok<T>(val data: T) : Result<T>
        data class Err(val message: String, val authFailed: Boolean = false) : Result<Nothing>
    }

    suspend fun list(
        dir: String,
        page: Int = 1,
        num: Int = 200,
        order: String = "name",
        desc: Boolean = false,
    ): Result<List<PanFile>> = withContext(Dispatchers.IO) {
        val resp = api.list(dir, page, num, order, if (desc) 1 else 0)
        when {
            resp.errno == 0 -> Result.Ok(resp.list)
            else -> Result.Err(
                api.errnoMessage(resp.errno, resp.showMsg),
                api.isAuthError(resp.errno),
            )
        }
    }

    suspend fun search(key: String, dir: String): Result<List<PanFile>> = withContext(Dispatchers.IO) {
        val resp = api.search(key, dir)
        if (resp.errno == 0) Result.Ok(resp.list)
        else Result.Err(api.errnoMessage(resp.errno, resp.showMsg), api.isAuthError(resp.errno))
    }

    suspend fun createFolder(dir: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val r = api.createFolder(dir, name)
        if (r.errno == 0) Result.Ok(Unit)
        else Result.Err(api.errnoMessage(r.errno, r.showMsg), api.isAuthError(r.errno))
    }

    suspend fun rename(path: String, newName: String, isDir: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        val r = api.rename(path, newName, isDir)
        if (r.errno == 0) Result.Ok(Unit)
        else Result.Err(api.errnoMessage(r.errno, r.showMsg), api.isAuthError(r.errno))
    }

    suspend fun moveOrCopy(paths: List<String>, dest: String, copy: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val r = api.moveOrCopy(paths, dest, copy)
            if (r.errno == 0) Result.Ok(Unit)
            else Result.Err(api.errnoMessage(r.errno, r.showMsg), api.isAuthError(r.errno))
        }

    suspend fun delete(paths: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        val r = api.delete(paths)
        if (r.errno == 0) Result.Ok(Unit)
        else Result.Err(api.errnoMessage(r.errno, r.showMsg), api.isAuthError(r.errno))
    }

    suspend fun share(fsIds: List<Long>, period: Int, pwd: String): Result<ShareResponse> =
        withContext(Dispatchers.IO) {
            val r = api.createShare(fsIds, period = period, pwd = pwd)
            if (r.errno == 0) Result.Ok(r)
            else Result.Err(api.errnoMessage(r.errno, r.showMsg), api.isAuthError(r.errno))
        }

    suspend fun modifyShare(shareId: Long, fsIds: List<Long>, period: Int, pwd: String): Result<ShareResponse> =
        withContext(Dispatchers.IO) {
            val r = api.modifyShare(shareId, fsIds, period, pwd)
            if (r.errno == 0) Result.Ok(r)
            else Result.Err(api.errnoMessage(r.errno, r.showMsg), api.isAuthError(r.errno))
        }

    suspend fun dlink(path: String): Result<String> = withContext(Dispatchers.IO) {
        val url = api.locatedownload(path)
        if (url.isNotBlank()) Result.Ok(url) else Result.Err("获取直链失败")
    }

    /** 网页版省流播放 URL（m3u8）。quality: 2160/720/480/360。 */
    suspend fun videoStream(path: String, quality: Int): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val m3u8 = api.videoM3u8(path, quality)
            if (m3u8.isBlank()) throw IllegalStateException("获取视频流失败")
            // 只保留一个 m3u8 小文件（几 KB），每次覆盖；ts 分片是带签名绝对 URL，
            // ExoPlayer 直接流式读取、不落缓存，所以几乎不占空间。
            val f = java.io.File(App.instance.cacheDir, "stream.m3u8")
            f.writeText(m3u8)
            "file://${f.absolutePath}"
        }.fold(
            onSuccess = { Result.Ok(it) },
            onFailure = { Result.Err(it.message ?: "获取视频流失败") },
        )
    }

    // ==================== 音频分类列表 ====================

    /** category=2 音频；分页抓取直到不足 num 或超限。 */
    suspend fun audioList(maxPages: Int = 10): Result<List<PanFile>> = withContext(Dispatchers.IO) {
        val all = linkedMapOf<Long, PanFile>()
        var lastError = ""
        for (page in 1..maxPages) {
            val resp = api.categoryList(category = 2, page = page, num = 100)
            when {
                resp.errno == 0 -> {
                    resp.list.forEach { all[it.fsId] = it }
                    if (resp.list.size < 100) break
                }
                else -> {
                    lastError = api.errnoMessage(resp.errno, resp.showMsg)
                    if (all.isEmpty()) return@withContext Result.Err(lastError, api.isAuthError(resp.errno))
                    else break
                }
            }
        }
        Result.Ok(all.values.sortedBy { it.name.lowercase() })
    }

    // ==================== 回收站 ====================

    suspend fun recycleList(): Result<List<RecycleItem>> = withContext(Dispatchers.IO) {
        val resp = api.recycleList()
        if (resp.errno == 0) Result.Ok(resp.list)
        else Result.Err(api.errnoMessage(resp.errno, resp.showMsg), api.isAuthError(resp.errno))
    }

    suspend fun recycleRestore(ids: List<Long>): Result<Unit> = withContext(Dispatchers.IO) {
        val r = api.recycleRestore(ids)
        if (r.errno == 0) Result.Ok(Unit) else Result.Err(api.errnoMessage(r.errno, r.showMsg), r.errno == 132)
    }

    suspend fun recycleDelete(ids: List<Long>): Result<Unit> = withContext(Dispatchers.IO) {
        val r = api.recycleDelete(ids)
        if (r.errno == 0) Result.Ok(Unit) else Result.Err(api.errnoMessage(r.errno, r.showMsg), r.errno == 132)
    }

    suspend fun recycleClear(): Result<Unit> = withContext(Dispatchers.IO) {
        val r = api.recycleClear()
        if (r.errno == 0) Result.Ok(Unit) else Result.Err(api.errnoMessage(r.errno, r.showMsg), r.errno == 132)
    }

    // ==================== 分享记录 ====================

    suspend fun shareRecords(): Result<List<ShareRecord>> = withContext(Dispatchers.IO) {
        val resp = api.shareRecord()
        if (resp.errno == 0) Result.Ok(resp.list)
        else Result.Err(api.errnoMessage(resp.errno, resp.showMsg), api.isAuthError(resp.errno))
    }

    suspend fun cancelShare(ids: List<Long>): Result<Unit> = withContext(Dispatchers.IO) {
        val r = api.cancelShare(ids)
        if (r.errno == 0) Result.Ok(Unit) else Result.Err(api.errnoMessage(r.errno, r.showMsg), api.isAuthError(r.errno))
    }

    companion object {
        /** "NAME"/"TIME"/"SIZE"/"TYPE" -> SortField；非法值回退 NAME */
        fun parseSort(v: String): com.panclone.app.ui.screen.files.SortField =
            runCatching { com.panclone.app.ui.screen.files.SortField.valueOf(v) }
                .getOrDefault(com.panclone.app.ui.screen.files.SortField.NAME)
    }
}
