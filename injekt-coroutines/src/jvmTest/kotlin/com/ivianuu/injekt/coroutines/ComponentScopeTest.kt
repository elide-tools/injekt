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

package com.ivianuu.injekt.coroutines

import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.common.Component
import com.ivianuu.injekt.common.dispose
import com.ivianuu.injekt.inject
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import org.junit.Test

@Component interface CoroutinesComponent {
  val scope: ComponentScope<CoroutinesComponent>
}

class ComponentScopeTest {
  @Test fun testComponentScopeLifecycle() {
    val component = inject<CoroutinesComponent>()
    val scope = component.scope
    scope.isActive.shouldBeTrue()
    component.dispose()
    scope.isActive.shouldBeFalse()
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test fun testCanSpecifyCustomCoroutineContext() {
    @Provide val customContext: ComponentCoroutineContext<CoroutinesComponent> = Dispatchers.Main
    val component = inject<CoroutinesComponent>()
    val scope = component.scope
    scope.coroutineContext[CoroutineDispatcher] shouldBeSameInstanceAs customContext
  }
}