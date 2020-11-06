package com.fwdekker.mediawikipurger

import com.beust.klaxon.JsonObject
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.cooccurring
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.pair
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import mu.KotlinLogging


/**
 * Runs the MediaWiki purger.
 */
fun main(args: Array<String>) = Purger().main(args)


/**
 * Purges all pages on a MediaWiki wiki.
 */
class Purger : CliktCommand() {
    private class LoginOptions : OptionGroup() {
        val username by option()
            .help("The username to authenticate with against the API.")
            .required()
        val password by option()
            .help("The password to authenticate with against the API.")
            .required()
    }


    private val logger = KotlinLogging.logger {}

    private val apiUrl by option("--api")
        .help("The URL to the MediaWiki API, such as https://www.mediawiki.org/w/api.php.")
        .required()
    private val pageSize by option("--page-size")
        .help("Amount of pages to purge at a time.")
        .int()
        .default(50)
        .check("Page size must be at least one.") { it > 0 }
    private val throttle by option("--throttle")
        .help(
            """
            The maximum amount of API requests per time period in milliseconds, such as `10 1000` for 10 requests
            per second. Note that each purge requires two requests.
            """.trimIndent()
        )
        .int().pair()
        .default(Pair(2, 1000))
    private val startFrom by option("--start-from")
        .help(
            """
            Starts purging pages in alphabetical order starting from this page title. Does not have to refer to an
            existing page.
            """.trimIndent()
        )
        .default("")

    private val userOptions by LoginOptions().cooccurring()


    override fun run() {
        val wiki = ThrottledWiki(SimpleWiki(apiUrl), requests = throttle.first, period = throttle.second)
        userOptions?.also { wiki.logIn(it.username, it.password) }

        val pagesByTitle = mutableMapOf<String, Page>()
        val allPages = mutableMapOf<Page, Boolean?>()

        var gapFrom: String? = startFrom
        while (gapFrom != null) {
            val pageList = wiki.request(
                method = "GET", action = "query",
                mapOf(
                    "format" to "json",
                    "generator" to "allpages",
                    "gaplimit" to pageSize.toString(),
                    "gapfrom" to gapFrom
                )
            )
            gapFrom = pageList.obj("query-continue")?.obj("allpages")?.string("gapfrom") // MW 1.19.24 (Fandom)
                ?: pageList.obj("continue")?.string("gapcontinue") // MW 1.33.3 (Fandom UCP)

            val purgeTargets = pageList.obj("query")?.obj("pages")
                ?.map { it.value as JsonObject }
                ?.map { Page(it.int("pageid")!!, it.string("title")!!) }
                ?: throw Exception("API response does not list pages.")
            allPages.putAll(purgeTargets.associateWith { null })
            pagesByTitle.putAll(purgeTargets.associateBy { it.title })

            val purgeStatus = wiki.request(
                method = "POST", action = "purge",
                mapOf(
                    "format" to "json",
                    "pageids" to purgeTargets.joinToString("|") { it.id.toString() }
                )
            )
            purgeStatus.array<JsonObject>("purge")?.forEach { purgedPage ->
                val page = pagesByTitle[purgedPage.string("title")]!!
                allPages[page] = purgedPage.string("purged") !== null
            }

            logger.info {
                """
                ${allPages.count { it.value == true }}/${allPages.size} successful purges. Now purging `${gapFrom}`.
                """.trimIndent()
            }
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
