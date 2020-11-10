package com.fwdekker.mediawikipurger

import mu.KotlinLogging
import java.net.CookieManager
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse


/**
 * A wrapper around an [HttpClient] that automatically throttles the number of requests according to some strategy.
 *
 * @property throttleStrategy the strategy to limit the number of requests with
 */
class ThrottledHttpClient(private val throttleStrategy: ThrottleStrategy) {
    /**
     * Manages cookies.
     */
    private val cookieManager = CookieManager()

    /**
     * Does the HTTP.
     */
    private val client = HttpClient.newBuilder()
        .cookieHandler(cookieManager)
        .build()


    /**
     * Sends an HTTP request through an [HttpClient], waiting if too many requests have been sent recently.
     *
     * @see HttpClient.send
     */
    fun <T : Any> send(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        throttleStrategy.maybeThrottle()
        return client.send(request, responseBodyHandler)
    }

    /**
     * Removes all cookies from this client.
     */
    fun removeCookies() {
        cookieManager.cookieStore.removeAll()
    }
}


/**
 * Throttles invocations of a function according to some strategy.
 */
interface ThrottleStrategy {
    /**
     * Does nothing if no throttling is required, or sleeps until throttling can be disengaged.
     */
    fun maybeThrottle()
}

/**
 * Does not throttle anything.
 */
class NoOpThrottleStrategy : ThrottleStrategy {
    /**
     * Does nothing.
     */
    override fun maybeThrottle() {
        // Do nothing
    }
}

/**
 * Ensures that no more than a maximum number of invocations are made in any period.
 *
 * @property period the minimum time difference in milliseconds between the `n`th request and the `(n + limit)`th
 * request for any `n`
 * @constructor constructs a new leaky bucket throttle strategy
 * @param invocations the maximum number of invocations that may be made in any [period]
 */
class LeakyBucketThrottleStrategy(invocations: Int, private val period: Int) : ThrottleStrategy {
    /**
     * Logs events.
     */
    private val logger = KotlinLogging.logger {}

    /**
     * Timestamps at which invocations have been made.
     */
    private val timestamps = CircularBuffer<Long>(invocations)


    /**
     * Waits until there have been fewer than `requests` invocations in the past `period`.
     */
    override fun maybeThrottle() {
        if (timestamps.get(-1) != null) {
            val resumeTime = timestamps.get(-1)!! + period
            val waitTime = resumeTime - System.currentTimeMillis()

            if (waitTime > 0) {
                logger.trace { "Throttle engaged. Waiting $waitTime ms." }
                Thread.sleep(waitTime)
            }
        }
        timestamps.add(System.currentTimeMillis())
    }
}
