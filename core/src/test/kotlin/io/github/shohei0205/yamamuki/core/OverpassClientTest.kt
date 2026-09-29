package io.github.shohei0205.yamamuki.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OverpassClientTest {
    private val box = BoundingBox(35.0, 138.0, 36.0, 139.0)
    private val okBody = "@id\t@lat\t@lon\tname\tname:ja\tele\n1\t35.36\t138.73\t富士山\t\t3776\n"

    @Test
    fun postsQueryAndParses() = runTest {
        var sentBody = ""
        val engine = MockEngine { request ->
            sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond(okBody)
        }
        val client = OverpassClient(HttpClient(engine), endpoints = listOf("https://a.example/api/interpreter"))

        val result = client.fetchPeaks(box)

        assertEquals(listOf(Mountain(1, "富士山", 35.36, 138.73, 3776.0)), result)
        assertTrue(sentBody.startsWith("data="))
    }

    @Test
    fun fallsBackToNextEndpoint() = runTest {
        val hosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            hosts += request.url.host
            if (request.url.host == "a.example") respondError(HttpStatusCode.TooManyRequests) else respond(okBody)
        }
        val client = OverpassClient(
            HttpClient(engine),
            endpoints = listOf("https://a.example/api/interpreter", "https://b.example/api/interpreter"),
        )

        assertEquals(1, client.fetchPeaks(box).size)
        assertEquals(listOf("a.example", "b.example"), hosts)
    }

    @Test
    fun throwsWhenAllEndpointsFail() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.GatewayTimeout) }
        val client = OverpassClient(HttpClient(engine), endpoints = listOf("https://a.example/api/interpreter"))

        val e = assertFailsWith<OverpassException> { client.fetchPeaks(box) }
        assertEquals(504, e.httpStatus, "取り直しの待ち時間を決められるよう、HTTP ステータスを伝える")
    }

    @Test
    fun usesOnlyMainInstanceByDefault() {
        assertEquals(listOf("https://overpass-api.de/api/interpreter"), OverpassClient.DEFAULT_ENDPOINTS)
    }
}
