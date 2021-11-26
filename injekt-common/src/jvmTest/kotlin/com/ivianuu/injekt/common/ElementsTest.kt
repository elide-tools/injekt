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

package com.ivianuu.injekt.common

import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.inject
import io.kotest.matchers.shouldBe
import org.junit.Test

class ElementsTest {
  @Test fun testElements() {
    class MyScope

    @Provide val int: @Element<MyScope> Int = 42
    @Provide val string: @Element<MyScope> String = "42"

    val elements = inject<Elements<MyScope>>()

    elements.get<Int>() shouldBe 42
    elements.get<String>() shouldBe "42"
  }
}