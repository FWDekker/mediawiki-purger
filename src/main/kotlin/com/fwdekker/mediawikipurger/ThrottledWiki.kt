package com.fwdekker.mediawikipurger

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse


/**
 * An object that sends requests to MediaWiki wikis.
 *
 * An instance is bound to one particular wiki.
 */
interface RequestableWiki {
    /**
     * Requests a token from the wiki using the given credentials, and sends that token with each subsequent request.
     *
     * @param username the username to log in with
     * @param password the password to log in with
     * @see logOut
     */
    fun logIn(username: String, password: String)

    /**
     * Stops sending the token acquired in [logIn].
     */
    fun logOut()

    /**
     * Sends a request to this wiki.
     *
     * @param method the HTTP method the request should have. Must be "GET" or "POST"
     * @param action the API action to perform, such as "query" or "purge"
     * @param params the parameters to give as part of the request
     * @return the API's response as a parsed JSON object
     */
    fun request(method: String, action: String, params: Map<String, String> = emptyMap()): JsonObject
}


/**
 * Interacts with a MediaWiki wiki through its API.
 */
class SimpleWiki(private val apiUrl: String) : RequestableWiki {
    private val logger = KotlinLogging.logger {}


    /**
     * Does HTTP.
     */
    private val client = HttpClient.newBuilder().build()

    /**
     * Parses JSON responses from the server.
     */
    private val parser = Parser.default()

    /**
     * Parameters that are added to each request.
     */
    private val defaultParams = mutableMapOf("format" to "json")


    override fun logIn(username: String, password: String) {
        val response = request("POST", "login", mapOf("username" to username, "password" to password))
        defaultParams["token"] = response.obj("login")?.string("token")
            ?: throw IllegalStateException("Failed to obtain login token.")
    }

    override fun logOut() {
        defaultParams.remove("token")
    }

    override fun request(method: String, action: String, params: Map<String, String>): JsonObject {
        val allParams = defaultParams + Pair("action", action) + params
        val uri = URI.create("$apiUrl?${allParams.map { "${it.key}=${it.value}" }.joinToString("&")}")
        logger.debug { "Sending request to `$uri`." }

        val request = HttpRequest.newBuilder()
            .method(method, HttpRequest.BodyPublishers.noBody())
            .uri(uri)
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        logger.debug { "Received `${response.body()}`." }

        return parser.parse(StringBuilder(response.body())) as JsonObject
    }
}


/**
 * A wrapper around a [RequestableWiki] that automatically throttles the number of requests.
 *
 * @property requests the maximum number of requests that may be made in any [period]
 * @property period the minimum time difference in milliseconds between the `n`th request and the `(n + limit)`th
 * request for any `n`
 * @property wiki the wiki to wrap
 * @constructor constructs a new throttled wiki
 */
class ThrottledWiki(private val wiki: RequestableWiki, private val requests: Int, private val period: Int) :
    RequestableWiki {
    private val logger = KotlinLogging.logger {}

    /**
     * Timestamps at which requests have been made.
     */
    private val timestamps = CircularBuffer<Long>(requests)


    override fun logIn(username: String, password: String) = wiki.logIn(username, password)

    override fun logOut() = wiki.logOut()

    /**
     * Invokes [SimpleWiki.request] on the wrapped [SimpleWiki], possibly after a timeout if the throttle has been reached.
     *
     * @see SimpleWiki.request
     */
    override fun request(method: String, action: String, params: Map<String, String>): JsonObject {
        if (timestamps.get(-1) !== null) {
            val resumeTime = timestamps.get(-1)!! + period
            val waitTime = resumeTime - System.currentTimeMillis()

            if (waitTime > 0) {
                logger.trace { "Throttle engaged. Waiting $waitTime ms." }
                Thread.sleep(waitTime)
            }
        }
        timestamps.add(System.currentTimeMillis())

        return wiki.request(method, action, params)
    }
}
