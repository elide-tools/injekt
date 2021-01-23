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

package com.ivianuu.injekt.common

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.Test

class ScopeTest {
    @Test fun testGetSet() {
        val scope = Scope()
        scope.get<String>(0) shouldBe null
        scope[0] = "value"
        scope.get<String>(0) shouldBe "value"
    }

    @Test fun testScope() {
        val scope = Scope()
        var calls = 0
        scope(0) { calls++ }
        scope(0) { calls++ }
        scope(1) { calls++ }
        calls shouldBe 2
    }

    @Test fun testDispose() {
        val scope = Scope()
        var disposed = false
        scope.set(
            0,
            object : Scope.Disposable {
                override fun dispose() {
                    disposed = true
                }
            }
        )

        disposed.shouldBeFalse()
        scope.dispose()
        disposed.shouldBeTrue()
    }
}