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

package com.ivianuu.injekt.ambient

import com.ivianuu.injekt.*
import com.ivianuu.injekt.common.*
import com.ivianuu.injekt.scope.*

@Tag annotation class AmbientService<N> {
  companion object {
    @Suppress("NOTHING_TO_INLINE")
    inline fun <T> current(@Inject ambients: Ambients, @Inject key: TypeKey<T>, ): T =
      serviceAmbientOf<T>().current()

    @Provide inline fun <@Spread T : @AmbientService<N> U, U : Any, N> providedServiceValue(
      noinline factory: () -> T,
      key: TypeKey<U>
    ): NamedProvidedValue<N, U> = serviceAmbientOf<U>() provides factory
  }
}

@OptIn(InternalScopeApi::class)
@Suppress("UNCHECKED_CAST")
@PublishedApi
internal fun <T> serviceAmbientOf(@Inject key: TypeKey<T>): ProvidableAmbient<T> {
  serviceAmbients[key.value]?.let { return it as ProvidableAmbient<T> }
  synchronized(serviceAmbients) {
    serviceAmbients[key.value]?.let { return it as ProvidableAmbient<T> }
    val ambient = ambientOf<T> { error("No service provided for ${key.value}") }
    serviceAmbients[key.value] = ambient
    return ambient
  }
}

private val serviceAmbients = mutableMapOf<String, ProvidableAmbient<*>>()
