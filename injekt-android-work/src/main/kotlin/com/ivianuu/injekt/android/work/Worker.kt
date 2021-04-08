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

@file:Suppress("NOTHING_TO_INLINE")

package com.ivianuu.injekt.android.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.ivianuu.injekt.Given
import com.ivianuu.injekt.Qualifier
import com.ivianuu.injekt.android.AppContext
import kotlin.reflect.KClass

/**
 * Registers the annotated given [ListenableWorker] in the [InjektWorkerFactory]
 */
@Qualifier
annotation class WorkerBinding

@Given
fun <@Given T : @WorkerBinding S, S : ListenableWorker> workerBindingImpl(
    @Given provider: (@Given WorkerParameters) -> T,
    @Given workerClass: KClass<S>
): Pair<String, SingleWorkerFactory> = workerClass.java.name to provider

internal typealias SingleWorkerFactory = (WorkerParameters) -> ListenableWorker

@Given
val AppContext.workManager: WorkManager
    get() = WorkManager.getInstance(this)

@Given
class InjektWorkerFactory(
    @Given private val workers: Map<String, SingleWorkerFactory>
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = workers[workerClassName]?.invoke(workerParameters)
}
