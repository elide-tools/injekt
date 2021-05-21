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
import io.kotest.matchers.types.*
import org.jetbrains.kotlin.name.*
import org.junit.*

class InjectableResolveTest {
  @Test fun testResolvesExternalInjectableInSamePackage() = singleAndMultiCodegen(
    """
      @Provide val foo = Foo()
    """,
    """
      fun invoke() = inject<Foo>()
    """
  ) {
    invokeSingleFile().shouldBeTypeOf<Foo>()
  }

  @Test fun testResolvesExternalInjectableInDifferentPackage() = singleAndMultiCodegen(
    listOf(
      listOf(
        source(
          """
            @Provide val foo = Foo()
          """,
          packageFqName = FqName("injectables")
        )
      ),
      listOf(
        source(
          """
            @Providers("injectables.*")
            fun invoke() = inject<Foo>()
          """,
          name = "File.kt"
        )
      )
    )
  ) {
    invokeSingleFile().shouldBeTypeOf<Foo>()
  }

  @Test fun testResolvesInternalInjectableFromDifferentPackageWithAllUnderImport() = codegen(
    listOf(
      source(
        """
          @Provide val foo = Foo()
        """,
        packageFqName = FqName("injectables")
      ),
      source(
        """
          @Providers("injectables.*")
          fun invoke() = inject<Foo>()
        """,
        name = "File.kt"
      )
    )
  ) {
    invokeSingleFile().shouldBeTypeOf<Foo>()
  }

  @Test fun testResolvesInjectableInSamePackageAndSameFile() = codegen(
    """
      @Provide val foo = Foo()
      fun invoke() = inject<Foo>()
    """
  ) {
    invokeSingleFile().shouldBeTypeOf<Foo>()
  }

  @Test fun testResolvesClassCompanionInjectableFromWithinTheClass() = singleAndMultiCodegen(
    """
      class MyClass {
        fun resolve() = inject<Foo>()
        companion object {
          @Provide val foo = Foo()
        }
      }
    """,
    """
      fun invoke() = MyClass().resolve() 
    """
  ) {
    invokeSingleFile().shouldBeTypeOf<Foo>()
  }

  @Test fun testResolvesClassCompanionInjectableFromOuterClass() = singleAndMultiCodegen(
    """
      class MyClass {
        companion object {
          @Provide val foo = Foo()
        }
      }
    """,
    """
      fun invoke() = inject<Foo>() 
    """
  ) {
    invokeSingleFile().shouldBeTypeOf<Foo>()
  }

  @Test fun testResolvesClassCompanionClassInjectableFromOuterClass() = singleAndMultiCodegen(
    """
      class MyClass {
        companion object {
          @Provide class MyModule {
            @Provide val foo = Foo()
          }
        }
      }
    """,
    """
        fun invoke() = inject<Foo>() 
    """
  ) {
    invokeSingleFile().shouldBeTypeOf<Foo>()
  }

  @Test fun testResolvesClassConstructorInjectable() = singleAndMultiCodegen(
    """
      class MyClass(@Provide val foo: Foo = Foo()) {
        fun resolve() = inject<Foo>()
      }
    """,
    """
      fun invoke() = MyClass().resolve() 
    """
  ) {
    invokeSingleFile().shouldBeTypeOf<Foo>()
  }

  @Test fun testResolvesClassInjectable() = singleAndMultiCodegen(
    """
      class MyClass {
        @Provide val foo = Foo()
        fun resolve() = inject<Foo>()
      }
    """,
    """
      fun invoke() = MyClass().resolve() 
    """
  ) {
    invokeSingleFile().shouldBeTypeOf<Foo>()
  }

  @Test fun testDerivedInjectable() = singleAndMultiCodegen(
    """
      @Provide val foo = Foo()
      @Provide val bar: Bar = Bar(inject())
    """,
    """
      fun invoke() = inject<Bar>() 
    """
  ) {
    invokeSingleFile()
      .shouldBeTypeOf<Bar>()
  }

  @Test fun testCanResolveSubTypeOfInjectable() = singleAndMultiCodegen(
    """
      interface Repo
      @Provide class RepoImpl : Repo
    """,
    """
      fun invoke() = inject<Repo>() 
    """
  ) {
    invokeSingleFile()
  }

  @Test fun testUnresolvedInjectable() = codegen(
    """
      fun invoke() {
        inject<String>()
      }
    """
  ) {
    compilationShouldHaveFailed("no injectable found of type kotlin.String")
  }

  @Test fun testNestedUnresolvedInjectable() = singleAndMultiCodegen(
    """
      @Provide fun bar(foo: Foo) = Bar(foo)
    """,
    """
      fun invoke() = inject<Bar>() 
    """
  ) {
    compilationShouldHaveFailed(" no injectable found of type com.ivianuu.injekt.test.Foo for parameter foo of function com.ivianuu.injekt.integrationtests.bar")
  }

  @Test fun testGenericInjectable() = singleAndMultiCodegen(
    """
      @Provide val foo = Foo()
      @Provide fun <T> injectableList(value: T): List<T> = listOf(value)
    """,
    """
      fun invoke() = inject<List<Foo>>() 
    """
  ) {
    val (foo) = invokeSingleFile<List<Any>>()
    foo.shouldBeTypeOf<Foo>()
  }

  @Test fun testFunctionInvocationWithInjectables() = singleAndMultiCodegen(
    """
      @Provide val foo = Foo()
      fun usesFoo(@Inject foo: Foo) {
      }
    """,
    """
      fun invoke() {
        usesFoo()
      } 
    """
  ) {
    invokeSingleFile()
  }

  @Test fun testLocalFunctionInvocationWithInjectables() = codegen(
    """
      @Provide val foo = Foo()
      fun invoke() {
        fun usesFoo(@Inject foo: Foo) {
        }                    
        usesFoo()
      }
    """
  ) {
    invokeSingleFile()
  }

  @Test fun testConstructorInvocationWithInjectables() = singleAndMultiCodegen(
    """
      @Provide val foo = Foo()
      class UsesFoo(@Inject foo: Foo)
    """,
    """
      fun invoke() {
        UsesFoo()
      } 
    """
  ) {
    invokeSingleFile()
  }

  @Test fun testPrimaryConstructorInjectableWithReceiver() = singleAndMultiCodegen(
    """
      class UsesFoo(@Provide val foo: Foo)
    """,
    """
      fun invoke(foo: Foo) = with(UsesFoo(foo)) {
        inject<Foo>()
      }
    """.trimIndent()
  ) {
    val foo = Foo()
    foo shouldBeSameInstanceAs invokeSingleFile(foo)
  }

  @Test fun testLocalConstructorInvocationWithInjectables() = codegen(
    """
      @Provide val foo = Foo()
      fun invoke() {
        class UsesFoo(@Inject foo: Foo)
        UsesFoo()
      }
    """
  ) {
    invokeSingleFile()
  }

  @Test fun testCanResolveInjectableOfInjectableThisFunction() = codegen(
    """
      class Dep(@Provide val foo: Foo)
      fun invoke(foo: Foo) = with(Dep(foo)) { inject<Foo>() }
    """
  ) {
    val foo = Foo()
    foo shouldBeSameInstanceAs invokeSingleFile(foo)
  }

  @Test fun testCanResolveInjectableWhichDependsOnAssistedInjectableOfTheSameType() = singleAndMultiCodegen(
    """
      typealias SpecialScope = Unit
      
      @Provide fun <E> asRunnable(factory: (@Provide SpecialScope) -> List<E>): List<E> = factory(Unit)
      
      @Provide fun raw(scope: SpecialScope): List<String> = listOf("")
    """,
    """
      fun invoke() = inject<List<String>>()
    """
  )

  @Test fun testCanResolveStarProjectedType() = singleAndMultiCodegen(
    """
      @Provide fun foos() = Foo() to Foo()
      
      @Qualifier annotation class First
      @Provide fun <A : @First B, B> first(pair: Pair<B, *>): A = pair.first as A
    """,
    """
      fun invoke() = inject<@First Foo>() 
    """
  )

  @Test fun testCannotResolveObjectWithoutInjectable() = singleAndMultiCodegen(
    """
      object MyObject
    """,
    """
      fun invoke() = inject<MyObject>() 
    """
  ) {
    compilationShouldHaveFailed("no injectable")
  }

  @Test fun testCanResolveObjectWithInjectable() = singleAndMultiCodegen(
    """
      @Provide object MyObject
    """,
    """
      fun invoke() = inject<MyObject>() 
    """
  )

  @Test fun testCannotResolveExternalInternalInjectable() = multiCodegen(
    """
      @Provide internal val foo = Foo()
    """,
    """
     fun invoke() = inject<Foo>()
    """
  ) {
    compilationShouldHaveFailed("no injectable found of type com.ivianuu.injekt.test.Foo")
  }

  @Test fun testCannotResolvePrivateInjectableFromOuterScope() = singleAndMultiCodegen(
    """
      @Provide class FooHolder {
        @Provide private val foo = Foo()
      }
    """,
    """
      fun invoke() = inject<Foo>() 
    """
  ) {
    compilationShouldHaveFailed("no injectable found of type com.ivianuu.injekt.test.Foo")
  }

  @Test fun testCanResolvePrivateInjectableFromInnerScope() = codegen(
    """
      @Provide class FooHolder {
        @Provide private val foo = Foo()
        fun invoke() = inject<Foo>()
      }
    """
  )

  @Test fun testCannotResolveProtectedInjectableFromOuterScope() = singleAndMultiCodegen(
    """
      @Provide open class FooHolder {
        @Provide protected val foo = Foo()
      }
    """,
    """
      fun invoke() = inject<Foo>() 
    """
  ) {
    compilationShouldHaveFailed("no injectable found of type com.ivianuu.injekt.test.Foo")
  }

  @Test fun testCanResolveProtectedInjectableFromSameClass() = codegen(
    """
      @Provide open class FooHolder {
        @Provide protected val foo = Foo()
        fun invoke() = inject<Foo>()
      }
    """
  )

  @Test fun testCanResolveProtectedInjectableFromSubClass() = singleAndMultiCodegen(
    """
      abstract class AbstractFooHolder {
        @Provide protected val foo = Foo()
      }
    """,
    """
      class FooHolderImpl : AbstractFooHolder() {
        fun invoke() = inject<Foo>()
      } 
    """
  )

  @Test fun testCannotResolvePropertyWithTheSameNameAsAnInjectablePrimaryConstructorParameter() =
    singleAndMultiCodegen(
      """
        @Provide class MyClass(foo: Foo) {
          val foo = foo
        }
      """,
      """
        fun invoke() = inject<Foo>() 
      """
    ) {
      compilationShouldHaveFailed("no injectable found of type com.ivianuu.injekt.test.Foo for parameter value of function com.ivianuu.injekt.inject")
    }

  @Test fun testCanResolveExplicitMarkedInjectableConstructorParameterFromOutsideTheClass() =
    singleAndMultiCodegen(
      """
        class MyClass(@Provide val foo: Foo)
      """,
      """
        fun invoke(@Provide myClass: MyClass) = inject<Foo>() 
      """
    )

  @Test fun testCannotResolveImplicitInjectableConstructorParameterFromOutsideTheClass() =
    singleAndMultiCodegen(
      """
        @Provide class MyClass(val foo: Foo)
      """,
      """
        fun invoke() = inject<Foo>() 
      """
    ) {
      compilationShouldHaveFailed("no injectable found of type com.ivianuu.injekt.test.Foo for parameter value of function com.ivianuu.injekt.inject")
    }

  @Test fun testCanResolveImplicitInjectableConstructorParameterFromInsideTheClass() = codegen(
    """
      @Provide class MyClass(private val foo: Foo) {
        fun invoke() = inject<Foo>()
      }
    """
  )

  @Test fun testResolvesInjectableWithTypeParameterInScope() = singleAndMultiCodegen(
    """
      @Provide fun <T> list(): List<T> = emptyList()
    """,
    """
      fun <T> invoke() {
        inject<List<T>>()
      } 
    """
  )

  @Test fun testCannotUseNonReifiedTypeParameterForReifiedInjectable() = singleAndMultiCodegen(
    """
      @Provide inline fun <reified T> list(): List<T> {
        T::class
        return emptyList()
      }
    """,
    """
      fun <T> invoke() {
        inject<List<T>>()
      }
    """
  ) {
    compilationShouldHaveFailed(
      "type parameter T of injectable com.ivianuu.injekt.integrationtests.list() of type kotlin.collections.List<com.ivianuu.injekt.integrationtests.invoke.T> for parameter value of function com.ivianuu.injekt.inject is marked with reified but type argument com.ivianuu.injekt.integrationtests.invoke.T is not marked with reified"
    )
  }

  @Test fun testCannotUseNonForTypeKeyTypeParameterForForTypeKeyInjectable() = singleAndMultiCodegen(
    """
      @Provide fun <@ForTypeKey T> list(): List<T> {
        typeKeyOf<T>()
        return emptyList()
      }
    """,
    """
      fun <T> invoke() {
        inject<List<T>>()
      } 
    """
  ) {
    compilationShouldHaveFailed(
      "type parameter T of injectable com.ivianuu.injekt.integrationtests.list() of type kotlin.collections.List<com.ivianuu.injekt.integrationtests.invoke.T> for parameter value of function com.ivianuu.injekt.inject is marked with @ForTypeKey but type argument com.ivianuu.injekt.integrationtests.invoke.T is not marked with @ForTypeKey"
    )
  }

  @Test fun testCannotResolveUnparameterizedSubTypeOfParameterizedInjectable() = singleAndMultiCodegen(
    """
      typealias TypedString<T> = String
  
      @Provide val foo = Foo()
  
      @Provide fun <T : Foo> typedString(value: T): TypedString<T> = ""
    """,
    """
      fun invoke() = inject<String>() 
    """
  )

  @Test fun testCannotResolveUnparameterizedSubTypeOfParameterizedInjectableWithQualifiers() =
    singleAndMultiCodegen(
      """
        typealias TypedString<T> = String

        @Provide val foo = Foo()

        @Provide fun <T : Foo> typedString(value: T): @TypedQualifier<T> TypedString<T> = ""
    """,
    """
      fun invoke() = inject<@TypedQualifier<Foo> String>() 
    """
    )

  @Test fun testSafeCallWithInject() = singleAndMultiCodegen(
      """
        @Provide val foo = Foo()

        fun String.myFunc(@Inject foo: Foo) {
        }
      """,
      """
      fun invoke() = (null as? String)?.myFunc()
    """
    )
}