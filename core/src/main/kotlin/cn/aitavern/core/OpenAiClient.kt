package cn.aitavern.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(message: String): IOException(message)

class OpenAiClient(private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(25,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS).callTimeout(5,TimeUnit.MINUTES).followRedirects(false).build()) {
    private fun url(profile: ApiProfile, suffix: String): HttpUrl {
        val base = profile.baseUrl.trim().trimEnd('/').toHttpUrl()
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "API 地址不能包含用户名、密码、查询参数或锚点" }
        return (base.toString().trimEnd('/') + "/" + suffix).toHttpUrl()
    }
    private suspend fun <T> execute(request: Request, read: (Response, () -> Boolean) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(ApiException("网络连接失败或超时，请检查地址和网络后重试。"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        if (!it.isSuccessful) throw ApiException(when(it.code) {
                            401,403 -> "鉴权失败（${it.code}），请检查密钥和权限。"
                            429 -> "请求过于频繁或额度不足（429），请稍后重试。"
                            else -> "API 请求失败（${it.code}），请检查服务配置。"
                        })
                        read(it) { continuation.isActive }
                    }
                    if (continuation.isActive) continuation.resume(value)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(if(e is ApiException) e else ApiException("响应格式不兼容或连接中断，请检查服务后重试。"))
                }
            }
        })
    }
    suspend fun models(profile: ApiProfile, key: String): List<String> {
        val request = Request.Builder().url(url(profile,"models")).header("Authorization","Bearer $key").build()
        return execute(request) { response, _ ->
            TavernJson.parseToJsonElement(response.body!!.string()).jsonObject["data"]!!.jsonArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }.sorted()
        }
    }
    suspend fun embeddings(profile: ApiProfile,key: String,texts: List<String>): List<List<Float>> {
        require(profile.embeddingModel.isNotBlank()) { "请先填写 embedding 模型" }
        val body=buildJsonObject { put("model",profile.embeddingModel); put("input",JsonArray(texts.map(::JsonPrimitive))) }.toString().toRequestBody("application/json".toMediaType())
        val request=Request.Builder().url(url(profile,"embeddings")).header("Authorization","Bearer $key").post(body).build()
        return execute(request) { response,_ ->
            val entries=TavernJson.parseToJsonElement(response.body!!.string()).jsonObject["data"]!!.jsonArray.sortedBy { it.jsonObject["index"]!!.jsonPrimitive.int }
            require(entries.size==texts.size && entries.map { it.jsonObject["index"]!!.jsonPrimitive.int }==texts.indices.toList())
            val vectors=entries.map { e -> e.jsonObject["embedding"]!!.jsonArray.map { it.jsonPrimitive.float } }
            require(vectors.all { it.isNotEmpty() && it.size==vectors.first().size && it.all(Float::isFinite) })
            vectors
        }
    }
    suspend fun complete(profile: ApiProfile, key: String, messages: List<WireMessage>, onDelta: (String) -> Unit) {
        require(profile.model.isNotBlank()) { "请填写模型名称" }
        require(profile.maxOutput > 0 && profile.contextSize > profile.maxOutput) { "上下文长度必须大于最大输出" }
        val body = buildJsonObject {
            put("model",profile.model); put("stream",profile.stream); put("max_tokens",profile.maxOutput); put("temperature",profile.temperature)
            put("messages",TavernJson.encodeToJsonElement(messages))
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url(profile,"chat/completions")).header("Authorization","Bearer $key").post(body).build()
        execute(request) { response, active ->
            val source = response.body ?: throw ApiException("API 返回空响应")
            var received = false
            fun emit(text: String) { if (active() && text.isNotEmpty()) { received=true; onDelta(text) } }
            if (response.header("Content-Type").orEmpty().contains("text/event-stream",true)) {
                var finished = false
                var done = false
                var truncated = false
                val event = StringBuilder()
                fun dispatch() {
                    if (event.isEmpty()) return
                    val payload = event.toString().trim(); event.setLength(0)
                    if (payload == "[DONE]") { finished=true; done=true; return }
                    val obj = TavernJson.parseToJsonElement(payload).jsonObject
                    if (obj["error"] != null) throw ApiException("API 在生成过程中返回错误。")
                    obj["choices"]?.jsonArray?.forEach { choice ->
                        val c = choice.jsonObject
                        emit(c["delta"]?.jsonObject?.get("content")?.takeUnless { it is JsonNull }?.jsonPrimitive?.content.orEmpty())
                        val reason = c["finish_reason"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                        if (reason != null) { finished=true; truncated = reason == "length"; if (reason == "content_filter") throw ApiException("服务端终止了本次回复。") }
                    }
                }
                source.source().let { stream ->
                    while (active()) {
                        val line = stream.readUtf8Line() ?: break
                        if (line.isEmpty()) { dispatch(); if(done) break }
                        else if (line.startsWith("data:")) { if(event.isNotEmpty()) event.append('\n'); event.append(line.substring(5).trimStart()) }
                    }
                    dispatch()
                }
                if (!finished) throw ApiException("回复连接中断，已保留部分内容。")
                if (truncated) throw ApiException("回复达到输出上限，已保留部分内容；请增大输出上限后重试。")
            } else {
                val choice = TavernJson.parseToJsonElement(source.string()).jsonObject["choices"]!!.jsonArray.first().jsonObject
                emit(choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
                if (choice["finish_reason"]?.jsonPrimitive?.content == "length") throw ApiException("回复达到输出上限，已保留部分内容。")
            }
            if (!received && active()) throw ApiException("API 没有返回文本回复。")
        }
    }
}
