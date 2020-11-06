package com.fwdekker.mediawikipurger

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse


/**
 * Interacts with a MediaWiki wiki through its API.
 *
 * @property apiUrl the URL to the `api.php` to connect to
 * @property client the client to send requests through
 */
class Wiki(private val apiUrl: String, private val client: ThrottledHttpClient) {
    private val logger = KotlinLogging.logger {}


    /**
     * Parses JSON responses from the server.
     */
    private val parser = Parser.default()

    /**
     * Parameters that are added to each request.
     */
    private val defaultParams = mutableMapOf("format" to "json")


    /**
     * Requests a token from the wiki using the given credentials, and sends that token with each subsequent request.
     *
     * @param username the username to log in with
     * @param password the password to log in with
     * @see logOut
     */
    fun logIn(username: String, password: String) {
        logger.info { "Logging in as $username." }

        val response = request("POST", "login", mapOf("username" to username, "password" to password))
        defaultParams["token"] = response.obj("login")?.string("token")
            ?: throw IllegalStateException("Failed to obtain login token.")
    }

    /**
     * Stops sending the token acquired in [logIn].
     */
    fun logOut() {
        logger.info { "Logging out." }

        defaultParams.remove("token")
    }

    /**
     * Sends a request to this wiki.
     *
     * @param method the HTTP method the request should have. Must be "GET" or "POST"
     * @param action the API action to perform, such as "query" or "purge"
     * @param params the parameters to give as part of the request
     * @return the API's response as a parsed JSON object
     */
    fun request(method: String, action: String, params: Map<String, String>): JsonObject {
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
