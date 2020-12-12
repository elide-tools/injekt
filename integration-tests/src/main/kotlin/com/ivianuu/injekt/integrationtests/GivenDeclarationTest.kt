package com.ivianuu.injekt.integrationtests

import com.ivianuu.injekt.test.Foo
import com.ivianuu.injekt.test.codegen
import com.ivianuu.injekt.test.invokeSingleFile
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertSame
import junit.framework.Assert.assertTrue
import org.junit.Test

class GivenDeclarationTest {

    @Test
    fun testGivenClass() = codegen(
        """
            @Given val foo = Foo()
            @Given class Dep(val foo: Foo = given)
            fun invoke() = given<Dep>()
        """
    ) {
        assertEquals("com.ivianuu.injekt.integrationtests.Dep",
            invokeSingleFile<Any>().javaClass.name)
    }

    @Test
    fun testGivenClassPrimaryConstructor() = codegen(
        """
            @Given val foo = Foo()
            class Dep @Given constructor(val foo: Foo = given)
            fun invoke() = given<Dep>()
        """
    ) {
        assertEquals("com.ivianuu.injekt.integrationtests.Dep",
            invokeSingleFile<Any>().javaClass.name)
    }

    @Test
    fun testGivenClassSecondaryConstructor() = codegen(
        """
            @Given val foo = Foo()
            class Dep {
                @Given constructor(foo: Foo = given)
            }
            fun invoke() = given<Dep>()
        """
    ) {
        assertEquals("com.ivianuu.injekt.integrationtests.Dep",
            invokeSingleFile<Any>().javaClass.name)
    }

    @Test
    fun testGivenAliasClass() = codegen(
        """
            interface Dep<T> {
                val value: T
            }
            @Given class DepImpl<T>(override val value: T = given) : @Given Dep<T>

            @Given val foo = Foo()
            fun invoke() = given<Dep<Foo>>()
        """
    ) {
        assertEquals("com.ivianuu.injekt.integrationtests.DepImpl",
            invokeSingleFile<Any>().javaClass.name)
    }

    @Test
    fun testGivenObject() = codegen(
        """
            @Given val foo = Foo()
            @Given object Dep {
                init {
                    given<Foo>()
                }
            }
            fun invoke() = given<Dep>()
        """
    ) {
        assertEquals("com.ivianuu.injekt.integrationtests.Dep",
            invokeSingleFile<Any>().javaClass.name)
    }

    @Test
    fun testGivenProperty() = codegen(
        """
            @Given val foo = Foo()
            fun invoke() = given<Foo>()
        """
    ) {
        assertTrue(invokeSingleFile<Any>() is Foo)
    }

    @Test
    fun testGivenFunction() = codegen(
        """
            @Given fun foo() = Foo()
            fun invoke() = given<Foo>()
        """
    ) {
        assertTrue(invokeSingleFile<Any>() is Foo)
    }

    @Test
    fun testExplicitGivenValueParameter() = codegen(
        """
            fun invoke(@Given foo: Foo) = given<Foo>()
        """
    ) {
        val foo = Foo()
        assertSame(foo, invokeSingleFile<Any>(foo))
    }

    @Test
    fun testImplicitGivenValueParameter() = codegen(
        """
            fun invoke(foo: Foo = given) = given<Foo>()
        """
    ) {
        val foo = Foo()
        assertSame(foo, invokeSingleFile<Any>(foo))
    }

    @Test
    fun testGivenExtensionReceiver() = codegen(
        """
            fun @receiver:Given Foo.invoke() = given<Foo>()
        """
    ) {
        val foo = Foo()
        assertSame(foo, invokeSingleFile<Any>(foo))
    }

    @Test
    fun testGivenLocalVariable() = codegen(
        """
            fun invoke(foo: Foo): Foo {
                @Given val givenFoo = foo
                return given()
            }
        """
    ) {
        val foo = Foo()
        assertSame(foo, invokeSingleFile<Any>(foo))
    }

    // todo @Test
    fun testGivenLambdaReceiverParameter() = codegen(
        """
            inline fun <R> withGiven(value: T, block: @Given T.() -> R) = block(value)
            fun invoke(foo: Foo): Foo {
                return withGiven(foo) { given<Foo>() }
            }
        """
    ) {
        val foo = Foo()
        assertSame(foo, invokeSingleFile<Any>(foo))
    }

    @Test
    fun testGivenLambdaParameterDeclarationSite() = codegen(
        """
            inline fun <T, R> withGiven(value: T, block: (@Given T) -> R) = block(value)
            fun invoke(foo: Foo): Foo {
                return withGiven(foo) { given<Foo>() }
            }
        """
    ) {
        val foo = Foo()
        assertSame(foo, invokeSingleFile<Any>(foo))
    }

    @Test
    fun testGivenLambdaParameterUseSite() = codegen(
        """
            inline fun <T, R> withGiven(value: T, block: (T) -> R) = block(value)
            fun invoke(foo: Foo): Foo {
                return withGiven(foo) { foo: @Given Foo -> given<Foo>() }
            }
        """
    ) {
        val foo = Foo()
        assertSame(foo, invokeSingleFile<Any>(foo))
    }

    // todo @Test
    fun testCanResolveGivenOfScopeFunction() = codegen(
        """
            class Dep(@Given val foo: Foo)
            fun invoke(foo: Foo): Foo {
                return with(Dep(foo)) { given<Foo>() }
            }
        """
    ) {
        val foo = Foo()
        assertSame(foo, invokeSingleFile<Any>(foo))
    }

    @Test
    fun testGivenInNestedBlock() = codegen(
        """
            fun invoke(a: Foo, b: Foo): Pair<Foo, Foo> {
                return run {
                    @Given val givenA = a
                    given<Foo>() to run {
                        @Given val givenB = b
                        given<Foo>()
                    }
                }
            }
        """
    ) {
        val a = Foo()
        val b = Foo()
        val result = invokeSingleFile<Pair<Foo, Foo>>(a, b)
        assertSame(a, result.first)
        assertSame(b, result.second)
    }

}
