package com.fwdekker.mediawikipurger

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import mu.KotlinLogging
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse


/**
 * Interacts with a MediaWiki wiki through its API.
 *
 * @property apiUrl the URL to the `api.php` to connect to
 * @property client the client to send requests through
 */
class Wiki(private val apiUrl: String, private val client: ThrottledHttpClient) {
    companion object {
        /**
         * Number of milliseconds to wait when the rate limiting timeout has been reached.
         */
        private const val RATE_LIMIT_TIMEOUT = 5000L
    }


    private val logger = KotlinLogging.logger {}


    /**
     * Parses JSON responses from the server.
     */
    private val parser = Parser.default()

    /**
     * Parameters that are added to each request.
     */
    private val defaultParams = mutableMapOf("format" to "json", "formatversion" to "2")


    /**
     * Sends a request to this wiki.
     *
     * @param method the HTTP method the request should have. Must be "GET" or "POST"
     * @param action the API action to perform, such as "query" or "purge"
     * @param params the parameters to give as part of the request
     * @return the API's response as a parsed JSON object
     */
    fun request(method: String, action: String, params: Map<String, String>): JsonObject {
        val allParams = (defaultParams + Pair("action", action) + params)
            .mapKeys { URLEncoder.encode(it.key, "UTF-8") }
            .mapValues { URLEncoder.encode(it.value, "UTF-8") }
        val uri = URI.create("$apiUrl?${allParams.map { "${it.key}=${it.value}" }.joinToString("&")}&*")
        logger.debug { "Sending request to `$uri`." }

        while (true) {
            val request = HttpRequest.newBuilder()
                .method(method, HttpRequest.BodyPublishers.noBody())
                .uri(uri)
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            logger.debug { "Received `${response.body()}`." }

            val json = parser.parse(StringBuilder(response.body())) as JsonObject
            val actionWarnings = json.obj("warnings")?.obj(action)?.string("warnings")
            if (actionWarnings !== null && actionWarnings.contains("rate limit")) {
                logger.warn { "Rate limit has been reached. Waiting 5 s until next attempt." }
                Thread.sleep(RATE_LIMIT_TIMEOUT)
                continue
            }

            return json
        }
    }

    /**
     * Traverses the API using the specified generator, synchronously passing the result to the given callback.
     *
     * @param method the HTTP method the requests should have. Must be "GET" or "POST"
     * @param action the API action to perform, such as "query" or "purge"
     * @param generator the generator to use for traversal
     * @param startFrom the page to start traversing at, or the empty string to start at the beginning
     * @param params the parameters to give as part of each request, possibly including generator parameters
     * @param callback invoked each time a traversal is made, with the API's response and the title of the next page to
     * be requested
     */
    fun traverse(
        method: String,
        action: String,
        generator: String,
        startFrom: String = "",
        params: Map<String, String>,
        callback: (JsonObject, String?) -> Unit
    ) {
        var gapFrom: String? = startFrom
        while (gapFrom !== null)
            request(method, action, params + mapOf("generator" to generator, "gapfrom" to gapFrom))
                .also { gapFrom = it.obj("continue")?.string("gapcontinue") }
                .also { callback(it, gapFrom) }
    }
}
