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

import com.ivianuu.injekt.test.Bar
import com.ivianuu.injekt.test.Foo
import com.ivianuu.injekt.test.assertOk
import com.ivianuu.injekt.test.codegen
import com.ivianuu.injekt.test.invokeSingleFile
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertNotSame
import junit.framework.Assert.assertSame
import junit.framework.Assert.assertTrue
import org.junit.Test

class ImplFactoryTest {

    @Test
    fun testTransient() = codegen(
        """
        interface TestComponent {
            val bar: Bar
        }
        
        @Factory
        fun createComponent(): TestComponent {
            transient { Foo() }
            transient { foo: Foo -> Bar(foo) }
            return create()
        }
        
        val component = createComponent()
        fun invoke() = component.bar
    """
    ) {
        assertNotSame(
            invokeSingleFile(),
            invokeSingleFile()
        )
    }

    @Test
    fun testScoped() = codegen(
        """
        interface TestComponent {
            val foo: Foo
        }
        
        @Factory
        fun createComponent(): TestComponent {
            scoped { Foo() }
            return create()
        }
        
        val component = createComponent()
        fun invoke() = component.foo
    """
    ) {
        assertSame(
            invokeSingleFile(),
            invokeSingleFile()
        )
    }

    @Test
    fun testAnnotatedClass() = codegen(
        """
        @Transient class AnnotatedBar(foo: Foo)
        interface TestComponent {
            val bar: AnnotatedBar
        }
        
        @Factory
        fun createComponent(): TestComponent {
            transient<Foo>()
            return create()
        }
        
        val component = createComponent()
        fun invoke() = component.bar
    """
    ) {
        assertNotSame(
            invokeSingleFile(),
            invokeSingleFile()
        )
    }

    @Test
    fun testBindingWithoutDefinition() = codegen(
        """
        interface TestComponent {
            val bar: Bar
        }
        
        @Factory
        fun createComponent(): TestComponent {
            transient<Foo>()
            transient<Bar>()
            return create()
        }
        
        val component = createComponent()
        fun invoke() = component.bar
    """
    ) {
        assertTrue(invokeSingleFile() is Bar)
    }

    @Test
    fun testBindingFromPassedProvider() = codegen(
        """
        interface TestComponent {
            val bar: Bar
        }
        
        @Factory
        fun createComponent(provider: (Foo) -> Bar): TestComponent {
            transient<Foo>()
            transient(provider)
            return create()
        }
        
        val component = createComponent { Bar(it) }
        fun invoke() = component.bar
    """
    ) {
        assertTrue(invokeSingleFile() is Bar)
    }

    @Test
    fun testBindingFromProviderReference() = codegen(
        """
        interface TestComponent {
            val bar: Bar
        }
        
        @Factory
        fun createComponent(): TestComponent {
            transient<Foo>()
            transient(::createBar)
            return create()
        }
        
        fun createBar(foo: Foo): Bar = Bar(foo)
        
        val component = createComponent()
        fun invoke() = component.bar
    """
    ) {
        assertTrue(invokeSingleFile() is Bar)
    }

    @Test
    fun testInstance() = codegen(
        """
        @InstanceFactory
        fun invoke(foo: Foo): Foo {
            instance(foo)
            return create()
        }
         """
    ) {
        val foo = Foo()
        assertSame(foo, invokeSingleFile(foo))
    }

    @Test
    fun testInclude() = codegen(
        """
        @Module
        fun module(foo: Foo) {
            instance(foo)
        }
        
        @InstanceFactory
        fun invoke(foo: Foo): Foo {
            module(foo)
            return create()
        }
        """
    ) {
        val foo = Foo()
        assertSame(foo, invokeSingleFile(foo))
    }

    @Test
    fun testDependency() = codegen(
        """
        interface DependencyComponent {
            val foo: Foo
        }
        
        @Factory
        fun createDep(): DependencyComponent {
            transient { Foo() }
            return create()
        }
        
        interface TestComponent {
            val bar: Bar
        }

        @Factory
        fun createChild(): TestComponent {
            dependency(createDep())
            transient { foo: Foo -> Bar(foo) }
            return create()
        }
        
        fun invoke() = createChild().bar
    """
    ) {
        assertTrue(invokeSingleFile() is Bar)
    }

    @Test
    fun testAlias() = codegen(
        """
        interface TestComponent {
            val any: Any
            val foo: Foo
        }
        
        @Factory
        fun createComponent(): TestComponent {
            scoped { Foo() }
            alias<Foo, Any>()
            return create()
        }
        
        val component = createComponent()
        fun invoke() = component.foo to component.any
    """
    ) {
        val (foo, any) = (invokeSingleFile() as Pair<Foo, Any>)
        assertSame(foo, any)
    }

    @Test
    fun testEmpty() = codegen(
        """
        interface TestComponent {
        }
        
        @Factory
        fun invoke(): TestComponent = create()
         """
    ) {
        invokeSingleFile()
    }

    @Test
    fun testFactoryImplementationBinding() = codegen(
        """
        interface TestComponent {
            val dep: Dep
        }
        
        @Transient class Dep(val testComponent: TestComponent)
        
        @Factory
        fun createComponent(): TestComponent = create()
        
        fun invoke(): Pair<TestComponent, TestComponent> = createComponent().let {
            it to it.dep.testComponent
        }
    """
    ) {
        val (component, dep) = invokeSingleFile<Pair<*, *>>()
        assertSame(component, dep)
    }

    @Test
    fun testGenericAnnotatedClass() = codegen(
        """
        interface TestComponent {
            val stringDep: Dep<String> 
            val intDep: Dep<Int>
        }
        
        @Transient class Dep<T>(val value: T)
        
        @Factory
        fun createComponent(): TestComponent {
            instance("hello world")
            instance(0)
            return create()
        }
    """
    )

    @Test
    fun testModuleWithTypeArguments() = codegen(
        """
        interface TestComponent {
            val string: String
            val int: Int
        }
        
        @Module
        fun <T> generic(instance: T) {
            instance(instance)
        }

        @Factory
        fun createComponent(): TestComponent { 
            generic("hello world")
            generic(42)
            return create()
        }
    """
    ) {
        assertOk()
    }

    @Test
    fun testProviderDefinitionWhichUsesTypeParameters() =
        codegen(
            """
        @Module
        fun <T : S, S> diyAlias() {
            transient { from: T -> from as S }
        }

        @InstanceFactory
        fun invoke(): Any {
            transient<Foo>()
            transient<Bar>()
            diyAlias<Bar, Any>()
            return create()
        }
         """
        ) {
            assertTrue(invokeSingleFile() is Bar)
        }

    @Test
    fun testComponentSuperTypeWithTypeParameters() =
        codegen(
            """
        interface BaseComponent<T> {
            val inject: @MembersInjector (T) -> Unit
        }
        
        class Injectable { 
            private val foo: Foo by inject()
        }
        
        interface ImplComponent : BaseComponent<Injectable>
        
        @Factory
        fun createImplComponent(): ImplComponent {
            transient { Foo() }
            return create()
        }
    """
        )

    @Test
    fun testComponentWithGenericSuperType() = codegen(
        """
        interface TypedComponent<T> {
            val inject: @MembersInjector (T) -> Unit
        }
        
        class Injectable {
            private val foo: Foo by inject()
        }

        @Factory
        fun createImplComponent(): TypedComponent<Injectable> {
            transient { Foo() }
            return create()
        }
    """
    )

    @Test
    fun testFactoryWithDefaultParameters() = codegen(
        """
        interface TestComponent {
            val string: String
        }
        
        @Factory
        fun createComponent(string: String = "default"): TestComponent {
            instance(string)
            return create()
        }
        
        fun invoke() = createComponent().string to createComponent("non_default").string
    """
    ) {
        val pair = invokeSingleFile<Pair<String, String>>()
        assertEquals("default", pair.first)
        assertEquals("non_default", pair.second)
    }
}
