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

package com.ivianuu.injekt.android.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.inject
import io.kotest.matchers.types.shouldBeTypeOf
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class InjektWorkerTest {
  @Test fun testWorkerBinding() {
    val workerFactory = inject<(Context) -> WorkerFactory>()(mockk())
    workerFactory.createWorker(mockk(), TestWorker::class.java.name, mockk())
      .shouldBeTypeOf<TestWorker>()
  }
}

@Provide @InjektWorker class TestWorker(
  appContext: Context,
  workerParams: WorkerParameters
) : Worker(appContext, workerParams) {
  override fun doWork(): Result = Result.success()
}
