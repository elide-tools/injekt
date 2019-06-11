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

import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Describes a injected [Type]
 */
class Type<T> internal constructor(
    val raw: KClass<*>,
    val isNullable: Boolean,
    val parameters: Array<out Type<*>>
) {

    val rawJava = raw.java

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Type<*>) return false

        if (rawJava != other.rawJava) return false
        if (isNullable != other.isNullable) return false
        if (!parameters.contentEquals(other.parameters)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = rawJava.hashCode()
        result = 31 * result + isNullable.hashCode()
        result = 31 * result + parameters.contentHashCode()
        return result
    }

    override fun toString(): String {
        val params = if (parameters.isNotEmpty()) {
            parameters.joinToString(
                separator = ", ",
                prefix = "<",
                postfix = ">"
            ) { it.toString() }
        } else {
            ""
        }

        return "${rawJava.name}${if (isNullable) "?" else ""}$params"
    }

}

@UseExperimental(ExperimentalStdlibApi::class)
inline fun <reified T> typeOf(): Type<T> = kotlin.reflect.typeOf<T>().asType()

@PublishedApi
internal fun <T> KType.asType(): Type<T> {
    val parameters = arrayOfNulls<Type<*>>(arguments.size)
    arguments.forEachIndexed { i, kType ->
        parameters[i] = kType.type!!.asType<Any?>()
    }

    return Type<T>(
        classifier as KClass<*>,
        isMarkedNullable,
        parameters as Array<out Type<*>>
    )
}

fun <T> typeOf(raw: KClass<*>): Type<T> {
    when (raw) {
        Boolean::class, java.lang.Boolean::class.java -> return typeOf<Boolean>() as Type<T>
        Byte::class, java.lang.Byte::class.java -> return typeOf<Byte>() as Type<T>
        Char::class, java.lang.Character::class.java -> return typeOf<Char>() as Type<T>
        Double::class, java.lang.Double::class.java -> return typeOf<Double>() as Type<T>
        Float::class, java.lang.Float::class.java -> return typeOf<Float>() as Type<T>
        Int::class, java.lang.Integer::class.java -> return typeOf<Int>() as Type<T>
        Long::class, java.lang.Long::class.java -> return typeOf<Long>() as Type<T>
        Short::class, java.lang.Short::class.java -> return typeOf<Short>() as Type<T>
    }

    return Type(raw, false, emptyArray())
}

fun <T> typeOf(raw: KClass<*>, vararg parameters: Type<*>): Type<T> =
    Type(raw, false, parameters)

fun <T> typeOf(raw: KClass<*>, isNullable: Boolean): Type<T> {
    if (isNullable) {
        when (raw) {
            Boolean::class, java.lang.Boolean::class -> return typeOf<Boolean?>() as Type<T>
            Byte::class, java.lang.Byte::class -> return typeOf<Byte?>() as Type<T>
            Char::class, java.lang.Character::class -> return typeOf<Char?>() as Type<T>
            Double::class, java.lang.Double::class -> return typeOf<Double?>() as Type<T>
            Float::class, java.lang.Float::class -> return typeOf<Float?>() as Type<T>
            Int::class, java.lang.Integer::class -> return typeOf<Int?>() as Type<T>
            Long::class, java.lang.Long::class -> return typeOf<Long?>() as Type<T>
            Short::class, java.lang.Short::class -> return typeOf<Short?>() as Type<T>
        }
    } else {
        when (raw) {
            Boolean::class, java.lang.Boolean::class.java -> return typeOf<Boolean>() as Type<T>
            Byte::class, java.lang.Byte::class.java -> return typeOf<Byte>() as Type<T>
            Char::class, java.lang.Character::class.java -> return typeOf<Char>() as Type<T>
            Double::class, java.lang.Double::class.java -> return typeOf<Double>() as Type<T>
            Float::class, java.lang.Float::class.java -> return typeOf<Float>() as Type<T>
            Int::class, java.lang.Integer::class.java -> return typeOf<Int>() as Type<T>
            Long::class, java.lang.Long::class.java -> return typeOf<Long>() as Type<T>
            Short::class, java.lang.Short::class.java -> return typeOf<Short>() as Type<T>
        }
    }

    return Type(raw, isNullable, emptyArray())
}

fun <T> typeOf(raw: KClass<*>, isNullable: Boolean, vararg parameters: Type<*>): Type<T> {
    return Type(raw, isNullable, parameters)
}

fun <T> typeOf(type: java.lang.reflect.Type, isNullable: Boolean = false): Type<T> {
    if (type is WildcardType) {
        if (type.upperBounds.isNotEmpty()) {
            return typeOf(type.upperBounds.first(), isNullable)
        } else if (type.lowerBounds.isNotEmpty()) {
            return typeOf(type.lowerBounds.first(), isNullable)
        }
    }

    if (type is ParameterizedType) {
        return Type<T>(
            (type.rawType as Class<*>).kotlin,
            isNullable,
            (type as? ParameterizedType)
                ?.actualTypeArguments
                ?.map { typeOf<Any?>(it) }
                ?.toTypedArray()
                ?: emptyArray()
        )
    }

    if (isNullable) {
        when (type.typeName) {
            "boolean" -> return typeOf<Boolean?>() as Type<T>
            "byte" -> return typeOf<Byte?>() as Type<T>
            "char" -> return typeOf<Char?>() as Type<T>
            "double" -> return typeOf<Double?>() as Type<T>
            "float" -> return typeOf<Float?>() as Type<T>
            "int" -> return typeOf<Int?>() as Type<T>
            "long" -> return typeOf<Long?>() as Type<T>
            "short" -> return typeOf<Short?>() as Type<T>
        }
    } else {
        when (type) {
            java.lang.Boolean::class.java -> return typeOf<Boolean>() as Type<T>
            java.lang.Byte::class.java -> return typeOf<Byte>() as Type<T>
            java.lang.Character::class.java -> return typeOf<Char>() as Type<T>
            java.lang.Double::class.java -> return typeOf<Double>() as Type<T>
            java.lang.Float::class.java -> return typeOf<Float>() as Type<T>
            java.lang.Integer::class.java -> return typeOf<Int>() as Type<T>
            java.lang.Long::class.java -> return typeOf<Long>() as Type<T>
            java.lang.Short::class.java -> return typeOf<Short>() as Type<T>
        }
    }

    return Type((type as Class<*>).kotlin, isNullable, emptyArray())
}