package com.mio.irondome.patrulha

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/** As consultas do PR seguem as páginas do GitHub, em vez de parar nas 100 primeiras conversas. */
class GitHubClientPagingTest {

    private lateinit var server: HttpServer
    private val cursors = mutableListOf<String?>()
    private val restPages = mutableListOf<String>()

    private fun thread(id: Int) =
        """{"id": "T$id", "path": "src/A.kt", "line": $id, "isResolved": false, "isOutdated": false,
            "comments": {"nodes": [{"databaseId": $id, "body": "c$id", "createdAt": "2026-11-01T10:00:00Z", "author": {"login": "ana"}, "state": "SUBMITTED"}]}}"""

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            val variables = JsonParser.parseString(exchange.requestBody.readBytes().decodeToString()).asJsonObject.getAsJsonObject("variables")
            val after = variables.get("after")?.asString
            cursors += after
            // Duas páginas: a primeira diz que há mais e dá o cursor; a segunda fecha.
            val (nodes, info) = if (after == null) "${thread(1)}, ${thread(2)}" to """{"hasNextPage": true, "endCursor": "c2"}"""
            else thread(3) to """{"hasNextPage": false, "endCursor": null}"""
            val body = """{"data": {"repository": {"pullRequest": {
                "reviewThreads": {"pageInfo": $info, "nodes": [$nodes]},
                "comments": {"nodes": []}, "reviews": {"nodes": []}}}}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/repos") { exchange ->
            val query = exchange.requestURI.query.orEmpty()
            restPages += query
            val page = Regex("""(?:^|&)page=(\d+)""").find(query)?.groupValues?.get(1)?.toInt() ?: 1
            val count = if (page == 1) 100 else 3
            val body = (1..count).joinToString(",", "[", "]") { """{"id": ${page * 1000 + it}, "path": "src/A.kt", "body": "x"}""" }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private fun client(): GitHubClient {
        val base = "http://127.0.0.1:${server.address.port}"
        return GitHubClient(base, "$base/graphql", "token-de-teste")
    }

    private val repo = GitHubRepo("github.com", "mio", "viagem")

    @Test
    fun theDiscussionsOfAllPagesComeTogether() {
        val discussions = client().discussions(repo, 7)

        assertEquals(listOf("c1", "c2", "c3"), discussions.map { it.comments.first().body })
        assertEquals(2, cursors.size)
        assertNull("a primeira página não leva cursor", cursors[0])
        assertEquals("c2", cursors[1])
    }

    @Test
    fun theThreadsUsedToVerifyTheRequestsAlsoFollowThePages() {
        assertEquals(listOf(1L, 2L, 3L), client().reviewThreads(repo, 7).map { it.firstCommentId })
    }

    @Test
    fun theCommentsOfADraftReviewWithMoreThanAHundredAreAllRead() {
        assertEquals(103, client().reviewComments(repo, 7, 55).size)
        assertEquals(2, restPages.size)
    }
}
