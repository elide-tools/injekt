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

package com.ivianuu.injekt.samples.typeclasses

import com.ivianuu.injekt.Inject
import com.ivianuu.injekt.Provide

fun interface Ord<in T> {
  fun compare(a: T, b: T): Int

  companion object {
    @Provide val int = Ord<Int> { a, b -> a.compareTo(b) }
  }
}

fun <T> List<T>.sorted(@Inject ord: Ord<T>): List<T> = sortedWith { a, b -> ord.compare(a, b) }

fun main() {
  val items = listOf(5, 3, 4, 1, 2).sorted()
}
