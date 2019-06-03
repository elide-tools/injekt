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

package com.ivianuu.injekt.compiler

import com.squareup.kotlinpoet.ClassName

data class CreatorDescriptor(
    val target: ClassName,
    val creatorName: ClassName,
    val kind: ClassName,
    val scope: ClassName?,
    val constructorParams: List<ParamDescriptor>
)

sealed class ParamDescriptor {
    abstract val paramName: String

    data class Parameter(
        override val paramName: String,
        val index: Int
    ) : ParamDescriptor()

    data class Dependency(
        override val paramName: String,
        val qualifierName: ClassName?
    ) : ParamDescriptor()
}