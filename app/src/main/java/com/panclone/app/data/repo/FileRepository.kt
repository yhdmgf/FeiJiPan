package com.panclone.app.data.repo

import com.panclone.app.App
import com.panclone.app.data.model.PanFile
import com.panclone.app.data.model.RecycleItem
import com.panclone.app.data.model.ShareRecord
import com.panclone.app.data.model.ShareResponse
import com.panclone.app.data.net.FeiJiPanClient
import com.panclone.app.data.net.FeiJiPanFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件仓库：封装小飞机网盘调用，把结果映射成 UI 通用的 PanFile，
 * 把业务异常翻成中文提示，并把"登录失效"单独标出来。
 *
 * 小飞机只支持「分享浏览/下载」与「个人网盘浏览/下载」，其余百度专属能力
 * （建目录/重命名/移动/删除/建分享/回收站/搜索/分类/视频流）返回不支持。
 */
class FileRepository(private val feiji: FeiJiPanClient) {

    sealed interface Result<out T> {
        data class Ok<T>(val data: T) : Result<T>
        data class Err(val message: String, val authFailed: Boolean = false) : Result<Nothing>
    }

    /** 当前是否个人网盘模式 */
    private val isPersonal: Boolean get() = App.instance.session.feiMode == "PERSONAL"

    /**
     * 列目录。folderId 为空串表示分享根目录（分享模式）或个人网盘根目录。
     * 返回单页结果，由调用方按需翻页。
     */
    suspend fun list(folderId: String, page: Int = 1): Result<List<PanFile>> = withContext(Dispatchers.IO) {
        try {
            val session = App.instance.session
            val data = if (isPersonal) {
                feiji.listPersonal(folderId, page, session.feiToken)
            } else if (folderId.isEmpty()) {
                feiji.listShareRoot(session.feiShareId, session.feiCode, page)
            } else {
                feiji.listShareFolder(session.feiShareId, session.feiCode, folderId, page)
            }
            Result.Ok(data.files.map { it.toPanFile() })
        } catch (e: Exception) {
            Result.Err(e.message ?: "加载失败", isAuthError(e))
        }
    }

    /** 取文件直链（预览/播放用）。fileId 为小飞机文件 id。 */
    suspend fun dlink(fileId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val session = App.instance.session
            val url = if (isPersonal) {
                feiji.resolvePersonalFileUrl(fileId, session.feiUserId, session.feiToken)
            } else {
                feiji.resolveShareFileUrl(
                    session.feiShareId, session.feiCode, fileId,
                    session.feiUserId, session.feiToken,
                )
            }
            if (url.isNotBlank()) Result.Ok(url) else Result.Err("获取直链失败")
        } catch (e: Exception) {
            Result.Err(e.message ?: "获取直链失败", isAuthError(e))
        }
    }

    // ==================== 小飞机不支持的能力：保留签名，统一返回不支持 ====================

    suspend fun search(key: String, dir: String): Result<List<PanFile>> =
        Result.Err("小飞机暂不支持搜索")

    suspend fun createFolder(dir: String, name: String): Result<Unit> =
        Result.Err("小飞机暂不支持新建文件夹")

    suspend fun rename(path: String, newName: String, isDir: Boolean = false): Result<Unit> =
        Result.Err("小飞机暂不支持重命名")

    suspend fun moveOrCopy(paths: List<String>, dest: String, copy: Boolean): Result<Unit> =
        Result.Err("小飞机暂不支持移动或复制")

    suspend fun delete(paths: List<String>): Result<Unit> =
        Result.Err("小飞机暂不支持删除")

    suspend fun share(fsIds: List<Long>, period: Int, pwd: String): Result<ShareResponse> =
        Result.Err("小飞机暂不支持创建分享")

    suspend fun modifyShare(shareId: Long, fsIds: List<Long>, period: Int, pwd: String): Result<ShareResponse> =
        Result.Err("小飞机暂不支持修改分享")

    /** 预览播放：小飞机无 m3u8，直接返回直链，ExoPlayer 流式播放。 */
    suspend fun videoStream(fileId: String, quality: Int): Result<String> = dlink(fileId)

    suspend fun audioList(maxPages: Int = 10): Result<List<PanFile>> =
        Result.Err("小飞机暂不支持音频分类")

    suspend fun recycleList(): Result<List<RecycleItem>> =
        Result.Err("小飞机暂不支持回收站")

    suspend fun recycleRestore(ids: List<Long>): Result<Unit> =
        Result.Err("小飞机暂不支持回收站")

    suspend fun recycleDelete(ids: List<Long>): Result<Unit> =
        Result.Err("小飞机暂不支持回收站")

    suspend fun recycleClear(): Result<Unit> =
        Result.Err("小飞机暂不支持回收站")

    suspend fun shareRecords(): Result<List<ShareRecord>> =
        Result.Err("小飞机暂不支持分享记录")

    suspend fun cancelShare(ids: List<Long>): Result<Unit> =
        Result.Err("小飞机暂不支持分享记录")

    // ==================== 内部：映射与辅助 ====================

    /** 小飞机文件 → UI 通用 PanFile。category=0，类型靠扩展名判断。 */
    private fun FeiJiPanFile.toPanFile(): PanFile {
        val idLong = id.toLongOrNull() ?: 0L
        return PanFile(
            fsId = idLong,
            path = id,          // 用小飞机原始 id 作寻址（面包屑/下载用）
            name = name,
            size = sizeBytes,
            mtime = parseTime(modifiedAt),
            ctime = 0L,
            isDir = if (isDirectory) 1 else 0,
            category = 0,
        )
    }

    /** 解析小飞机时间字符串（秒级时间戳优先，其次常见日期格式），失败返回 0。 */
    private fun parseTime(raw: String): Long {
        if (raw.isBlank()) return 0L
        raw.toLongOrNull()?.let { return it }
        for (fmt in TIME_FORMATS) {
            runCatching {
                val df = java.text.SimpleDateFormat(fmt, java.util.Locale.CHINA)
                df.timeZone = java.util.TimeZone.getTimeZone("GMT+8")
                df.setLenient(false)
                return df.parse(raw)?.time?.div(1000) ?: 0L
            }
        }
        return 0L
    }

    /** 登录过期判断：异常信息含「登录」关键字 */
    private fun isAuthError(e: Exception): Boolean =
        e.message?.contains("登录") == true

    companion object {
        private val TIME_FORMATS = arrayOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy-MM-dd",
            "yyyyMMddHHmmss",
        )

        /** "NAME"/"TIME"/"SIZE"/"TYPE" -> SortField；非法值回退 NAME */
        fun parseSort(v: String): com.panclone.app.ui.screen.files.SortField =
            runCatching { com.panclone.app.ui.screen.files.SortField.valueOf(v) }
                .getOrDefault(com.panclone.app.ui.screen.files.SortField.NAME)
    }
}
