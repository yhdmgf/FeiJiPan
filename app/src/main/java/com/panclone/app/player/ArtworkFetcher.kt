package com.panclone.app.player

import com.panclone.app.App
import com.panclone.app.data.net.FeiJiPanClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

/**
 * 只拉音频文件头部 Range 字节，本地解析内嵌专辑封面（ID3v2 APIC / FLAC PICTURE）。
 * 比整首下载省一个数量级流量；解析失败返回 null。
 */
object ArtworkFetcher {

    private val client = OkHttpClient()

    const val FIRST_RANGE_END = 262143L   // 前 256KB，绝大多数封面都在这段里
    const val RETRY_RANGE_END = 524287L   // 512KB 兜底（ID3 标签超大时）

    /**
     * 返回 (封面字节, ID3 标签总长或 null)。
     * 封面为 null 且 tagSize 超出已拉长度时，说明封面帧在更后面，值得加大 Range 重试。
     */
    suspend fun fetchCover(url: String, rangeEnd: Long = FIRST_RANGE_END): Pair<ByteArray?, Int?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-$rangeEnd")
                    .header("User-Agent", FeiJiPanClient.DESKTOP_UA)
                    .build()
                client.newCall(req).execute().use { resp ->
                    val input = resp.body?.byteStream() ?: return@use null to null
                    val buf = ByteArrayOutputStream()
                    val tmp = ByteArray(8192)
                    while (buf.size() <= rangeEnd) {
                        val n = input.read(tmp)
                        if (n <= 0) break
                        buf.write(tmp, 0, n)
                    }
                    parse(buf.toByteArray())
                }
            }.getOrNull() ?: (null to null)
        }

    fun parse(d: ByteArray): Pair<ByteArray?, Int?> {
        val id3 = parseId3(d)
        if (id3 != null) return id3
        return parseFlac(d) to null
    }

    /** ID3v2 APIC 帧解析；返回 (封面, 标签总长)。非 ID3 文件返回 null。 */
    private fun parseId3(d: ByteArray): Pair<ByteArray?, Int?>? {
        if (d.size < 10) return null
        if (d[0] != 'I'.code.toByte() || d[1] != 'D'.code.toByte() || d[2] != '3'.code.toByte()) return null
        val major = d[3].toInt() and 0xFF
        val flags = d[5].toInt() and 0xFF
        val tagSize = synchsafe(d, 6)
        if (tagSize <= 0) return null to null
        if (flags and 0x40 != 0) return null to tagSize // 带扩展头：不解析，交给大 Range 重试
        var pos = 10
        val end = minOf(d.size, 10 + tagSize)
        while (pos + 10 <= end) {
            val id = String(d, pos, 4, Charsets.ISO_8859_1)
            if (id[0] !in 'A'..'Z') break
            val size = if (major >= 4) synchsafe(d, pos + 4) else be32(d, pos + 4)
            if (size <= 0) break
            if (id == "APIC") {
                val frameEnd = pos + 10 + size
                if (frameEnd > d.size) return null to tagSize // 帧被 Range 截断
                var p = pos + 10
                val enc = d[p].toInt() and 0xFF; p++
                while (p < frameEnd && d[p] != 0.toByte()) p++ // mime
                p++
                p++ // picture type
                if (enc == 1 || enc == 2) { // utf16 描述：双零结尾
                    while (p + 1 < frameEnd && !(d[p] == 0.toByte() && d[p + 1] == 0.toByte())) p += 2
                    p += 2
                } else {
                    while (p < frameEnd && d[p] != 0.toByte()) p++
                    p++
                }
                if (p <= frameEnd && frameEnd - p > 0) {
                    return d.copyOfRange(p, frameEnd) to tagSize
                }
                return null to tagSize
            }
            pos += 10 + size
        }
        return null to tagSize
    }

    private fun synchsafe(d: ByteArray, off: Int): Int =
        ((d[off].toInt() and 0x7F) shl 21) or ((d[off + 1].toInt() and 0x7F) shl 14) or
            ((d[off + 2].toInt() and 0x7F) shl 7) or (d[off + 3].toInt() and 0x7F)

    private fun be32(d: ByteArray, off: Int): Int =
        ((d[off].toInt() and 0xFF) shl 24) or ((d[off + 1].toInt() and 0xFF) shl 16) or
            ((d[off + 2].toInt() and 0xFF) shl 8) or (d[off + 3].toInt() and 0xFF)

    /** FLAC PICTURE 块（type 6）解析。非 FLAC 或无封面返回 null。 */
    private fun parseFlac(d: ByteArray): ByteArray? {
        if (d.size < 8 || d[0] != 'f'.code.toByte() || d[1] != 'L'.code.toByte() ||
            d[2] != 'a'.code.toByte() || d[3] != 'C'.code.toByte()
        ) return null
        var pos = 4
        while (pos + 4 <= d.size) {
            val header = d[pos].toInt() and 0xFF
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val len = ((d[pos + 1].toInt() and 0xFF) shl 16) or
                ((d[pos + 2].toInt() and 0xFF) shl 8) or (d[pos + 3].toInt() and 0xFF)
            pos += 4
            if (type == 6 && pos + len <= d.size) {
                var p = pos
                fun i32(): Int { val v = be32(d, p); p += 4; return v }
                i32() // picture type
                val mimeLen = i32(); p += mimeLen
                val descLen = i32(); p += descLen
                p += 16 // w/h/depth/colors
                if (p + 4 <= pos + len) {
                    val dataLen = i32()
                    if (dataLen in 1..(pos + len - p)) return d.copyOfRange(p, p + dataLen)
                }
                return null
            }
            pos += len
            if (last) break
        }
        return null
    }
}
