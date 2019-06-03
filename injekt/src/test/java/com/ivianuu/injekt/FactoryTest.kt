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

import com.ivianuu.injekt.util.TestDep1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FactoryTest {

    @Test
    fun testFactoryCreatesNew() {
        val component = component(
            modules = listOf(
                module {
                    factory { TestDep1() }
                }
            )
        )

        val value1 = component.get<TestDep1>()
        val value2 = component.get<TestDep1>()

        assertFalse(value1 === value2)
    }

    private object Bound : Qualifier
    private object Unbounded : Qualifier

    @Test
    fun testContextUsage() {
        lateinit var rootComponent: Component
        lateinit var nestedComponent: Component

        rootComponent = component(
            modules = listOf(
                module {
                    factory(Bound, unbounded = false) {
                        assertEquals(rootComponent, component)
                        "bound"
                    }
                    factory(Unbounded, unbounded = true) {
                        assertEquals(nestedComponent, component)
                        "unbounded"
                    }
                }
            )
        )

        nestedComponent = component(dependencies = listOf(rootComponent))

        nestedComponent.get<String>(Bound)
        nestedComponent.get<String>(Unbounded)
    }

}