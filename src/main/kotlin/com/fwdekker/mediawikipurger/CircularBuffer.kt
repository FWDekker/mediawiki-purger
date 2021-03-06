package com.fwdekker.mediawikipurger


/**
 * A circular buffer that holds [size] items.
 *
 * Once `size` elements have been added, the oldest item will be overwritten each time a new item is added.
 *
 * @param T the non-null type of elements held by the buffer
 * @property size the maximum number of elements before the oldest element should be overwritten. Must be strictly
 * positive
 */
class CircularBuffer<T : Any>(private val size: Int) {
    /**
     * The index in [items] of the next item to fill.
     */
    private var nextIndex: Int = 0

    /**
     * The items that have been added.
     */
    private val items: MutableList<T?>


    init {
        require(size > 0) { "Requests per minute must be strictly positive, but was $size." }

        items = MutableList(size) { null }
    }


    /**
     * Adds an item into this buffer, overwriting the oldest element if there are more than [size] elements.
     *
     * @param item the item to add
     */
    fun add(item: T) {
        items[nextIndex] = item
        nextIndex = (nextIndex + 1) % size
    }

    /**
     * Returns the item at the given index relative to the head, or `null` if there is no such item.
     *
     * Does not return `null` if [isCircular] returns `true`.
     *
     * @param index the index relative to the head to return, where 0 is the newest element, 1 is the second-newest
     * elements, and `size - 1` is the oldest element. Interpreted modulo [size], so negative numbers are allowed.
     * @return the element at the given index
     */
    fun get(index: Int) = items[Math.floorMod(nextIndex - index - 1, size)]
}
