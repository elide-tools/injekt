/*
 * Copyright 2021 Manuel Wrage
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

import com.ivianuu.injekt.test.*
import io.kotest.matchers.nulls.*
import io.kotest.matchers.types.*
import org.junit.*

class ProviderTest {
  @Test fun testProviderGiven() = singleAndMultiCodegen(
    """
            @Given val foo = Foo()
    """,
    """
         fun invoke(): Foo {
                return summon<() -> Foo>()()
            } 
    """
  ) {
    invokeSingleFile()
      .shouldBeTypeOf<Foo>()
  }

  @Test fun testCannotRequestProviderForNonExistingGiven() = codegen(
    """ 
         fun invoke(): Foo {
                return summon<() -> Foo>()()
            }
    """
  ) {
    compilationShouldHaveFailed("no given argument found of type kotlin.Function0<com.ivianuu.injekt.test.Foo> for parameter value of function com.ivianuu.injekt.summon")
  }

  @Test fun testProviderWithGivenArgs() = codegen(
    """
            @Given fun bar(foo: Foo) = Bar(foo)
    """,
    """
        fun invoke() = summon<(@Given Foo) -> Bar>()(Foo()) 
    """
  ) {
    invokeSingleFile()
      .shouldBeTypeOf<Bar>()
  }

  @Test fun testProviderWithQualifiedGivenArgs() = singleAndMultiCodegen(
    """
      @Qualifier annotation class MyQualifier
      @Given fun bar(foo: @MyQualifier Foo) = Bar(foo)
    """,
    """
      fun invoke() = summon<(@Given @MyQualifier Foo) -> Bar>()(Foo()) 
    """
  ) {
    invokeSingleFile()
      .shouldBeTypeOf<Bar>()
  }

  @Test fun testProviderWithGenericGivenArgs() = singleAndMultiCodegen(
    """ 
      typealias GivenScopeA = GivenScope

      typealias GivenScopeB = GivenScope

      @Given fun givenScopeBFactory(
        parent: GivenScopeA,
        scopeFactory: () -> GivenScopeB
      ): @InstallElement<GivenScopeA> () -> GivenScopeB = scopeFactory

      typealias GivenScopeC = GivenScope

      @Given fun givenScopeCFactory(
        parent: GivenScopeB,
        scopeFactory: () -> GivenScopeC
      ): @InstallElement<GivenScopeB> () -> GivenScopeC = scopeFactory
    """,
    """
      @GivenImports("com.ivianuu.injekt.common.*", "com.ivianuu.injekt.scope.*")
      fun createGivenScopeA() = summon<GivenScopeA>()

      @InstallElement<GivenScopeC>
      @Given 
      class MyComponent(
        val a: GivenScopeA,
        val b: GivenScopeB,
        val c: GivenScopeC
      )
    """
  )

  @Test fun testProviderModule() = singleAndMultiCodegen(
    """
      @Given fun bar(foo: Foo) = Bar(foo)
      class FooModule(@Given val foo: Foo)
    """,
    """
      fun invoke() = summon<(@Given FooModule) -> Bar>()(FooModule(Foo()))
    """
  )

  @Test fun testSuspendProviderGiven() = singleAndMultiCodegen(
    """
            @Given suspend fun foo() = Foo()
    """,
    """
        fun invoke(): Foo = runBlocking { summon<suspend () -> Foo>()() } 
    """
  ) {
    invokeSingleFile()
      .shouldBeTypeOf<Foo>()
  }

  @Test fun testComposableProviderGiven() = singleAndMultiCodegen(
    """
            @Given val foo: Foo @Composable get() = Foo()
    """,
    """
        fun invoke() = summon<@Composable () -> Foo>() 
    """
  ) {
    invokeSingleFile()
  }

  @Test fun testMultipleProvidersInSetWithDependencyDerivedByProviderArgument() =
    singleAndMultiCodegen(
      """
        typealias MyGivenScope = GivenScope
        @Given val MyGivenScope.key: String get() = ""
        @Given fun foo(key: String) = Foo()
        @Given fun fooIntoSet(provider: (@Given MyGivenScope) -> Foo): (MyGivenScope) -> Any = provider as (MyGivenScope) -> Any 
        @Given class Dep(key: String)
        @Given fun depIntoSet(provider: (@Given MyGivenScope) -> Dep): (MyGivenScope) -> Any = provider as (MyGivenScope) -> Any
    """,
      """
        fun invoke() {
          summon<Set<(MyGivenScope) -> Any>>()
        } 
    """
    )

  @Test fun testProviderWhichReturnsItsParameter() = singleAndMultiCodegen(
    """
      @Given val foo = Foo()
    """,
    """
      fun invoke() = summon<(@Given Foo) -> Foo>()(Foo())
    """
  ) {
    invokeSingleFile()
      .shouldBeTypeOf<Foo>()
  }

  @Test fun testProviderWithoutCandidatesError() = codegen(
    """
         fun invoke() {
                summon<() -> Foo>()
            }
    """
  ) {
    compilationShouldHaveFailed("no given argument found of type kotlin.Function0<com.ivianuu.injekt.test.Foo> for parameter value of function com.ivianuu.injekt.summon")
  }

  @Test fun testProviderWithNullableReturnTypeUsesNullAsDefault() = codegen(
    """
         fun invoke() = summon<() -> Foo?>()()
    """
  ) {
    invokeSingleFile().shouldBeNull()
  }

  @Test fun testProviderWithNullableReturnTypeAndDefaultOnAllErrors() = codegen(
    """
            @Given fun bar(foo: Foo) = Bar(foo)
         fun invoke() = summon<@DefaultOnAllErrors () -> Bar?>()()
    """
  ) {
    invokeSingleFile().shouldBeNull()
  }
}