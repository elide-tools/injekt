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

package com.ivianuu.injekt.comparison.dagger2

import com.ivianuu.injekt.comparison.fibonacci.Fib4
import com.ivianuu.injekt.comparison.fibonacci.Fib8
import dagger.Component
import dagger.MembersInjector
import javax.inject.Inject
import javax.inject.Provider

@Component
interface Dagger2Component {
    val fib8: Fib8

    val myClassInjector: MembersInjector<MyClass>

    @Component.Factory
    interface Factory {
        fun create(): Dagger2Component
    }
}

class MyClass {
    @Inject
    lateinit var fib4: Fib4
}

@Component
interface Test {
    val fib8: Provider<Fib8>
}