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

@file:Providers(
  "com.ivianuu.injekt.coroutines.*",
  "com.ivianuu.injekt.android.*",
  "com.ivianuu.injekt.samples.android.data.*",
  "com.ivianuu.injekt.samples.android.domain.*",
  "com.ivianuu.injekt.samples.android.ui.*"
)

package com.ivianuu.injekt.samples.android.app

import android.app.Application
import com.ivianuu.injekt.Inject
import com.ivianuu.injekt.Providers
import com.ivianuu.injekt.android.AppComponentOwner
import com.ivianuu.injekt.android.createAppComponent
import com.ivianuu.injekt.common.AppComponent
import com.ivianuu.injekt.inject

class App : Application(), AppComponentOwner {
  override lateinit var appComponent: AppComponent

  override fun onCreate() {
    appComponent = createAppComponent()
    super.onCreate()
  }
}

fun lol(@Inject S: String, I: Int) {
  inject<String>()
  inject<Int>()
}
