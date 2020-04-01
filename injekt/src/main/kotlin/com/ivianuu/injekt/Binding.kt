/*
 * Copyright 2020 Manuel Wrage
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ivianuu.injekt

/**
 * A binding knows how to create a concrete instance of a type
 * it also holds additional information about the declaration
 *
 * You typically don't access this class directly but instead declare bindings
 * via a [ComponentBuilder] or annotating classes with [Factory] or [Single]
 *
 * @see Factory
 * @see Single
 */
class Binding<T> private constructor(
    /**
     * The key which is used to identify this binding
     */
    val key: Key<T>,
    /**
     * Behavior applied to the [provider]
     */
    val behavior: Behavior = Behavior.None,
    /**
     * How overrides should be handled
     */
    val duplicateStrategy: DuplicateStrategy = DuplicateStrategy.Fail,
    /**
     * All tags of this binding
     */
    val tags: List<Tag> = emptyList(),
    /**
     * Creates instances for this binding
     */
    val provider: BindingProvider<T>
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other != null && this::class != other::class) return false

        other as Binding<*>

        if (key != other.key) return false
        if (behavior != other.behavior) return false
        if (duplicateStrategy != other.duplicateStrategy) return false
        if (tags != other.tags) return false
        if (provider != other.provider) return false

        return true
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + behavior.hashCode()
        result = 31 * result + duplicateStrategy.hashCode()
        result = 31 * result + tags.hashCode()
        result = 31 * result + provider.hashCode()
        return result
    }

    override fun toString(): String {
        return "Binding(key=$key, behavior=$behavior, duplicateStrategy=$duplicateStrategy, tags=$tags, provider=$provider)"
    }

    fun copy(
        key: Key<T> = this.key,
        behavior: Behavior = this.behavior,
        duplicateStrategy: DuplicateStrategy = this.duplicateStrategy,
        tags: List<Tag> = this.tags,
        provider: BindingProvider<T> = this.provider
    ) = invoke(
        key,
        behavior,
        duplicateStrategy,
        tags,
        provider
    )

    companion object {
        inline operator fun <T> invoke(
            key: Key<T>,
            behavior: Behavior = Behavior.None,
            duplicateStrategy: DuplicateStrategy = DuplicateStrategy.Fail,
            tags: List<Tag> = emptyList(),
            crossinline provider: Component.(Parameters) -> T
        ): Binding<T> = invoke(
            key = key,
            behavior = behavior,
            duplicateStrategy = duplicateStrategy,
            tags = tags,
            provider = object : BindingProvider<T> {
                override fun invoke(component: Component, parameters: Parameters): T {
                    return provider(component, parameters)
                }
            }
        )

        /**
         * Returns a new [Binding] instance
         */
        operator fun <T> invoke(
            key: Key<T>,
            behavior: Behavior = Behavior.None,
            duplicateStrategy: DuplicateStrategy = DuplicateStrategy.Fail,
            tags: List<Tag> = emptyList(),
            provider: BindingProvider<T>
        ): Binding<T> = Binding(
            key = key,
            behavior = behavior,
            duplicateStrategy = duplicateStrategy,
            tags = tags,
            provider = behavior.foldIn(provider) { currentProvider, currentBehavior ->
                currentBehavior.apply(currentProvider)
            }
        )
    }

}

/**
 * Provides instances of T
 */
interface BindingProvider<T> {
    fun onAttach(component: Component) {
    }

    operator fun invoke(component: Component, parameters: Parameters): T
}

/**
 * Wraps a existing [BindingProvider]
 */
abstract class DelegatingBindingProvider<T>(
    private val delegate: BindingProvider<T>
) : BindingProvider<T> {

    private var initialized = false

    override fun onAttach(component: Component) {
        check(!initialized) { "Binding providers should not be reused" }
        initialized = true
        delegate.onAttach(component)
    }

    override fun invoke(component: Component, parameters: Parameters): T =
        delegate(component, parameters)
}
