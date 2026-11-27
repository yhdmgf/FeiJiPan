package com.panclone.app.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object StringIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StringInt", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Int {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())
        return element.toString().trim('"').toIntOrNull() ?: -1
    }
}

@Serializable
data class PanFile(
    @SerialName("fs_id") val fsId: Long = 0,
    @SerialName("path") val path: String = "",
    @SerialName("server_filename") val name: String = "",
    @SerialName("size") val size: Long = 0,
    @SerialName("server_mtime") val mtime: Long = 0,
    @SerialName("server_ctime") val ctime: Long = 0,
    @SerialName("isdir") val isDir: Int = 0,
    @SerialName("md5") val md5: String = "",
    @SerialName("category") val category: Int = 0,
    @SerialName("thumbs") val thumbs: Thumbs? = null,
) {
    val isDirectory: Boolean get() = isDir == 1
}

@Serializable
data class Thumbs(
    @SerialName("url1") val url1: String = "",
    @SerialName("url2") val url2: String = "",
    @SerialName("url3") val url3: String = "",
)

@Serializable
data class FileListResponse(
    @SerialName("errno") val errno: Int = -1,
    @SerialName("list") val list: List<PanFile> = emptyList(),
    @SerialName("guid") val guid: Int = 0,
    @SerialName("request_id") val requestId: Long = 0,
    @SerialName("show_msg") val showMsg: String = "",
)

@Serializable
data class QuotaResponse(
    @SerialName("errno") val errno: Int = -1,
    @SerialName("total") val total: Long = 0,
    @SerialName("used") val used: Long = 0,
    @SerialName("request_id") val requestId: Long = 0,
    @SerialName("show_msg") val showMsg: String = "",
)

@Serializable
data class UserInfoResponse(
    @SerialName("errno") val errno: Int = -1,
    @SerialName("baidu_name") val baiduName: String = "",
    @SerialName("netdisk_name") val netdiskName: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("vip_type") val vipType: Int = 0,
    @SerialName("uk") val uk: Long = 0,
    @SerialName("show_msg") val showMsg: String = "",
)

@Serializable
data class ShareResponse(
    @SerialName("errno") val errno: Int = -1,
    @SerialName("link") val link: String = "",
    @SerialName("shorturl") val shortUrl: String = "",
    @SerialName("pwd") val pwd: String = "",
    @SerialName("shareid") val shareId: Long = 0,
    @SerialName("qrcodeurl") val qrcodeUrl: String = "",
    @SerialName("show_msg") val showMsg: String = "",
)

@Serializable
data class VipProduct(
    @SerialName("cluster") val cluster: String = "",
    @SerialName("detail_cluster") val detailCluster: String = "",
    @SerialName("product_type") val productType: String = "",
    @SerialName("start_time") val startTime: Long = 0,
    @SerialName("expired_time") val expiredTime: Long = 0,
)

@Serializable
data class VipQueryResponse(
    @SerialName("current_product_v2") val current: VipProduct? = null,
    @SerialName("previous_product") val previous: VipProduct? = null,
) {
    val vipActive: Boolean
        get() = current != null && current.productType.isNotBlank() &&
            current.expiredTime > System.currentTimeMillis() / 1000
    val isSvip: Boolean get() = vipActive && current?.detailCluster == "svip"
}

@Serializable
data class SimpleResponse(
    @SerialName("errno") val errno: Int = -1,
    @SerialName("show_msg") val showMsg: String = "",
    @SerialName("request_id") val requestId: Long = 0,
    @SerialName("authwidget") val authwidget: VerifyWidget? = null,
    @SerialName("verify_scene") val verifyScene: Int = 0,
    @SerialName("taskid") val taskId: Long = 0,
)

@Serializable
enum class TransferState { PENDING, RUNNING, PAUSED, DONE, FAILED }

@Serializable
enum class TransferKind { DOWNLOAD, UPLOAD }

@Serializable
data class TransferTask(
    val id: String,
    val kind: TransferKind,
    val name: String,
    val remotePath: String,
    val localPath: String,
    val size: Long = 0,
    val thumb: String = "",
    val directUrl: String = "",
    val sourceUri: String = "",
    val done: Long = 0,
    val doneAt: Long = 0,
    val state: TransferState = TransferState.PENDING,
    val error: String = "",
) {
    val progress: Float get() = if (size <= 0) 0f else (done.toFloat() / size).coerceIn(0f, 1f)
}

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class CategoryListResponse(
    @SerialName("errno") val errno: Int = -1,
    @SerialName("info") val list: List<PanFile> = emptyList(),
    @SerialName("show_msg") val showMsg: String = "",
)

@Serializable
data class RecycleItem(
    @SerialName("fs_id") val fsId: Long = 0,
    @SerialName("server_filename") val name: String = "",
    @SerialName("path") val path: String = "",
    @SerialName("size") val size: Long = 0,
    @SerialName("isdir") val isDir: Int = 0,
    @SerialName("category") val category: Int = 0,
    @SerialName("server_ctime") val ctime: Long = 0,
    @SerialName("server_mtime") val mtime: Long = 0,
    @SerialName("leftTime") val leftTime: Int = 0,
    @SerialName("md5") val md5: String = "",
    @SerialName("thumbs") val thumbs: Thumbs? = null,
) {
    val isDirectory: Boolean get() = isDir == 1
}

@Serializable
data class RecycleListResponse(
    @SerialName("errno") val errno: Int = -1,
    @SerialName("list") val list: List<RecycleItem> = emptyList(),
    @SerialName("show_msg") val showMsg: String = "",
)

@Serializable
data class ShareRecord(
    @SerialName("shareId") val shareId: Long = 0,
    @SerialName("shortlink") val shortlink: String = "",
    @SerialName("shorturl") val shorturl: String = "",
    @SerialName("passwd") val passwd: String = "",
    @SerialName("typicalPath") val typicalPath: String = "",
    @SerialName("fsIds") val fsIds: List<Long> = emptyList(),
    @SerialName("ctime") val ctime: Long = 0,
    @SerialName("expiredType") val expiredType: Int = 0,
    @SerialName("expiredTime") val expiredTime: Long = 0,
    @SerialName("lastExpireTime") val lastExpireTime: Long = 0,
    @SerialName("vCnt") val vCnt: Int = 0,
    @SerialName("status") val status: Int = 0,
    @SerialName("typicalCategory") val typicalCategory: Int = 0,
) {
    val fileName: String get() = typicalPath.substringAfterLast('/')
}

@Serializable
data class ShareRecordResponse(
    @SerialName("errno") val errno: Int = -1,
    @SerialName("count") val count: Int = 0,
    @SerialName("list") val list: List<ShareRecord> = emptyList(),
    @SerialName("show_msg") val showMsg: String = "",
)

@Serializable
data class VerifyWidget(
    @SerialName("saferand") val saferand: String = "",
    @SerialName("safesign") val safesign: String = "",
    @SerialName("safetpl") val safetpl: String = "",
)

@Serializable
data class TemplateVariableResponse(
    @SerialName("errno") val errno: Int = -1,
    @SerialName("result") val result: TemplateVariables? = null,
    @SerialName("show_msg") val showMsg: String = "",
)

@Serializable
data class TemplateVariables(
    @SerialName("bdstoken") val bdstoken: String = "",
    @SerialName("token") val token: String = "",
    @SerialName("uk") val uk: Long = 0,
)

@Serializable
data class VerifyApiResponse(
    @Serializable(with = StringIntSerializer::class) @SerialName("errno") val errno: Int = -1,
    @SerialName("msg") val msg: String = "",
    @SerialName("data") val data: VerifyMethods? = null,
    @SerialName("forgot") val forgot: String = "",
    @SerialName("ppAuthName") val ppAuthName: String = "",
) {
    val isSuccess: Boolean get() = errno == 110000 || errno == 0
}

@Serializable
data class VerifyMethods(
    @SerialName("mobile") val mobile: String = "",
    @SerialName("email") val email: String = "",
    @SerialName("original_mobile") val originalMobile: String = "",
)

@Serializable
data class VerifyPublicKey(
    @Serializable(with = StringIntSerializer::class) @SerialName("errno") val errno: Int = -1,
    @SerialName("pubkey") val pubkey: String = "",
    @SerialName("key") val key: String = "",
)

@Serializable
data class VerifyActionResponse(
    @Serializable(with = StringIntSerializer::class) @SerialName("errno") val errno: Int = -1,
    @SerialName("msg") val msg: String = "",
    @SerialName("authsid") val authsid: String = "",
    @SerialName("verifytoken") val verifytoken: String = "",
    @SerialName("channel_id") val channelId: String = "",
) {
    val isSuccess: Boolean get() = errno == 110000 || errno == 0
}
