/*
 * Copyright 2019 Manuel Wrage
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

import kotlin.jvm.internal.ClassBasedDeclarationContainer
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Describes a injectable type [Type]
 */
data class Type<T> internal constructor(
    val classifier: KClass<*>,
    val isNullable: Boolean,
    val arguments: Array<Type<*>>,
    val annotations: Array<KClass<*>>
) {

    private val javaClassifier = classifier.java

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Type<*>

        if (javaClassifier != other.javaClassifier) return false
        // todo if (isNullable != other.isNullable) return false
        if (!arguments.contentEquals(other.arguments)) return false
        // todo if (!annotations.contentEquals(other.annotations)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = javaClassifier.hashCode()
        // todo result = 31 * result + isNullable.hashCode()
        result = 31 * result + arguments.contentHashCode()
        // todo result = 31 * result + annotations.contentHashCode()
        return result
    }

    override fun toString(): String {
        val annotations = if (annotations.isNotEmpty()) {
            "${annotations.joinToString(" ")} "
        } else {
            ""
        }
        val params = if (arguments.isNotEmpty()) {
            arguments.joinToString(
                separator = ", ",
                prefix = "<",
                postfix = ">"
            ) { it.toString() }
        } else {
            ""
        }

        return "$annotations${classifier.java.name}${if (isNullable) "?" else ""}$params"
    }
}

inline fun <reified T> typeOf(): Type<T> = kotlin.reflect.typeOf<T>().asType()

@PublishedApi
internal fun <T> KType.asType(): Type<T> {
    val args = arrayOfNulls<Type<Any?>>(arguments.size)

    arguments.forEachIndexed { index, kTypeProjection ->
        args[index] = kTypeProjection.type?.asType() ?: typeOf()
    }

    return Type(
        classifier = (classifier ?: Any::class) as KClass<*>,
        arguments = args as Array<Type<*>>,
        isNullable = isMarkedNullable,
        annotations = emptyArray()/*annotations
            .map { it.annotationClass }
            .filter { annotation ->
                annotation.annotations.any { false } // todo check for the type marker https://youtrack.jetbrains.com/issue/KT-34900
            }*/
    )
}

fun <T> typeOf(
    classifier: KClass<*>,
    arguments: Array<Type<*>> = emptyArray(),
    isNullable: Boolean = false,
    annotations: Array<KClass<*>> = emptyArray()
): Type<T> {
    // todo check for the type marker https://youtrack.jetbrains.com/issue/KT-34900
    val finalClassifier = if (isNullable) boxed(classifier) else unboxed(classifier)
    return Type(
        classifier = finalClassifier,
        arguments = arguments,
        isNullable = isNullable,
        annotations = annotations
    )
}

private fun unboxed(type: KClass<*>): KClass<*> {
    val thisJClass = (type as ClassBasedDeclarationContainer).jClass
    if (thisJClass.isPrimitive) return type

    return when (thisJClass.name) {
        "java.lang.Boolean" -> Boolean::class
        "java.lang.Character" -> Char::class
        "java.lang.Byte" -> Byte::class
        "java.lang.Short" -> Short::class
        "java.lang.Integer" -> Int::class
        "java.lang.Float" -> Float::class
        "java.lang.Long" -> Long::class
        "java.lang.Double" -> Double::class
        else -> type
    }
}

private fun boxed(type: KClass<*>): KClass<*> {
    val jClass = (type as ClassBasedDeclarationContainer).jClass
    if (!jClass.isPrimitive) return type

    return when (jClass.name) {
        "boolean" -> java.lang.Boolean::class
        "char" -> java.lang.Character::class
        "byte" -> java.lang.Byte::class
        "short" -> java.lang.Short::class
        "int" -> java.lang.Integer::class
        "float" -> java.lang.Float::class
        "long" -> java.lang.Long::class
        "double" -> java.lang.Double::class
        else -> type
    }
}
