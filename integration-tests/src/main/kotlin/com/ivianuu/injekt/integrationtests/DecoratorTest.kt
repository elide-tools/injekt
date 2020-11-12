package com.ivianuu.injekt.integrationtests

import com.ivianuu.injekt.test.codegen
import com.ivianuu.injekt.test.invokeSingleFile
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import org.junit.Test

class DecoratorTest {

    @Test
    fun testExplicitDecorator() = codegen(
        """
            var called = false
            @Decorator
            annotation class MyDecorator {
                companion object {
                    fun <T> decorate(myComponent: MyComponent, factory: () -> T): () -> T {
                        return {
                            called = true
                            factory()
                        }
                    }
                }
            }
            
            @MyDecorator
            @Binding
            fun foo() = Foo()
            
            @Component
            abstract class MyComponent {
                abstract val foo: Foo
            }
            
            fun invoke(): Boolean {
                component<MyComponent>().foo
                return called
            }
        """
    ) {
        assertTrue(invokeSingleFile<Boolean>())
    }

    @Test
    fun testImplicitDecorator() = codegen(
        """
            var callCount = 0
            @Decorator
            fun <T> decorate(factory: () -> T): () -> T { 
                return {
                    callCount++
                    factory()
                }
            }
            
            @Binding
            fun foo() = Foo()
            
            @Binding
            fun bar(foo: Foo) = Bar(foo)
            
            @Binding
            fun baz(foo: Foo, bar: Bar) = Baz(bar, foo)
            
            @Component
            abstract class MyComponent {
                abstract val baz: Baz
            }
            
            fun invoke(): Int {
                component<MyComponent>().baz
                return callCount
            }
        """
    ) {
        assertEquals(4, invokeSingleFile<Int>())
    }

}