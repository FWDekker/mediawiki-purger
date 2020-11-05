package com.fwdekker.mediawikipurger

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.fastily.jwiki.core.Wiki


/**
 * Purges all pages on Nukapedia.
 */
fun main() {
    val wiki = Wiki.Builder()
        .withApiEndpoint("https://fallout.fandom.com/api.php".toHttpUrlOrNull())
        .build()
    val jsonParser = Parser.default()

    val pagesByTitle = mutableMapOf<String, Page>()
    val allPages = mutableMapOf<Page, Boolean?>()

    var gapfrom: String? = ""
    while (gapfrom != null) {
        val pageList = wiki.request(
            jsonParser,
            method = "GET", action = "query",
            "format", "json",
            "generator", "allpages",
            "gaplimit", "50",
            "gapfrom", gapfrom
        )
        gapfrom = pageList.obj("query-continue")?.obj("allpages")?.string("gapfrom")

        val purgeTargets = pageList.obj("query")?.obj("pages")
            ?.map { it.value as JsonObject }
            ?.map { Page(it.int("pageid")!!, it.string("title")!!) }
            ?: throw Exception("API response does not list pages.")
        allPages.putAll(purgeTargets.associateWith { null })
        pagesByTitle.putAll(purgeTargets.associateBy { it.title })

        val purgeStatus = wiki.request(
            jsonParser,
            method = "POST", action = "purge",
            "format", "json",
            "pageids", purgeTargets.joinToString("|") { it.id.toString() }
        )
        purgeStatus.array<JsonObject>("purge")?.forEach { purgedPage ->
            val page = pagesByTitle[purgedPage.string("title")]!!
            allPages[page] = purgedPage.string("purged") !== null
        }

        println("${allPages.count { it.value == true }}/${allPages.size} successful purges. Now purging `${gapfrom}`.")
    }
}


/**
 * Sends a request to this wiki.
 *
 * @param parser the parser to parse the server's JSON response with
 * @param method the HTTP method the request should have. Must be "GET" or "POST"
 * @param action the API action to perform, such as "query" or "purge"
 * @param params the parameters to give as part of the request. If "POST" is used, give keys and values in alternating
 * order
 * @return the API's response as a parsed JSON object
 */
private fun Wiki.request(parser: Parser, method: String, action: String, vararg params: String): JsonObject {
    val response =
        when (method.toUpperCase()) {
            "POST" -> this.basicPOST(action, params.toList().toHashMap())
            "GET" -> this.basicGET(action, *params)
            else -> throw Exception("Unknown HTTP method `$method`.")
        }

    val body = response.body?.string()
        ?: throw Exception("API response body is unexpectedly null.")

    return parser.parse(StringBuilder(body)) as JsonObject
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


/**
 * A descriptor of a page on a MediaWiki wiki.
 *
 * @property id the numerical identifier of the page
 * @property title the title of the page
 */
private data class Page(val id: Int, val title: String)
