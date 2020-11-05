package com.fwdekker.mediawikipurger

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe


/**
 * Unit tests for [CircularBuffer].
 */
object CircularBufferTest : Spek({
    describe("constructor") {
        it("throws an exception if a zero size is given") {
            assertThatThrownBy { CircularBuffer<Int>(size = 0) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Requests per minute must be strictly positive, but was 0.")
        }

        it("throws an exception if a negative size is given") {
            assertThatThrownBy { CircularBuffer<Int>(size = -8) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Requests per minute must be strictly positive, but was -8.")
        }
    }

    describe("get") {
        data class Case(val size: Int, val items: List<Int>, val index: Int)

        mapOf(
            // Empty buffer
            Case(42, emptyList(), -2) to null,
            Case(86, emptyList(), -1) to null,
            Case(35, emptyList(), 0) to null,
            Case(33, emptyList(), 1) to null,
            Case(70, emptyList(), 2) to null,
            Case(96, emptyList(), 95) to null, // size - 1
            Case(70, emptyList(), 70) to null, // size
            Case(89, emptyList(), 90) to null, // size + 1
            Case(93, emptyList(), -92) to null, // -size + 1
            Case(77, emptyList(), -77) to null, // -size
            Case(27, emptyList(), -28) to null, // -size - 1
            Case(34, emptyList(), 4910) to null, // large positive
            Case(87, emptyList(), -8874) to null, // large negative

            // Semi-full buffer
            Case(4, listOf(68, 49), -5) to null, // -size - 1
            Case(4, listOf(68, 49), -4) to 49,
            Case(4, listOf(68, 49), -3) to 68,
            Case(4, listOf(68, 49), -2) to null,
            Case(4, listOf(68, 49), -1) to null,
            Case(4, listOf(68, 49), 0) to 49,
            Case(4, listOf(68, 49), 1) to 68,
            Case(4, listOf(68, 49), 2) to null,
            Case(4, listOf(68, 49), 3) to null,
            Case(4, listOf(68, 49), 4) to 49,
            Case(4, listOf(68, 49), 5) to 68, // +size + 1

            // Full buffer
            Case(3, listOf(79, 14, 28), -4) to 79, // -size - 1
            Case(3, listOf(79, 14, 28), -3) to 28,
            Case(3, listOf(79, 14, 28), -2) to 14,
            Case(3, listOf(79, 14, 28), -1) to 79,
            Case(3, listOf(79, 14, 28), 0) to 28,
            Case(3, listOf(79, 14, 28), 1) to 14,
            Case(3, listOf(79, 14, 28), 2) to 79,
            Case(3, listOf(79, 14, 28), 3) to 28,
            Case(3, listOf(79, 14, 28), 4) to 14, // +size + 1

            // Overfull buffer
            Case(3, listOf(20, 14, 42, 88, 20), -6) to 20, // -itemCount - 1
            Case(3, listOf(20, 14, 42, 88, 20), -5) to 88,
            Case(3, listOf(20, 14, 42, 88, 20), -4) to 42,
            Case(3, listOf(20, 14, 42, 88, 20), -3) to 20,
            Case(3, listOf(20, 14, 42, 88, 20), -2) to 88,
            Case(3, listOf(20, 14, 42, 88, 20), -1) to 42,
            Case(3, listOf(20, 14, 42, 88, 20), 0) to 20,
            Case(3, listOf(20, 14, 42, 88, 20), 1) to 88,
            Case(3, listOf(20, 14, 42, 88, 20), 2) to 42,
            Case(3, listOf(20, 14, 42, 88, 20), 3) to 20,
            Case(3, listOf(20, 14, 42, 88, 20), 4) to 88,
            Case(3, listOf(20, 14, 42, 88, 20), 5) to 42,
            Case(3, listOf(20, 14, 42, 88, 20), 6) to 20, // +itemCount + 1
        ).forEach { (case, expected) ->
            it("$case => $expected") {
                val buffer = CircularBuffer<Int>(case.size)
                case.items.forEach { buffer.add(it) }

                assertThat(buffer.get(case.index)).isEqualTo(expected)
            }
        }
    }
})
