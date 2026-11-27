package com.panclone.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.panclone.app.data.ExternalUploadManager
import com.panclone.app.data.TransferManager
import com.panclone.app.data.model.TransferTask
import com.panclone.app.data.net.FeiJiPanClient
import com.panclone.app.data.store.SessionStore
import java.io.File

/**
 * 手写服务定位器：项目规模不需要 DI 框架。
 */
class App : Application() {

    lateinit var session: SessionStore
        private set
    lateinit var feiji: FeiJiPanClient
        private set
    lateinit var repo: com.panclone.app.data.repo.FileRepository
        private set
    lateinit var transfers: TransferManager
        private set
    lateinit var externalUploads: ExternalUploadManager
        private set

    /** 共享下载 client：小飞机直链是普通 CDN HTTP，多连接并发用 */
    val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = SessionStore(this)
        feiji = FeiJiPanClient()
        repo = com.panclone.app.data.repo.FileRepository(feiji)
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .diskCache {
                    DiskCache.Builder()
                        .directory(File(context.cacheDir, "coil3_disk_cache"))
                        .maxSizeBytes(50L * 1024 * 1024)
                        .build()
                }
                .build()
        }
        externalUploads = ExternalUploadManager()
        com.panclone.app.player.AudioPlaybackManager.initialize(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.panclone.app.data.CacheStore.trim(this@App)
        }
        transfers = TransferManager(
            context = this,
            session = session,
            threads = { session.downloadThreads },
            downloadClient = downloadClient,
            resolveUrl = ::resolveDownloadUrl,
        )
    }

    /**
     * 根据任务取小飞机直链（下载与断点续传刷新直链共用）。
     * task.remotePath 存的是小飞机 fileId（文件）字符串。
     */
    private suspend fun resolveDownloadUrl(task: TransferTask): String {
        val fileId = task.remotePath
        return if (session.feiMode == "PERSONAL") {
            feiji.resolvePersonalFileUrl(fileId, session.feiUserId, session.feiToken)
        } else {
            feiji.resolveShareFileUrl(
                session.feiShareId, session.feiCode, fileId,
                session.feiUserId, session.feiToken,
            )
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
