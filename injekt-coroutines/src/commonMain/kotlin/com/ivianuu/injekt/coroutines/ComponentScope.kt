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
import com.ivianuu.injekt.Tag
import com.ivianuu.injekt.common.Disposable
import com.ivianuu.injekt.common.Scoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

typealias ComponentScope<N> = @ComponentScopeTag<N> CoroutineScope

@Tag annotation class ComponentScopeTag<N> {
  companion object {
    @Provide fun <N> scope(
      context: ComponentContext<N>
    ): @Scoped<N> ComponentScope<N> = object : CoroutineScope, Disposable {
      override val coroutineContext: CoroutineContext = context + SupervisorJob()
      override fun dispose() {
        coroutineContext.cancel()
      }
    }
  }
}

typealias ComponentContext<N> = @ComponentContextTag<N> CoroutineContext

@Tag annotation class ComponentContextTag<N> {
  companion object {
    @Provide inline fun <N> context(
      dispatcher: DefaultDispatcher
    ): ComponentContext<N> = dispatcher
  }
}
