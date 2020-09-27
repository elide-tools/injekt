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

package com.ivianuu.injekt.integrationtests

import com.ivianuu.injekt.test.Foo
import com.ivianuu.injekt.test.assertCompileError
import com.ivianuu.injekt.test.codegen
import com.ivianuu.injekt.test.invokeSingleFile
import com.ivianuu.injekt.test.multiCodegen
import com.ivianuu.injekt.test.source
import junit.framework.Assert.assertTrue
import org.junit.Test

class EffectTest {

    @Test
    fun testSimpleEffect() = codegen(
        """
        @Effect
        annotation class Effect1 {
            @GivenSet
            companion object {
                @Given
                fun <T> bind() = given<T>().toString()
            }
        }
        
        @Effect
        annotation class Effect2 {
            @GivenSet
            companion object {
                @Given
                fun <T : Any> bind(): Any = given<T>()
            }
        }
        
        @Given
        @Effect1
        @Effect2
        class Dep
        
        fun invoke() {
            rootContext<TestContext>().runReader { 
                given<Dep>() 
                given<String>()
                given<Any>()
            }
        }
    """
    ) {
        invokeSingleFile()
    }

    @Test
    fun testAssistedEffect() = codegen(
        """
        @Effect
        annotation class Effect1 {
            @GivenSet
            companion object {
                @Given
                fun <T : (String) -> Any> bind() = given<T>().toString()
            }
        }
        
        @Effect1
        @Given
        class Dep(arg: String)
        
        fun invoke() {
            rootContext<TestContext>().runReader { 
                given<Dep>("a") 
                given<String>()
            }
        }
    """
    ) {
        invokeSingleFile()
    }

    @Test
    fun testWithTwoTypeParameters() = codegen(
        """
        @Effect
        annotation class Memoized {
            @GivenSet
            companion object {
                @PublishedApi
                internal val instances = mutableMapOf<KClass<*>, Any?>()
                @Given
                inline fun <reified T : S, reified S> memoized(): S = instances.getOrPut(S::class) {
                    given<T>()
                } as S
            }
        }

        @Memoized
        class Dep
        
        fun invoke() {
            rootContext<TestContext>().runReader {
                val a = given<Dep>()
                val b = given<Dep>()
            }
        }
    """
    ) {
        invokeSingleFile()
    }

    @Test
    fun testEffectWithoutCompanion() = codegen(
        """
        @Effect
        annotation class MyEffect
    """
    ) {
        assertCompileError("companion")
    }

    @Test
    fun testEffectWithoutTypeParameters() = codegen(
        """
        @Effect
        annotation class MyEffect {
            @GivenSet
            companion object {
                @Given
                operator fun invoke() {
                }
            }
        }
    """
    ) {
        assertCompileError("type parameter")
    }

    @Test
    fun testEffectWithToManyTypeParameters() = codegen(
        """
        @Effect
        annotation class MyEffect {
            @GivenSet
            companion object {
                @Given
                operator fun <A, B, C> invoke() {
                }
            }
        }
    """
    ) {
        assertCompileError("type parameter")
    }

    @Test
    fun testEffectWithInvalidTypeParameterSignature() = codegen(
        """
        @Effect
        annotation class MyEffect {
            @GivenSet
            companion object {
                @Given
                operator fun <A, B> invoke() {
                }
            }
        }
    """
    ) {
        assertCompileError("type parameter")
    }

    @Test
    fun testEffectWithFunction() = codegen(
        """
        @Effect
        annotation class MyEffect {
            @GivenSet 
            companion object {
                @EffectFunction(MyEffect::class)
                @Given
                fun <T> bind() {
                }
            }
        }
        
        @MyEffect
        fun myFun() {
        }
    """
    ) {
        assertCompileError("function")
    }

    @Test
    fun testEffectNotInBounds() = codegen(
        """
        @Effect
        annotation class MyEffect {
            @GivenSet
            companion object { 
                @Given
                fun <T : UpperBound> bind() {
                }
            }
        }
        
        interface UpperBound
        
        @MyEffect
        class MyClass
    """
    ) {
        assertCompileError("bound")
    }

    @Test
    fun testFunctionEffectNotInBounds() = codegen(
        """
        @Effect
        annotation class MyEffect {
            @GivenSet
            companion object {
                @EffectFunction(MyEffect::class)
                fun <T : () -> Unit> bind() {
                }
            }
        }
        @MyEffect
        fun myFun(p0: String) {
        }
    """
    ) {
        assertCompileError("bound")
    }

    @Test
    fun testFunctionEffect() = codegen(
        """
        typealias FooFactory = () -> Foo
        
        @Effect
        annotation class GivenFooFactory {
            @GivenSet
            companion object {
                @Given
                operator fun <T : FooFactory> invoke(): FooFactory = given<T>()
            }
        }
        
        @GivenFooFactory
        fun fooFactory(): Foo {
            return Foo()
        }
        
        fun invoke(): Foo { 
            return rootContext<TestContext>().runReader { given<FooFactory>()() }
        }
    """
    ) {
        assertTrue(invokeSingleFile() is Foo)
    }

    @Test
    fun testFunctionEffectMulti() = multiCodegen(
        listOf(
            source(
                """
                typealias FooFactory = () -> Foo
        
                @Effect
                annotation class GivenFooFactory {
                    @GivenSet
                    companion object {
                        @Given
                        operator fun <T : FooFactory> invoke(): FooFactory = given<T>()
                    }
                }
            """,
                initializeInjekt = false
            ),
        ),
        listOf(
            source(
                """
                @GivenFooFactory
                fun fooFactory(): Foo {
                    return Foo()
                }
            """,
                initializeInjekt = false
            )
        ),
        listOf(
            source(
                """
                fun invoke(): Foo { 
                    return rootContext<TestContext>().runReader { given<FooFactory>()() }
                }
            """,
                name = "File.kt"
            )
        )
    ) {
        assertTrue(it.last().invokeSingleFile() is Foo)
    }

    @Test
    fun testSuspendFunctionEffect() = codegen(
        """
        typealias FooFactory = suspend () -> Foo
        
        @Effect
        annotation class GivenFooFactory {
            @GivenSet
            companion object {
                @Given
                operator fun <T : FooFactory> invoke(): FooFactory = given<T>()
            }
        }
        
        @GivenFooFactory
        suspend fun fooFactory(): Foo {
            return Foo()
        }
        
        fun invoke(): Foo { 
            return rootContext<TestContext>().runReader { 
                runBlocking { 
                    delay(1)
                    given<FooFactory>()() 
                }
            }
        }
    """
    ) {
        assertTrue(invokeSingleFile() is Foo)
    }

}
