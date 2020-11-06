package com.fwdekker.mediawikipurger

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import org.fastily.jwiki.core.Wiki


/**
 * An object that sends requests to MediaWiki wikis.
 *
 * An instance is bound to one particular wiki.
 */
interface RequestableWiki {
    /**
     * Sends a request to this wiki.
     *
     * @param method the HTTP method the request should have. Must be "GET" or "POST"
     * @param action the API action to perform, such as "query" or "purge"
     * @param params the parameters to give as part of the request. If "POST" is used, give keys and values in
     * alternating order
     * @return the API's response as a parsed JSON object
     */
    fun request(method: String, action: String, vararg params: String): JsonObject
}


/**
 * A wrapper around `Wiki` that works the way I want it to.
 *
 * @property wiki the wiki to wrap around
 */
class SimpleWiki(private val wiki: Wiki) : RequestableWiki {
    /**
     * Parses JSON responses from the server.
     */
    private val parser = Parser.default()


    override fun request(method: String, action: String, vararg params: String): JsonObject {
        val response =
            when (method.toUpperCase()) {
                "POST" -> wiki.basicPOST(action, params.toList().toHashMap())
                "GET" -> wiki.basicGET(action, *params)
                else -> throw Exception("Unknown HTTP method `$method`.")
            }

        val body = response.body?.string()
            ?: throw Exception("API response body is unexpectedly null.")

        return parser.parse(StringBuilder(body)) as JsonObject
    }
}


/**
 * A wrapper around [SimpleWiki] that automatically throttles the number of requests.
 *
 * @property requests the maximum number of requests that may be made in any [period]
 * @property period the minimum time difference in milliseconds between the `n`th request and the `(n + limit)`th
 * request for any `n`
 * @property wiki the wiki to wrap
 * @constructor constructs a new throttled wiki
 */
class ThrottledWiki(private val wiki: RequestableWiki, private val requests: Int, private val period: Int) :
    RequestableWiki {
    /**
     * Timestamps at which requests have been made.
     */
    private val timestamps = CircularBuffer<Long>(requests)


    /**
     * Invokes [SimpleWiki.request] on the wrapped [SimpleWiki], possibly after a timeout if the throttle has been reached.
     *
     * @see SimpleWiki.request
     */
    override fun request(method: String, action: String, vararg params: String): JsonObject {
        if (timestamps.get(-1) !== null) {
            val resumeTime = timestamps.get(-1)!! + period
            val waitTime = resumeTime - System.currentTimeMillis()

            if (waitTime > 0)
                Thread.sleep(waitTime)
        }
        timestamps.add(System.currentTimeMillis())

        return wiki.request(method, action, *params)
    }
}


/**
 * Converts a collection with an even number of elements to a hash map.
 *
 * Given `["a", "b", "c", "d"]`, this method returns `{"a": "b", "c": "d"}`.
 *
 * @return the hash map described by this collection
 */
private fun Collection<String>.toHashMap(): HashMap<String, String> {
    require(this.size % 2 == 0) { "Collection must have even number of elements to convert to hash map." }
    return this.chunked(2).map { Pair(it[0], it[1]) }.toMap().let { HashMap(it) }
}
