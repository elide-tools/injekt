/*
 * Copyright 2021 Manuel Wrage
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
+ *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ivianuu.injekt.android

import android.app.*
import android.content.*
import android.content.res.*
import androidx.lifecycle.*
import com.ivianuu.injekt.*
import com.ivianuu.injekt.ambient.*

/**
 * Returns [Ambients] of this
 */
@Provide val Application.appAmbients: Ambients
  get() = (this as? AppAmbientsOwner)?.appAmbients
    ?: error("application does not implement AppAmbientsOwner")

/**
 * Host of application wide [Ambients]
 */
interface AppAmbientsOwner {
  /**
   * The app ambients which are typically created via [createAppAmbients]
   */
  val appAmbients: Ambients
}

/**
 * Creates the [Ambients] which must be manually stored
 */
inline fun Application.createAppAmbients(
  @Inject providedValuesFactory: (@Provide Application) -> NamedProvidedValues<ForApp>
): Ambients = providedValuesFactory().createAmbients(ambientsOf())

typealias AppContext = Context

@Provide inline val Application.appContext: AppContext
  get() = this

typealias AppResources = Resources

@Provide inline val AppContext.appResources: AppResources
  get() = resources

typealias AppLifecycleOwner = LifecycleOwner

@Provide inline val appLifecycleOwner: AppLifecycleOwner
  get() = ProcessLifecycleOwner.get()
