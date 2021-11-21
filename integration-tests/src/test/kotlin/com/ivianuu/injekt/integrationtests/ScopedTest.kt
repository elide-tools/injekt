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

import com.ivianuu.injekt.common.Disposable
import com.ivianuu.injekt.test.Foo
import com.ivianuu.injekt.test.TestDisposable
import com.ivianuu.injekt.test.codegen
import com.ivianuu.injekt.test.compilationShouldHaveFailed
import com.ivianuu.injekt.test.invokeSingleFile
import com.ivianuu.injekt.test.singleAndMultiCodegen
import com.ivianuu.injekt.test.withCompose
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.Test

class ScopedTest {
  @Test fun testScopedFunction() = singleAndMultiCodegen(
    """
      @Provide interface ScopeComponent : Component {
        val foo: Foo
      } 

      @Provide @Scoped<ScopeComponent> fun foo(): Foo = Foo() 
    """,
    """
      val component = inject<ScopeComponent>()
      fun invoke() = component.foo
    """
  ) {
    invokeSingleFile() shouldBeSameInstanceAs invokeSingleFile()
  }

  @Test fun testScopedProperty() = singleAndMultiCodegen(
    """
      @Provide interface ScopeComponent : Component {
        val foo: Foo
      } 

      @Provide @Scoped<ScopeComponent> val foo: Foo get() = Foo() 
    """,
    """
      val component = inject<ScopeComponent>()
      fun invoke() = component.foo
    """
  ) {
    invokeSingleFile() shouldBeSameInstanceAs invokeSingleFile()
  }

  @Test fun testScopedClass() = singleAndMultiCodegen(
    """
      @Provide interface ScopeComponent : Component {
        val dep: Dep
      } 

      @Provide @Scoped<ScopeComponent> class Dep
    """,
    """
      val component = inject<ScopeComponent>()
      fun invoke() = component.dep
    """
  ) {
    invokeSingleFile() shouldBeSameInstanceAs invokeSingleFile()
  }

  @Test fun testScopedPrimaryConstructor() = singleAndMultiCodegen(
    """
      @Provide interface ScopeComponent : Component {
        val dep: Dep
      } 

      class Dep @Provide @Scoped<ScopeComponent> constructor()
    """,
    """
      val component = inject<ScopeComponent>()
      fun invoke() = component.dep
    """
  ) {
    invokeSingleFile() shouldBeSameInstanceAs invokeSingleFile()
  }

  @Test fun testScopedSecondaryConstructor() = singleAndMultiCodegen(
    """
      @Provide interface ScopeComponent : Component {
        val dep: Dep
      } 

      class Dep {
        @Provide @Scoped<ScopeComponent> constructor()
      }
    """,
    """
      val component = inject<ScopeComponent>()
      fun invoke() = component.dep
    """
  ) {
    invokeSingleFile() shouldBeSameInstanceAs invokeSingleFile()
  }

  @Test fun testScopedGenericConstructor() = singleAndMultiCodegen(
    """
      @Provide interface ScopeComponent : Component

      @Provide interface GenericEntryPoint<C : Component> : EntryPoint<C> {
        val dep: Dep<C>
      }

      class Dep<C : Component> @Provide @Scoped<C> constructor()
    """,
    """
      val component = inject<ScopeComponent>().entryPoint<ScopeComponent, GenericEntryPoint<ScopeComponent>>()
      fun invoke() = component.dep
    """
  ) {
    invokeSingleFile() shouldBeSameInstanceAs invokeSingleFile()
  }

  @Test fun testAccessScopedInjectableFromNestedScoped() = singleAndMultiCodegen(
    """
      @Provide interface ParentComponent : Component {
        fun childComponent(): ChildComponent
      }
      @Provide interface ChildComponent : Component {
        val dep: Dep
      }

      @Provide @Scoped<ChildComponent> class Dep
    """,
    """
      val component = inject<ParentComponent>().childComponent()
      fun invoke() = component.dep
    """
  ) {
    invokeSingleFile() shouldBeSameInstanceAs invokeSingleFile()
  }

  @Test fun testCannotResolveScopedInjectableWithoutEnclosingComponent() = singleAndMultiCodegen(
    """
      interface ScopeComponent : Component
      @Provide @Scoped<ScopeComponent> val foo: Foo = Foo() 
    """,
    """
      fun invoke(): Foo = inject<Foo>()
    """
  ) {
    compilationShouldHaveFailed("no enclosing component matches com.ivianuu.injekt.integrationtests.ScopeComponent")
  }

  @Test fun testScopedValueAccessedBySubType() = singleAndMultiCodegen(
    """
      @Provide interface ScopeComponent : Component {
        val dep: Dep
        val subType: SubType
      } 

      interface SubType
      @Provide @Scoped<ScopeComponent> class Dep : SubType
    """,
    """
      val component = inject<ScopeComponent>()
      fun invoke() = component.dep to component.subType
    """
  ) {
    val (a, b) = invokeSingleFile<Pair<Any, Any>>()
    a shouldBeSameInstanceAs b
  }

  @Test fun testScopedValueWillBeDisposed() = singleAndMultiCodegen(
    """
      @Provide interface MyComponent : Component {
        val disposable: TestDisposable
      }
    """,
    """
      fun invoke(@Inject @Scoped<MyComponent> disposable: TestDisposable) = inject<MyComponent>()
        .also { it.disposable }
    """
  ) {
    val disposable = TestDisposable()
    val component = invokeSingleFile<Disposable>(disposable)
    disposable.disposeCalls shouldBe 0
    component.dispose()
    disposable.disposeCalls shouldBe 1
  }

  @Test fun testScopedSuspendFunction() = codegen(
    """
      @Provide @Scoped<Component> suspend fun foo() = Foo() 
    """
  ) {
    compilationShouldHaveFailed("a scoped declarations call context must be default")
  }

  @Test fun testScopedComposableFunction() = codegen(
    """
      @Provide @Scoped<Component> @Composable fun foo() = Foo() 
    """,
    config = { withCompose() }
  ) {
    compilationShouldHaveFailed("a scoped declarations call context must be default")
  }

  @Test fun testScopedEager() = singleAndMultiCodegen(
    """
      @Provide interface ScopeComponent : Component {
        val foo: Foo
      }
    """,
    """
      fun invoke(@Provide foo: @Provide @Scoped<ScopeComponent>(eager = true) () -> Foo) = {
        inject<ScopeComponent>()
      }
    """
  ) {
    var called = false
    val componentFactory = invokeSingleFile<() -> Any?>({
      called = true
      Foo()
    })

    called.shouldBeFalse()
    componentFactory()
    called.shouldBeTrue()
  }
}
