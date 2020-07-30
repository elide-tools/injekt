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

package com.ivianuu.injekt

import com.ivianuu.injekt.internal.injektIntrinsic

@Target(AnnotationTarget.CLASS)
annotation class Component

fun initializeComponents(): Unit = injektIntrinsic()

fun <T> rootComponent(vararg inputs: Any?): T = injektIntrinsic()

@Reader
fun <T> childComponent(vararg inputs: Any?): T = injektIntrinsic()

@Reader
inline fun <R> withInstances(
    vararg instances: Any?,
    block: @Reader () -> R
): R = injektIntrinsic()
