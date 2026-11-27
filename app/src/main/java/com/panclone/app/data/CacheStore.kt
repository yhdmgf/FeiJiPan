package com.panclone.app.data

import android.content.Context
import java.io.File

/**
 * 应用缓存统一入口（cacheDir）。
 *
 * 只放可重建的数据：音频索引、专辑封面、上传中转。
 * 总量上限 80MB；超额时按“最久未修改”依次覆盖删除旧文件，保留正在写入的项。
 */
object CacheStore {
    private const val LIMIT_BYTES = 180L * 1024 * 1024

    fun dir(context: Context, name: String): File =
        File(context.cacheDir, name).apply { mkdirs() }

    /** 音频索引文件：随缓存目录，可随时重建。 */
    fun musicIndex(context: Context): File = File(context.cacheDir, "music_index.txt")

    /** 专辑封面：文件名带 fsId，封面变化会生成新条目。 */
    fun artworkFile(context: Context, fsId: Long): File =
        File(dir(context, "audio_artwork"), "$fsId.img")

    /** 当前播放窗口的直链缓存：每行 fsId\t直链，滚动更新，进程重启后可直接复用。 */
    fun audioUrlCache(context: Context): File = File(dir(context, "audio"), "window_urls.txt")

    /** 上传中转目录（仅用于必须落盘的场景，正常情况下传原文件）。 */
    fun uploadTmp(context: Context): File = dir(context, "upload_tmp")

    /** 把总量压到上限以内；按最后修改时间从旧到新删除。 */
    fun trim(context: Context) {
        val cache = context.cacheDir ?: return
        val files = cache.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .toList()
        var total = files.sumOf { it.length() }
        if (total <= LIMIT_BYTES) return
        for (file in files) {
            if (total <= LIMIT_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }
}
