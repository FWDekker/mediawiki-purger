package com.fwdekker.mediawikipurger

import com.beust.klaxon.JsonObject
import com.beust.klaxon.KlaxonException
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
         * Maximum number of attempts to fulfil a request before giving up.
         */
        private const val MAX_REQUEST_ATTEMPTS = 5

        /**
         * Number of milliseconds to wait before retrying when a request could not be completed for unknown reasons.
         */
        private const val UNEXPECTED_ERROR_TIMEOUT = 5000L

        /**
         * Number of milliseconds to wait before retrying when server sends a malformed reply.
         */
        private const val MALFORMED_REPLY_TIMEOUT = 5000L

        /**
         * Number of milliseconds to wait before retrying when the rate limiting timeout has been reached.
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
     * The username that this wiki is logged in as, or `null` if not logged in.
     */
    private var loggedInAs: String? = null


    /**
     * Retrieves a login token from the API.
     */
    private fun getLoginToken() =
        request("GET", action = "query", params = mapOf("meta" to "tokens", "type" to "login"))
            .obj("query")?.obj("tokens")?.string("logintoken")
            ?: throw IllegalStateException("API did not provide login token.")

    /**
     * Logs in using the given credentials.
     *
     * @param username the username to log in as
     * @param password the bot password to log in with
     */
    fun logIn(username: String, password: String) {
        if (loggedInAs != null) logOut()
        logger.info { "Logging in as `$username`." }

        val token = getLoginToken()

        val response = request(
            "POST",
            action = "login",
            body = mapOf("lgname" to username, "lgpassword" to password, "lgtoken" to token)
        )
        if (response.obj("login")?.string("result") != "Success")
            throw IllegalStateException("Failed to log in.")

        loggedInAs = username
        logger.info { "Successfully logged in as `$username`." }
    }

    /**
     * Logs out of a bot account, if possible.
     */
    fun logOut() {
        logger.info { "Logging out from `${loggedInAs}`." }
        loggedInAs = null

        client.removeCookies()
    }


    /**
     * Sends a request to this wiki.
     *
     * @param method the HTTP method the request should have. Must be "GET" or "POST"
     * @param action the API action to perform, such as "query" or "purge"
     * @param params the parameters to give as part of the request
     * @return the API's response as a parsed JSON object
     */
    fun request(
        method: String,
        action: String,
        params: Map<String, String> = emptyMap(),
        body: Map<String, String> = emptyMap()
    ): JsonObject {
        val allParams = defaultParams + Pair("action", action) + params
        val uri = URI.create("$apiUrl?${allParams.toQueryParamString(urlEncode = true)}&*")
        logger.debug { "Sending request to `$uri`." }

        val bodyPublisher = HttpRequest.BodyPublishers.ofString(body.toQueryParamString(urlEncode = true))

        for (i in 1..MAX_REQUEST_ATTEMPTS) {
            val request = HttpRequest.newBuilder()
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method(method, bodyPublisher)
                .uri(uri)
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
            logger.debug { "Received `${response}`." }

            val json: JsonObject
            try {
                json = parser.parse(StringBuilder(response)) as JsonObject
            } catch (e: KlaxonException) {
                logger.error(e) {
                    "Failed to complete request because server sent malformed reply. Waiting " +
                        "$UNEXPECTED_ERROR_TIMEOUT ms until next attempt."
                }
                Thread.sleep(UNEXPECTED_ERROR_TIMEOUT)
                continue
            }

            // Check warnings
            val actionWarnings = json.obj("warnings")?.obj(action)?.string("warnings")
            if (actionWarnings != null && actionWarnings.contains("rate limit")) {
                logger.warn {
                    "Server-side rate limit has been reached. Waiting $RATE_LIMIT_TIMEOUT ms until next attempt. " +
                        "Consider reducing the number of requests using the `--throttle` option."
                }
                Thread.sleep(RATE_LIMIT_TIMEOUT)
                continue
            }

            // Check completion status
            if (params.containsKey("generator") && json.boolean("batchcomplete") != true) {
                logger.error {
                    "Failed to complete request for an unknown reason. Waiting $UNEXPECTED_ERROR_TIMEOUT ms until " +
                        "next attempt."
                }
                Thread.sleep(UNEXPECTED_ERROR_TIMEOUT)
                continue
            }

            return json
        }
        throw IllegalStateException("Failed to fulfil request in $MAX_REQUEST_ATTEMPTS attempts.")
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
        while (gapFrom != null)
            request(method, action, params + mapOf("generator" to generator, "gapfrom" to gapFrom))
                .also { gapFrom = it.obj("continue")?.string("gapcontinue") }
                .also { callback(it, gapFrom) }
    }
}


/**
 * Converts this map to a string of query parameters.
 *
 * That is, converts `{a: "b", c: "d"}` to `a=b&c=d`.
 *
 * @param urlEncode encodes all keys and values to be safe for usage in URLs
 * @return the query param string formed from this map
 */
private fun Map<String, String>.toQueryParamString(urlEncode: Boolean) =
    this
        .let {
            if (urlEncode)
                this
                    .mapKeys { URLEncoder.encode(it.key, "UTF-8") }
                    .mapValues { URLEncoder.encode(it.value, "UTF-8") }
            else
                this
        }
        .map { "${it.key}=${it.value}" }
        .joinToString("&")
