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

package com.ivianuu.injekt.samples.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.android.ActivityComponent
import com.ivianuu.injekt.android.createActivityComponent
import com.ivianuu.injekt.common.EntryPoint
import com.ivianuu.injekt.common.entryPoint

class MainActivity : ComponentActivity() {
  private var activityComponent: ActivityComponent? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    activityComponent = createActivityComponent()
    val dependencies: MainActivityDependencies = activityComponent!!.entryPoint()

    setContent {
      dependencies.theme {
        dependencies.appUi()
      }
    }
  }

  override fun onDestroy() {
    activityComponent?.dispose()
    super.onDestroy()
  }
}

@Provide interface MainActivityDependencies : EntryPoint<ActivityComponent> {
  val theme: AppTheme
  val appUi: AppUi
}
