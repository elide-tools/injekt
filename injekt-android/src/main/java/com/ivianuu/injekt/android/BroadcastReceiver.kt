/*
 * Copyright 2018 Manuel Wrage
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

package com.ivianuu.injekt.android

import android.content.BroadcastReceiver
import android.content.Context
import com.ivianuu.injekt.*

const val RECEIVER_SCOPE = "receiver_scope"

/**
 * Returns a [Component] with convenient configurations
 */
fun <T : BroadcastReceiver> receiverComponent(
    instance: T,
    context: Context,
    scopeId: String = RECEIVER_SCOPE,
    name: String? = instance.javaClass.simpleName + "Component",
    createEagerInstances: Boolean = true,
    definition: ComponentDefinition? = null
) = component(scopeId, name, createEagerInstances) {
    instance.parentComponentOrNull(context)?.let { components(it) }
    addInstance(instance)
    definition?.invoke(this)
}

/**
 * Returns the parent [Component] if available or null
 */
fun BroadcastReceiver.parentComponentOrNull(context: Context) =
    (context.applicationContext as? InjektTrait)?.component

/**
 * Returns the parent [Component] or throws
 */
fun BroadcastReceiver.parentComponent(context: Context) =
    parentComponentOrNull(context) ?: error("No parent found for $this")