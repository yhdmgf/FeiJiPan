package com.panclone.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import kotlinx.coroutines.launch
import com.panclone.app.data.ExternalUploadManager
import com.panclone.app.data.TransferManager
import com.panclone.app.data.net.PanApi
import com.panclone.app.data.store.SessionStore
import java.io.File

/**
 * 手写服务定位器：项目规模不需要 DI 框架。
 */
class App : Application() {

    lateinit var session: SessionStore
        private set
    lateinit var api: PanApi
        private set
    lateinit var transfers: TransferManager
        private set
    lateinit var externalUploads: ExternalUploadManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = SessionStore(this)
        api = PanApi { session.cookie }
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
            api = api,
            session = session,
            threads = { session.downloadThreads },
        )
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
