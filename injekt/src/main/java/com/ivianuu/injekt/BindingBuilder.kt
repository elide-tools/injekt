/*
 * Copyright 2018 Manuel Wrage
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

import kotlin.properties.Delegates
import kotlin.reflect.KClass

/**
 * Builder for [Binding]s
 */
class BindingBuilder<T> internal constructor() {

    var type: KClass<*> by Delegates.notNull()
        private set
    var name: Any? = null
        private set
    var kind: Kind by Delegates.notNull()
        private set
    var definition: Definition<T> by Delegates.notNull()
        private set
    var attributes = Attributes()
        private set

    val additionalBindings: List<Binding<*>> get() = _additionalBindings
    private val _additionalBindings = arrayListOf<Binding<*>>()

    fun type(type: KClass<*>) {
        this.type = type
    }

    fun name(name: Any?) {
        this.name = name
    }

    fun kind(kind: Kind) {
        this.kind = kind
    }

    fun definition(definition: Definition<T>) {
        this.definition = definition
    }

    fun setAttributes(attributes: Attributes) {
        this.attributes = attributes
    }

    fun additionalBinding(binding: Binding<*>) {
        _additionalBindings.add(binding)
    }

    /**
     * Builds the [Binding] from this builder
     */
    fun build(): Binding<T> = Binding(
        Key(type, name), kind, definition, attributes, additionalBindings
    )

}

/**
 * Creates a copy of this builder
 */
fun <T> BindingBuilder<T>.copy(): BindingBuilder<T> {
    val other = BindingBuilder<T>()

    other.type(type)
    other.name(name)
    other.kind(kind)
    other.definition(definition)
    other.setAttributes(attributes)

    return other
}

fun BindingBuilder<*>.attributes(attributes: Attributes) {
    attributes(attributes.entries)
}

fun BindingBuilder<*>.attributes(attributes: Map<String, Any?>) {
    attributes.forEach {
        this.attributes[it.key] = it.value
    }
}

fun BindingBuilder<*>.attribute(key: String, value: Any?) {
    attributes.set(key, value)
}

/**
 * Adds a additional binding for [T]
 */
inline fun <reified T> BindingBuilder<*>.bindType() {
    bindType(T::class)
}

/**
 * Adds a additional binding for [type]
 */
fun BindingBuilder<*>.bindType(type: KClass<*>) {
    val copy = copy()
    copy.type(type)
    copy.name(null)
    additionalBinding(copy.build())
}

/**
 * Binds all of [types]
 */
fun BindingBuilder<*>.bindTypes(vararg types: KClass<*>) {
    types.forEach { bindType(it) }
}

/**
 * Binds all of [types]
 */
fun BindingBuilder<*>.bindTypes(types: Iterable<KClass<*>>) {
    types.forEach { bindTypes(it) }
}

/**
 * Binds all of [types]
 */
fun BindingBuilder<*>.bindTypes(type: KClass<*>) {
    bindType(type)
}


fun BindingBuilder<*>.bindName(name: Any) {
    val copy = copy()
    copy.name(name)
    additionalBinding(copy.build())
}

fun BindingBuilder<*>.bindNames(vararg names: Any) {
    names.forEach { bindName(it) }
}

fun BindingBuilder<*>.bindNames(names: Iterable<Any>) {
    names.forEach { bindName(it) }
}

fun BindingBuilder<*>.bindNames(name: Any) {
    bindName(name)
}

inline fun <reified T> BindingBuilder<*>.bindAlias(name: Any) {
    bindAlias(T::class, name)
}

fun BindingBuilder<*>.bindAlias(type: KClass<*>, name: Any) {
    val copy = copy()
    copy.type(type)
    copy.name(name)
    additionalBinding(copy.build())
}