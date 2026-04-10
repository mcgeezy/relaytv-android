package pro.relaytv

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    val uploadClient: OkHttpClient = client.newBuilder()
        .callTimeout(20, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.MINUTES)
        .build()

    fun get(url: String): Request = Request.Builder().url(url).get().build()

    fun postJson(url: String, json: String): Request {
        val mt = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mt)
        return Request.Builder().url(url).post(body).build()
    }

    fun postMultipartFile(
        url: String,
        file: File,
        fileFieldName: String = "file",
        fileName: String = file.name,
        mimeType: String? = null,
        title: String? = null,
    ): Request {
        val contentType = mimeType?.toMediaTypeOrNull()
            ?: "application/octet-stream".toMediaType()
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                fileFieldName,
                fileName,
                file.asRequestBody(contentType)
            )

        if (!title.isNullOrBlank()) {
            bodyBuilder.addFormDataPart("title", title)
        }

        return Request.Builder()
            .url(url)
            .post(bodyBuilder.build())
            .build()
    }
}
