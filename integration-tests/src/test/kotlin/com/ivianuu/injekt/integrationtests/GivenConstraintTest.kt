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
import com.ivianuu.injekt.test.codegen
import com.ivianuu.injekt.test.compilationShouldHaveFailed
import com.ivianuu.injekt.test.invokeSingleFile
import com.ivianuu.injekt.test.multiCodegen
import com.ivianuu.injekt.test.shouldNotContainMessage
import com.ivianuu.injekt.test.source
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import org.junit.Test

class GivenConstraintTest {

    @Test
    fun testGivenWithGivenConstraint() = codegen(
        """
            @Qualifier annotation class Trigger
            @Given fun <@Given T : @Trigger S, S> triggerImpl(@Given instance: T): S = instance

            @Given fun foo(): @Trigger Foo = Foo()

            fun invoke() = given<Foo>()
        """
    ) {
        invokeSingleFile().shouldBeTypeOf<Foo>()
    }

    @Test
    fun testClassWithGivenConstraint() = codegen(
        """
            @Given
            class MyModule<@Given T : @Trigger S, S> {
                @Given fun intoSet(@Given instance: T): @Final S = instance
            }
            @Qualifier annotation class Trigger

            @Qualifier annotation class Final

            @Given fun foo(): @Trigger Foo = Foo()
            @Given fun string(): @Trigger String = ""

            fun invoke() = given<Set<@Final Foo>>()
        """
    ) {
        invokeSingleFile<Set<Foo>>().size shouldBe 1
    }

    @Test
    fun testGivenConstraintOnNonGivenClass() = codegen(
        """
            class MyModule<@Given T>
        """
    ) {
        compilationShouldHaveFailed("a @Given type constraint is only supported on @Given functions and @Given classes")
    }

    @Test
    fun testGivenConstraintOnNonGivenFunction() = codegen(
        """
            fun <@Given T> triggerImpl() = Unit
        """
    ) {
        compilationShouldHaveFailed("a @Given type constraint is only supported on @Given functions and @Given classes")
    }

    @Test
    fun testGivenConstraintOnNonFunction() = codegen(
        """
            val <@Given T> T.prop get() = Unit
        """
    ) {
        compilationShouldHaveFailed("a @Given type constraint is only supported on @Given functions")
    }

    @Test
    fun testMultipleGivenConstraints() = codegen(
        """
            @Given fun <@Given T, @Given S> triggerImpl() = Unit
        """
    ) {
        compilationShouldHaveFailed("a declaration may have only one @Given type constraint")
    }

    @Test
    fun testGivenConstraintWithQualifierWithTypeParameter() = codegen(
        """
            @Qualifier annotation class Trigger<S>
            @Given fun <@Given @ForTypeKey T : @Trigger<S> Any?, @ForTypeKey S> triggerImpl() = 
                typeKeyOf<S>()

            @Given fun foo(): @Trigger<Bar> Foo = Foo()

            fun invoke() = given<TypeKey<Bar>>().value
        """
    ) {
        "com.ivianuu.injekt.test.Bar" shouldBe invokeSingleFile()
    }

    @Test
    fun testGivenConstraintWithQualifierWithTypeParameterMulti() = multiCodegen(
        listOf(
            source(
                """
                    @Qualifier annotation class Trigger<S>
                    @Given fun <@Given @ForTypeKey T : @Trigger<S> Any?, @ForTypeKey S> triggerImpl() = 
                        typeKeyOf<S>()
                """
            )
        ),
        listOf(
            source(
                """
                    @Given fun foo(): @Trigger<Bar> Foo = Foo()
                """
            )
        ),
        listOf(
            source(
                """
                    fun <T> givenTypeKeyOf(@Given value: () -> TypeKey<T>) = value()
                    fun invoke() = givenTypeKeyOf<Bar>().value
                """,
                name = "File.kt"
            )
        )
    ) {
        "com.ivianuu.injekt.test.Bar" shouldBe it.invokeSingleFile()
    }

    @Test
    fun testGivenConstraintWithQualifierWithTypeParameterMulti2() = multiCodegen(
        listOf(
            source(
                """
                    @Qualifier annotation class Trigger<S>
                    @Given fun <@Given @ForTypeKey T : @Trigger<S> Any?, @ForTypeKey S> triggerImpl() = 
                        typeKeyOf<S>()
                """
            )
        ),
        listOf(
            source(
                """
                    @Given
                    object FooModule {
                        @Given fun fooModule(): @Trigger<Bar> Foo = Foo()
                    }
                """
            )
        ),
        listOf(
            source(
                """
                    fun <T> givenKeyOf(@Given value: () -> TypeKey<T>) = value()
                    fun invoke() = givenKeyOf<Bar>().value
                """,
                name = "File.kt"
            )
        )
    ) {
        "com.ivianuu.injekt.test.Bar" shouldBe it.invokeSingleFile()
    }

    @Test
    fun testGivenConstraintTriggeredByClass() = codegen(
        """
            @Qualifier annotation class Trigger
            @Given fun <@Given T : @Trigger S, S> triggerImpl(@Given instance: T): S = instance

            @Trigger @Given class NotAny

            fun invoke() = given<NotAny>()
        """
    ) {
        invokeSingleFile()
    }

    @Test
    fun testGivenConstraintChain() = codegen(
        """
            @Qualifier annotation class A

            @Given fun <@Given T : @A S, S> aImpl() = AModule<S>()

            class AModule<T> {
                @Given
                fun my(@Given instance: T): @B T = instance
            }

            @Qualifier annotation class B
            @Given fun <@Given T : @B S, S> bImpl() = BModule<T>()

            class BModule<T> {
                @Given
                fun my(@Given instance: T): @C Any? = instance
            }

            @Qualifier annotation class C
            @Given fun <@Given T : @C Any?> cImpl() = Foo()

            @Given fun dummy(): @A Long = 0L
            
            fun invoke() = given<Set<Foo>>().single()
        """
    ) {
        invokeSingleFile()
            .shouldBeTypeOf<Foo>()
    }

    @Test
    fun testScoped() = multiCodegen(
        listOf(
            source(
                """
                    typealias ActivityGivenScope = DefaultGivenScope
                    @Given val activityGivenScopeModule = 
                        ChildGivenScopeModule0<AppGivenScope, ActivityGivenScope>()
                """
            )
        ),
        listOf(
            source(
                """
                    @Given fun foo(): @Scoped<AppGivenScope> Foo = Foo()
                    fun invoke() = given<Foo>()
                """,
                name = "File.kt"
            )
        )
    ) {
        it.invokeSingleFile()
            .shouldBeTypeOf<Foo>()
    }

    @Test
    fun testMultipleConstrainedContributionsWithSameType() = codegen(
        """
            @Qualifier annotation class Trigger
            @Given fun <@Given T : @Trigger String> triggerImpl(@Given instance: T): String = instance

            @Given fun a(): @Trigger String = "a"
            @Given fun b(): @Trigger String = "b"

            fun invoke() = given<Set<String>>()
        """
    ) {
        invokeSingleFile<Set<String>>()
            .shouldContainExactly("a", "b")
    }

    @Test
    fun testGivenConstraintTypeParameterNotMarkedAsUnused() = codegen(
        """
            @Qualifier annotation class Trigger
            @GivenSetElement fun <@Given T : @Trigger String> triggerImpl(): String = ""
        """
    ) {
        shouldNotContainMessage("Type parameter \"T\" is never used")
    }

    @Test
    fun testNoFinalTypeWarningOnGivenConstraintTypeParameter() = codegen(
        """
            @Qualifier annotation class Trigger
            @GivenSetElement fun <@Given T : @Trigger String> triggerImpl(): String = ""
        """
    ) {
        shouldNotContainMessage("'String' is a final type, and thus a value of the type parameter is predetermined")
    }

    @Test
    fun testCanResolveTypeBasedOnGivenConstraintType() = codegen(
        """
            @Qualifier annotation class Trigger
            @Given fun <@Given T : @Trigger S, S> triggerImpl(
                @Given pair: Pair<S, S>
            ): Int = 0

            @Given
            val string: @Trigger String = ""

            @Given
            fun stringPair() = "a" to "b"

            fun invoke() = given<Int>()
        """
    )

    @Test
    fun testCanResolveTypeWithConstraintTypeArgument() = codegen(
        """
            @Given fun <@Given T : String> triggerImpl(
                @Given pair: Pair<T, T>
            ): Int = 0

            @Given
            val string = ""

            @Given
            fun stringPair() = "a" to "b"

            fun invoke() = given<Int>()
        """
    )

    @Test
    fun testUiDecorator() = codegen(
        """
            typealias UiDecorator = @Composable (@Composable () -> Unit) -> Unit

            @Qualifier annotation class UiDecoratorBinding

            @Given
            fun <@Given T : @UiDecoratorBinding S, @ForTypeKey S : UiDecorator> uiDecoratorBindingImpl(
                @Given instance: T
            ): UiDecorator = instance as UiDecorator

            typealias RootSystemBarsProvider = UiDecorator

            @Given
            fun rootSystemBarsProvider(): @UiDecoratorBinding RootSystemBarsProvider = {}

            fun invoke() = given<Set<UiDecorator>>().size
        """
    ) {
        1 shouldBe invokeSingleFile()
    }

    @Test
    fun testDivergentConstrainedGiven() = codegen(
        """
            @Given fun <@Given T> constrainedGiven(@Given instance: T): T = instance

            @Given fun foo() = Foo()

            fun invoke() = given<Foo>()
        """
    ) {
        compilationShouldHaveFailed("constrained given return type must not be assignable to the constraint type")
    }

    @Test
    fun testComplexGivenConstraintSetup() = codegen(
        """
            typealias App = Any

            @Scoped<AppGivenScope>
            @Given
            class Dep(@Given app: App)

            @Scoped<AppGivenScope>
            @Given
            class DepWrapper(@Given dep: Dep)

            @Scoped<AppGivenScope>
            @Given
            class DepWrapper2(@Given dep: () -> Dep, @Given wrapper: () -> DepWrapper)

            fun invoke() {
                given<(@Given @GivenScopeElementBinding<AppGivenScope> App) -> AppGivenScope>()
            }
            @GivenScopeElementBinding<AppGivenScope>
            @Given
            class MyComponent(@Given dep: Dep, @Given wrapper: () -> () -> DepWrapper, @Given wrapper2: () -> DepWrapper2)

            @Given
            fun myInitializer(@Given dep: Dep, @Given wrapper: () -> () -> DepWrapper, @Given wrapper2: () -> DepWrapper2): GivenScopeInitializer<AppGivenScope> = {}
        """
    )

    @Test
    fun testGivenConstraintWithAbstractGiven() = codegen(
        """
            @Qualifier annotation class Trigger
            @Given fun <@Given T : @Trigger S, S> triggerImpl(@Given instance: T): S = instance

            @Given
            @Trigger
            interface FooComponent {
                @Given val foo: Foo
            }

            @Given val foo = Foo()

            fun invoke() = given<FooComponent>()
        """
    )
}
