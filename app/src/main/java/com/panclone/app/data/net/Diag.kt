package com.panclone.app.data.net

import android.content.Context
import android.util.Log
import com.panclone.app.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 网络层全链路实测 v2。
 *
 * 覆盖：
 *  - bdstoken / 列表
 *  - 下载：locatedownload 取直链 → Range 拉字节（带 cookie/不带 cookie/浏览器UA/官方UA）→ 双段并发
 *  - 上传：/api/precreate → pcs/superfile2 → /api/create → 列表确认 → locatedownload 回读校验内容 → 删除清理
 *  - 新建/删除/重命名（含 lib.zip.txt 恢复检查）
 *
 * 结果打 logcat(tag=PanDiag) 并落盘 filesDir/diag.txt。
 */
object Diag {
    private const val TAG = "PanDiag"
    private const val PAN = "https://pan.baidu.com"
    private const val PCS = "https://d.pcs.baidu.com/rest/2.0/pcs/file"
    private const val PCS_UP = "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2"
    private const val UA_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private const val UA_PCS =
        "netdisk;P2SP;2.2.91.74;netdisk;12.1.3;android-android;12;JSbridge4.4.0;jointBridge;1.1.0;"

    private val sb = StringBuilder()

    private fun log(s: String) {
        Log.i(TAG, s)
        sb.append(s).append('\n')
    }

    fun run(ctx: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cookie = App.instance.session.cookie
                log("=== cookie len=${cookie.length} BDUSS=${cookie.contains("BDUSS")} ===")
                val token = bdstoken(cookie)
                log("1) bdstoken=$token")
                restoreLibZip(cookie, token)

                val dev = devId()

                // ================= 下载链路 =================
                val dl = locatedownloadByPath("/lib.zip", dev, cookie)
                val dlUrl = Regex("\"url\":\"([^\"]+)\"").find(dl)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                log("2) locatedownload => ${if (dlUrl.isNotBlank()) "拿到直链 ${dlUrl.take(90)}..." else "失败: ${dl.take(200)}"}")

                if (dlUrl.isNotBlank()) {
                    val a = rangeGet(dlUrl, cookie, UA_PCS, 0, 65535)
                    log("3) 下载 netdiskUA+cookie => code=${a.first} bytes=${a.second}")
                    val b = rangeGet(dlUrl, cookie, UA_WEB, 0, 65535)
                    log("4) 下载 browserUA+cookie => code=${b.first} bytes=${b.second}")
                    val c = rangeGet(dlUrl, "", UA_PCS, 0, 65535)
                    log("5) 下载 netdiskUA无cookie => code=${c.first} bytes=${c.second}")

                    // 双段并发
                    var r1 = Pair(0, 0)
                    var r2 = Pair(0, 0)
                    val t1 = Thread { r1 = rangeGet(dlUrl, cookie, UA_PCS, 0, 65535) }
                    val t2 = Thread { r2 = rangeGet(dlUrl, cookie, UA_PCS, 65536, 131071) }
                    t1.start(); t2.start(); t1.join(); t2.join()
                    log("6) 双段并发 => seg1 code=${r1.first} bytes=${r1.second} | seg2 code=${r2.first} bytes=${r2.second}")
                }

                // ================= 上传链路 =================
                uploadRoundtrip(token, cookie, dev)

                // ================= 新建/删除回归 =================
                val t2 = "/_diag2_${System.currentTimeMillis() / 1000}"
                val cr = createFolder(t2, token, cookie)
                log("7) createFolder => errno0=${errno0(cr)}")
                val del = delete(listOf(t2), token, cookie)
                log("8) delete => errno0=${errno0(del)}")

                // ================= 删除 errno=8001 排查 =================
                deleteProbe(token, cookie, dev)

                // ================= 并发分块下载 =================
                concurrencyProbe(dev, cookie)

            } catch (t: Throwable) {
                log("EX: ${t.message}")
            }
            flush(ctx)
        }
    }

    /** 上传 roundtrip：precreate → superfile2 → create → 列表确认 → 回读校验 → 删除 */
    private fun quoteJson(v: String): String = "\"" + v + "\""

    private fun uploadRoundtrip(token: String, cookie: String, dev: String) {
        val size = 512 * 1024
        val data = ByteArray(size) { ((it * 31 + 7) % 251).toByte() }
        val md5 = md5hex(data)
        val path = "/_diag_upload_${System.currentTimeMillis()}.txt"

        // 1) 预创建（web 接口）
        val pre = post(
            "$PAN/api/precreate?app_id=250528&channel=chunlei&clienttype=0&web=1",
            "path=${enc(path)}&size=$size&autoinit=1&rtype=2&block_list=" +
                java.net.URLEncoder.encode("[" + quoteJson(md5) + "]", "UTF-8"),
            cookie, UA_PCS,
        )
        log("u1) precreate => ${pre.take(260)}")
        val preErr = runCatching { org.json.JSONObject(pre).optInt("errno", -1) }.getOrDefault(-1)
        if (preErr != 0) { log("u1) 预创建失败 errno=$preErr"); return }
        val uploadid = Regex("\"uploadid\":\"([^\"]+)\"").find(pre)?.groupValues?.get(1).orEmpty()
        val rapid = Regex("\"return_type\":(\\d+)").find(pre)?.groupValues?.get(1) == "2"
        if (rapid) { log("u1) 秒传命中，无需分片"); }

        // 2) 分片上传（单片）
        if (!rapid) {
            val upUrl = "$PCS_UP?method=upload&app_id=250528&type=tmpfile&partseq=0" +
                "&uploadid=${enc(uploadid)}&path=${enc(path)}"
            val up = multipart(upUrl, data, cookie, UA_PCS)
            log("u2) superfile2 => ${up.take(260)}")
        }

        // 3) 创建文件
        val cr = post(
            "$PAN/api/create?app_id=250528&channel=chunlei&clienttype=0&web=1&bdstoken=${enc(token)}",
            "path=${enc(path)}&size=$size&uploadid=${enc(uploadid)}&block_list=" +
                java.net.URLEncoder.encode("[" + quoteJson(md5) + "]", "UTF-8") + "&rtype=2",
            cookie, UA_PCS,
        )
        log("u3) create => ${cr.take(260)}")
        if (!errno0(cr)) { log("u3) 创建失败"); return }

        // 4) 列表确认（rtype=2 重名会自动改名，所以按前缀找）
        val lt = get("$PAN/api/list?dir=%2F&order=time&desc=1&num=100&page=1&web=1&clienttype=0&app_id=250528", cookie)
        var actualPath = ""
        runCatching {
            val arr = org.json.JSONObject(lt).optJSONArray("list")
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optString("path", "")
                if (p.startsWith("/_diag_upload_")) { actualPath = p; break }
            }
        }
        log("u4) 列表确认 => ${actualPath.ifBlank { "未找到!" }}")
        if (actualPath.isBlank()) return

        // 5) 回读校验：locatedownload → Range 拉 32 字节 → 与上传内容比对
        val dl = locatedownloadByPath(actualPath, dev, cookie)
        val upLink = Regex("\"url\":\"([^\"]+)\"").find(dl)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
        if (upLink.isBlank()) { log("u5) 回读取链失败: ${dl.take(160)}"); cleanup(listOf(actualPath), token, cookie); return }
        val rb = rangeGetBytes(upLink, cookie, UA_PCS, 0, 31)
        val body = rb.second
        val match = body != null && body.size == 32 &&
            body.toList() == data.copyOfRange(0, 32).toList()
        log("u5) 回读校验 => code=${rb.first} bytes=${body?.size ?: 0} 内容一致=$match")

        // 6) 清理
        cleanup(listOf(actualPath), token, cookie)
    }

    private fun cleanup(paths: List<String>, token: String, cookie: String) {
        val del = delete(paths, token, cookie)
        log("u6) delete => errno0=${errno0(del)}")
    }

    /** 恢复：上次诊断可能把 /lib.zip 留成了 .txt */
    private fun restoreLibZip(cookie: String, token: String) {
        runCatching {
            val lt = get("$PAN/api/list?dir=%2F&order=name&desc=0&num=100&page=1&web=1&clienttype=0&app_id=250528", cookie)
            val arr = org.json.JSONObject(lt).optJSONArray("list")
            var hasTxt = false; var hasZip = false
            if (arr != null) for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i)?.optString("server_filename").orEmpty()
                if (n == "lib.zip.txt") hasTxt = true
                if (n == "lib.zip") hasZip = true
            }
            if (hasTxt && !hasZip) {
                val rn = rename("/lib.zip.txt", "lib.zip", token, cookie)
                log("0) RESTORE /lib.zip.txt => errno0=${errno0(rn)}")
            } else log("0) restore check: hasTxt=$hasTxt hasZip=$hasZip（无需恢复）")
        }
    }

    /** 删除 errno=8001 排查：嵌套文件/空目录/非空目录/多选/async=2/官方UA，记录原始 errno */
    private fun deleteProbe(token: String, cookie: String, dev: String) {
        val size = 64 * 1024
        val data = ByteArray(size) { ((it * 17 + 3) % 251).toByte() }
        val md5 = md5hex(data)

        fun seed(path: String): Boolean {
            val pre = post(
                "$PAN/api/precreate?app_id=250528&channel=chunlei&clienttype=0&web=1",
                "path=${enc(path)}&size=$size&autoinit=1&rtype=2&block_list=" +
                    java.net.URLEncoder.encode("[" + quoteJson(md5) + "]", "UTF-8"),
                cookie, UA_PCS,
            )
            val upid = Regex("\"uploadid\":\"([^\"]+)\"").find(pre)?.groupValues?.get(1).orEmpty()
            if (upid.isBlank()) return false
            multipart(
                "$PCS_UP?method=upload&app_id=250528&type=tmpfile&partseq=0&uploadid=${enc(upid)}&path=${enc(path)}",
                data, cookie, UA_PCS,
            )
            val cr = post(
                "$PAN/api/create?app_id=250528&channel=chunlei&clienttype=0&web=1&bdstoken=${enc(token)}",
                "path=${enc(path)}&size=$size&uploadid=${enc(upid)}&block_list=" +
                    enc("[" + quoteJson(md5) + "]") + "&rtype=2",
                cookie, UA_PCS,
            )
            return errno0(cr)
        }

        val ts = System.currentTimeMillis() / 1000
        val root = "/_diag3_$ts"
        createFolder(root, token, cookie)
        createFolder("$root/sub", token, cookie)
        log("d0) seed f.txt=${seed("$root/sub/f.txt")} g.txt=${seed("$root/g.txt")}")

        // d1 嵌套文件（webUA，async=1）
        log("d1) 删嵌套文件(async1,webUA) => ${delete(listOf("$root/sub/f.txt"), token, cookie).take(120)}")
        // d2 空目录
        log("d2) 删空目录(async1,webUA) => ${delete(listOf("$root/sub"), token, cookie).take(120)}")
        // d3 非空目录
        log("d3) 删非空目录(async1,webUA) => ${delete(listOf(root), token, cookie).take(120)}")

        // d4 多选 2 项 + 官方UA
        createFolder("$root/a", token, cookie)
        createFolder("$root/b", token, cookie)
        log("d4) 多选2目录(async1,webUA) => ${delete(listOf("$root/a", "$root/b"), token, cookie).take(120)}")
        log("d4b) 多选2目录(async1,官方UA) => ${deleteUA(listOf("$root/a", "$root/b"), token, cookie).take(120)}")
        // d5 async=2
        createFolder("$root/c", token, cookie)
        log("d5) 删目录(async2,webUA) => ${deleteAsync2(listOf("$root/c"), token, cookie).take(120)}")

        // 清理
        log("d6) 清理root => ${delete(listOf(root), token, cookie).take(120)}")
    }

    private fun deleteUA(paths: List<String>, token: String, cookie: String): String {
        val u = "$PAN/api/filemanager?opera=delete&async=1&onnest=fail&channel=chunlei&web=1&app_id=250528&clienttype=0&bdstoken=${enc(token)}"
        val fl = org.json.JSONArray()
        paths.forEach { fl.put(it) }
        return post(u, "filelist=${enc(fl.toString())}", cookie, UA_PCS)
    }

    private fun deleteAsync2(paths: List<String>, token: String, cookie: String): String {
        val u = "$PAN/api/filemanager?opera=delete&async=2&onnest=fail&channel=chunlei&web=1&app_id=250528&clienttype=0&bdstoken=${enc(token)}"
        val fl = org.json.JSONArray()
        paths.forEach { fl.put(it) }
        return post(u, "filelist=${enc(fl.toString())}", cookie, UA_WEB)
    }

    /** 并发分块下载：128 线程 vs 4 线程，各拉 128 个 64KB 块（共 8MB），统计 HTTP 码与耗时 */
    private fun concurrencyProbe(dev: String, cookie: String) {
        val dl = locatedownloadByPath("/lib.zip", dev, cookie)
        val url = Regex("\"url\":\"([^\"]+)\"").find(dl)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
        if (url.isBlank()) { log("c0) 取链失败: ${dl.take(160)}"); return }

        val chunk = 64L * 1024
        fun run(nThreads: Int, nChunks: Int): Pair<Long, Map<Int, Int>> {
            val codes = java.util.concurrent.ConcurrentHashMap<Int, Int>()
            val t0 = System.currentTimeMillis()
            val per = nChunks / nThreads
            val ts = (0 until nThreads).map { t ->
                Thread {
                    for (j in 0 until per) {
                        val off = t.toLong() * per * chunk + j * chunk
                        val r = rangeGet(url, "", UA_PCS, off, off + chunk - 1)
                        codes.merge(r.first, 1, Int::plus)
                    }
                }
            }
            ts.forEach { it.start() }
            ts.forEach { it.join() }
            return (System.currentTimeMillis() - t0) to codes.toSortedMap()
        }
        val (ms128, c128) = run(128, 128)
        log("c1) 128线程×64KB×128块 => ${ms128}ms codes=$c128")
        val (ms4, c4) = run(4, 128)
        log("c2) 4线程×64KB×128块 => ${ms4}ms codes=$c4")
    }

    // ==================== 接口封装 ====================

    private fun bdstoken(cookie: String): String {
        val t = get("$PAN/api/gettemplatevariable?fields=%5B%22bdstoken%22%5D&clienttype=0&app_id=250528&web=1", cookie, UA_WEB)
        return Regex("\"bdstoken\":\"([^\"]+)\"").find(t)?.groupValues?.get(1).orEmpty()
    }

    private fun createFolder(path: String, token: String, cookie: String): String {
        val u = "$PAN/api/create?isdir=1&rtype=1&channel=chunlei&web=1&app_id=250528&clienttype=0&bdstoken=${enc(token)}"
        val body = "path=${enc(path)}&isdir=1&block_list=%5B%5D&name=${enc(path.substringAfterLast('/'))}"
        return post(u, body, cookie, UA_WEB)
    }

    private fun delete(paths: List<String>, token: String, cookie: String): String {
        val u = "$PAN/api/filemanager?opera=delete&async=1&onnest=fail&channel=chunlei&web=1&app_id=250528&clienttype=0&bdstoken=${enc(token)}"
        val fl = org.json.JSONArray()
        paths.forEach { fl.put(it) }
        return post(u, "filelist=${enc(fl.toString())}", cookie, UA_WEB)
    }

    private fun rename(path: String, newName: String, token: String, cookie: String): String {
        val u = "$PAN/api/filemanager?opera=rename&async=2&onnest=fail&channel=chunlei&web=1&app_id=250528&clienttype=0&bdstoken=${enc(token)}"
        val fl = org.json.JSONArray().put(
            org.json.JSONObject().put("path", path).put("newname", newName).put("isdir", 0)
        )
        val r = post(u, "filelist=${enc(fl.toString())}", cookie, UA_WEB)
        Log.i(TAG, "rename raw=${r.take(160)}")
        return r
    }

    /** 预览通道取链：只给 path + use=1 */
    private fun locatedownloadByPath(path: String, dev: String, cookie: String): String {
        val rand = (1..40).map { "0123456789abcdef".random() }.joinToString("")
        val url = "$PCS?app_id=250528&method=locatedownload&check_blue=1&es=1&esl=1" +
            "&path=${enc(path)}" +
            "&ver=4.0&dtype=1&err_ver=1.0&ehps=1&eck=1&vip=0&clienttype=17" +
            "&version=2.2.91.74&time=${System.currentTimeMillis() / 1000}&rand=$rand" +
            "&devuid=${enc(dev)}&channel=0&version_app=12.1.3&apn_id=0_-1" +
            "&freeisp=0&queryfree=0&cuid=${enc(dev)}&use=1"
        return postPcs(url, cookie)
    }

    // ==================== HTTP ====================

    private fun openBase(url: String, cookie: String, ua: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            setRequestProperty("Referer", "$PAN/disk/home")
        }

    private fun get(url: String, cookie: String, ua: String = UA_WEB): String = runCatching {
        val c = openBase(url, cookie, ua)
        val s = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        s?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
    }.getOrElse { "EX: ${it.message}" }

    private fun post(url: String, body: String, cookie: String, ua: String): String = runCatching {
        val c = openBase(url, cookie, ua).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        val s = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        s?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
    }.getOrElse { "EX: ${it.message}" }

    /** PCS 接口：body 必须是一个空格（照抓包） */
    private fun postPcs(url: String, cookie: String): String = runCatching {
        val c = openBase(url, cookie, UA_PCS).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        c.outputStream.use { it.write(" ".toByteArray()) }
        val s = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        s?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
    }.getOrElse { "EX: ${it.message}" }

    private fun rangeGet(url: String, cookie: String, ua: String, start: Long, end: Long): Pair<Int, Int> {
        val r = rangeGetBytes(url, cookie, ua, start, end)
        return r.first to (r.second?.size ?: 0)
    }

    private fun rangeGetBytes(url: String, cookie: String, ua: String, start: Long, end: Long): Pair<Int, ByteArray?> = runCatching {
        val c = openBase(url, cookie, ua)
        c.setRequestProperty("Range", "bytes=$start-$end")
        val code = c.responseCode
        val s = if (code in 200..299) c.inputStream else null
        val buf = s?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(32 * 1024)
            var n: Int
            var total = 0
            val want = (end - start + 1).toInt()
            while (total < want) {
                n = input.read(tmp, 0, minOf(tmp.size, want - total))
                if (n <= 0) break
                out.write(tmp, 0, n)
                total += n
            }
            out.toByteArray()
        }
        code to buf
    }.getOrElse { 0 to null }

    private fun multipart(url: String, data: ByteArray, cookie: String, ua: String): String = runCatching {
        val boundary = "----PanClone${System.currentTimeMillis()}"
        val c = openBase(url, cookie, ua).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        c.outputStream.use { os ->
            os.write("--$boundary\r\n".toByteArray())
            os.write("Content-Disposition: form-data; name=\"file\"; filename=\"blob\"\r\n".toByteArray())
            os.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
            os.write(data)
            os.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val s = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        s?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
    }.getOrElse { "EX: ${it.message}" }

    // ==================== 杂项 ====================

    private fun errno0(body: String): Boolean =
        runCatching { org.json.JSONObject(body).optInt("errno", -1) == 0 }.getOrDefault(false)

    private fun md5hex(b: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5").digest(b).joinToString("") { String.format("%02x", it) }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun devId(): String {
        val prefs = App.instance.getSharedPreferences("device", 0)
        return prefs.getString("devuid", null) ?: run {
            val v = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32).uppercase()
            prefs.edit().putString("devuid", v).apply()
            v
        }
    }

    private fun flush(ctx: Context) {
        runCatching { File(ctx.filesDir, "diag.txt").writeText(sb.toString()) }
        log("=== done ===")
    }
}
