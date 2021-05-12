package com.ivianuu.injekt.common

import com.ivianuu.injekt.*

class NotGiven<out T> private constructor() {
    abstract class LowPriorityNotGiven internal constructor() {
        private val defaultInstance: NotGiven<Nothing> = NotGiven()
        @Given
        fun <T> default(): NotGiven<T> = defaultInstance
    }
    companion object : LowPriorityNotGiven() {
        @Given
        fun <T> amb1(@Given value: T): NotGiven<T> = throw AssertionError()

        @Given
        fun <T> amb2(@Given value: T): NotGiven<T> = throw AssertionError()
    }
}
