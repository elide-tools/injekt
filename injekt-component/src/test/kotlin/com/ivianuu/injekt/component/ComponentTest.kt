package com.ivianuu.injekt.component

import com.ivianuu.injekt.GivenSetElement
import com.ivianuu.injekt.common.keyOf
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.Test

class ComponentTest {

    @Test
    fun testReturnsExistingValue() {
        val component = Component(TestComponent1) { element("value") }
        component.get<String>() shouldBe "value"
    }

    @Test
    fun testReturnsNullForNotExistingValue() {
        val component = Component(TestComponent1)
        component.getOrNull(keyOf<String>()) shouldBe null
    }

    @Test
    fun testReturnsFromDependency() {
        val component = Component(TestComponent2) {
            dependency(
                Component(TestComponent1) {
                    element("value")
                }
            )
        }
        component.get<String>() shouldBe "value"
    }

    @Test fun testGetDependencyReturnsDependency() {
        val dependency = Component(TestComponent1)
        val dependent = Component(TestComponent2) { dependency(dependency) }
        dependent.getDependencyOrNull(TestComponent1) shouldBeSameInstanceAs dependency
    }

    @Test fun testGetDependencyReturnsNullIfNotExists() {
        val dependent = Component(TestComponent1)
        dependent.getDependencyOrNull(TestComponent1) shouldBe null
    }

    @Test
    fun testOverridesDependency() {
        val component = Component(TestComponent2) {
            dependency(
                Component(TestComponent1) {
                    element("dependency")
                }
            )
            element("child")
        }
        component.get<String>() shouldBe "child"
    }

    @Test
    fun testInjectedElement() {
        @GivenSetElement val injected = componentElement(TestComponent1, "value")
        val component = ComponentBuilder(TestComponent1).build()
        component.get<String>() shouldBe "value"
    }

    @Test fun testGetSet() {
        val component = Component(TestComponent1)
        component.getScopedValue<String>(0) shouldBe null
        component.setScopedValue(0, "value")
        component.getScopedValue<String>(0) shouldBe "value"
    }

    @Test fun testScope() {
        val component = Component(TestComponent1)
        var calls = 0
        component.scope(0) { calls++ }
        component.scope(0) { calls++ }
        component.scope(1) { calls++ }
        calls shouldBe 2
    }

    @Test fun testDispose() {
        val component = Component(TestComponent1)
        var disposed = false
        component.setScopedValue(
            0,
            object : Component.Disposable {
                override fun dispose() {
                    disposed = true
                }
            }
        )

        disposed.shouldBeFalse()
        component.dispose()
        disposed.shouldBeTrue()
    }

}
