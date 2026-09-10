package cn.aitavern.core

import kotlin.test.*
import org.junit.Test
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import kotlinx.coroutines.*

class ApiTest {
    @Test fun modelsAreSorted() = runBlocking {
        val server=MockWebServer(); server.enqueue(MockResponse().setBody("""{"data":[{"id":"z"},{"id":"a"}]}""")); server.start()
        try { assertEquals(listOf("a","z"),OpenAiClient().models(ApiProfile(baseUrl=server.url("/v1").toString()),"key")) }
        finally { server.shutdown() }
    }
    @Test fun embeddingResponseIsSortedByIndex() = runBlocking {
        val server=MockWebServer(); server.enqueue(MockResponse().setBody("""{"data":[{"index":1,"embedding":[0.0,1.0]},{"index":0,"embedding":[1.0,0.0]}]}""")); server.start()
        try {
            val vectors=OpenAiClient().embeddings(ApiProfile(baseUrl=server.url("/v1").toString(),embeddingModel="vec"),"key",listOf("a","b"))
            assertEquals(listOf(1f,0f),vectors.first()); assertEquals("/v1/embeddings",server.takeRequest().path)
        } finally { server.shutdown() }
    }
    @Test fun coroutineCancellationStopsHangingRequest() = runBlocking {
        val server=MockWebServer(); server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)); server.start()
        try {
            val job=launch { OpenAiClient().complete(ApiProfile(baseUrl=server.url("/").toString(),model="x"),"",emptyList()) {} }
            delay(100); withTimeout(2000) { job.cancelAndJoin() }; assertTrue(job.isCancelled)
        } finally { server.shutdown() }
    }
    @Test fun lengthFinishReasonPreservesTextButFails() = runBlocking<Unit> {
        val server=MockWebServer(); server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("""{"choices":[{"message":{"content":"部分"},"finish_reason":"length"}]}""")); server.start()
        try { var text=""; assertFailsWith<ApiException> { OpenAiClient().complete(ApiProfile(baseUrl=server.url("/").toString(),model="x"),"",emptyList()) { text+=it } }; assertEquals("部分",text) }
        finally { server.shutdown() }
    }
    @Test fun readsFragmentedSseAndSendsAuthorization() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\ndata: [DONE]\n\n").throttleBody(7, 1, java.util.concurrent.TimeUnit.MILLISECONDS))
        server.start()
        try {
            var result = ""
            OpenAiClient().complete(ApiProfile(baseUrl=server.url("/v1").toString(), model="test"), "test-secret", listOf(WireMessage("user", "hi"))) { result += it }
            assertEquals("你好", result)
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer test-secret", request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }
    @Test fun nonStreamingResponseWorks() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"content":"完整回复"},"finish_reason":"stop"}]}"""))
        server.start()
        try {
            var result = ""
            OpenAiClient().complete(ApiProfile(baseUrl=server.url("/").toString(), model="test", stream=false), "", emptyList()) { result += it }
            assertEquals("完整回复", result)
        } finally { server.shutdown() }
    }
    @Test fun authFailureDoesNotLeakServerBodyOrKey() = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(401).setBody("test-secret")); server.start()
        try {
            val error = assertFailsWith<ApiException> { OpenAiClient().complete(ApiProfile(baseUrl=server.url("/").toString(), model="test"), "test-secret", emptyList()) {} }
            assertTrue(error.message!!.contains("401")); assertFalse(error.message!!.contains("test-secret"))
        } finally { server.shutdown() }
    }
    @Test fun abruptStreamIsNotMarkedComplete() = runBlocking<Unit> {
        val server = MockWebServer(); server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"部分\"}}]}\n\n")); server.start()
        try { assertFailsWith<ApiException> { OpenAiClient().complete(ApiProfile(baseUrl=server.url("/").toString(), model="x"), "", emptyList()) {} } }
        finally { server.shutdown() }
    }
}
