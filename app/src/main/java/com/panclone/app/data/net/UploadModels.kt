package com.panclone.app.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 预创建上传响应（web /api/precreate 同款） */
@Serializable
data class PrecreateResponse(
    @SerialName("errno") val errno: Int = -1,
    @SerialName("uploadid") val uploadId: String = "",
    @SerialName("return_type") val returnType: Int = 1,
    @SerialName("show_msg") val showMsg: String = "",
)

@Serializable
data class UploadSliceResponse(
    @SerialName("errno") val errno: Int = -1,
    @SerialName("md5") val md5: String = "",
    @SerialName("show_msg") val showMsg: String = "",
)
