package com.fwdekker.mediawikipurger

import com.beust.klaxon.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.fastily.jwiki.core.Wiki


/**
 * Purges all pages on Nukapedia.
 */
fun main() {
    val wiki = Wiki.Builder()
        .withApiEndpoint("https://fallout.fandom.com/api.php".toHttpUrlOrNull())
        .build()
        .let { ThrottledWiki(MyWiki(it), requests = 10, period = 1000) }

    val pagesByTitle = mutableMapOf<String, Page>()
    val allPages = mutableMapOf<Page, Boolean?>()

    var gapfrom: String? = ""
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


/**
 * A descriptor of a page on a MediaWiki wiki.
 *
 * @property id the numerical identifier of the page
 * @property title the title of the page
 */
private data class Page(val id: Int, val title: String)
