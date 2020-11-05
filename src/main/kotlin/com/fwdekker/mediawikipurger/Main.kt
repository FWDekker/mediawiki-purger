package com.fwdekker.mediawikipurger

import com.beust.klaxon.JsonObject
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.pair
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.fastily.jwiki.core.Wiki


/**
 * Runs the MediaWiki purger.
 */
fun main(args: Array<String>) = Purger().main(args)


/**
 * Purges all pages on a MediaWiki wiki.
 */
class Purger : CliktCommand() {
    private val apiUrl by option("--api")
        .help("The URL to the MediaWiki API, such as https://www.mediawiki.org/w/api.php.")
        .convert("URL") { it.toHttpUrlOrNull() ?: fail("The URL `${it}` is malformed.") }
        .required()

    private val throttle by option("--throttle")
        .help(
            "The maximum amount of requests per time period in milliseconds, such as `10 1000` for 10 requests per " +
                "second."
        )
        .int().pair()

    private val startFrom by option("--startFrom")
        .help("Starts purging pages in alphabetical order starting from this page title. Does not have to refer to " +
            "an existing page.")


    override fun run() {
        val wiki = Wiki.Builder()
            .withApiEndpoint(apiUrl)
            .build()
            .let { BasicWiki(it) }
            .let { wiki ->
                throttle
                    ?.let { ThrottledWiki(wiki, requests = it.first, period = it.second) }
                    ?: wiki
            }

        val pagesByTitle = mutableMapOf<String, Page>()
        val allPages = mutableMapOf<Page, Boolean?>()

        var gapfrom: String? = startFrom
        while (gapfrom != null) {
            val pageList = wiki.request(
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
}


/**
 * A descriptor of a page on a MediaWiki wiki.
 *
 * @property id the numerical identifier of the page
 * @property title the title of the page
 */
private data class Page(val id: Int, val title: String)
