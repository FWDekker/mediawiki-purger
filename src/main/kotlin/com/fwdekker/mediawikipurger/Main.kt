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
    private val logger = KotlinLogging.logger {}

    private class LoginOptions : OptionGroup() {
        val username by option("--username")
            .help("The username to log in as, including the @.")
            .required()
        val password by option("--bot-password")
            .help("The bot password to log in with.")
            .required()
    }

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
            "The maximum amount of API requests per time period in milliseconds, such as `10 1000` for 10 requests " +
                "per second."
        )
        .int().pair()
        .default(Pair(1, 2500))
    private val startFrom by option("--start-from")
        .help(
            "Starts purging pages in alphabetical order starting from this page title. Does not have to refer to an " +
                "existing page."
        )
        .default("")
    private val loginOptions by LoginOptions().cooccurring()


    override fun run() {
        var successfulPurgeCount = 0

        Wiki(
            apiUrl,
            ThrottledHttpClient(LeakyBucketThrottleStrategy(invocations = throttle.first, period = throttle.second))
        )
            .also { wiki -> loginOptions?.let { wiki.logIn(it.username, it.password) } }
            .traverse(
                "POST",
                action = "purge",
                generator = "allpages", startFrom = startFrom,
                params = mapOf("gaplimit" to pageSize.toString())
            ) { response, nextPage ->
                val purgedPages = response.array<JsonObject>("purge")!!
                val failedPurges = purgedPages.filter { it.boolean("purged") == false }.map { it.string("title") }
                successfulPurgeCount += purgedPages.size - failedPurges.size

                if (failedPurges.isNotEmpty())
                    logger.warn { "Failed to purge ${failedPurges.size}: $failedPurges." }

                logger.info {
                    "Successfully purged $successfulPurgeCount page(s)." +
                        if (nextPage != null) " Next up: `$nextPage`."
                        else " This was the last batch."
                }
            }

        logger.info { "Completed purging." }
    }
}
