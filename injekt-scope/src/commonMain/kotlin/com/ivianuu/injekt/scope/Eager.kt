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

package com.ivianuu.injekt.scope

import com.ivianuu.injekt.Given
import com.ivianuu.injekt.Qualifier

/**
 * Creates a scoped instance as soon as scope [S] gets initialized
 */
@Qualifier
annotation class Eager<S : GivenScope>

@Given
class EagerModule<@Given T : @Eager<S> U, U : Any, S : GivenScope> {
    @Suppress("NOTHING_TO_INLINE")
    @Given
    inline fun scopedInstance(@Given instance: T): @Scoped<S> U = instance

    @Given
    fun initializer(@Given factory: () -> U): GivenScopeInitializer<S> = {
        factory()
    }
}
