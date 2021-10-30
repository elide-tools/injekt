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

package com.ivianuu.injekt.compiler.analysis

import com.ivianuu.injekt.compiler.WithInjektContext
import com.ivianuu.injekt.compiler.hasAnnotation
import com.ivianuu.injekt.compiler.injektFqNames
import com.ivianuu.injekt.compiler.injektName
import com.ivianuu.injekt.compiler.resolution.isInject
import org.jetbrains.kotlin.backend.common.descriptors.allParameters
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.load.java.descriptors.JavaMethodDescriptor
import org.jetbrains.kotlin.resolve.calls.components.hasDefaultValue
import org.jetbrains.kotlin.types.TypeSubstitutor
import org.jetbrains.kotlin.utils.addToStdlib.cast

interface InjectFunctionDescriptor : FunctionDescriptor {
  val underlyingDescriptor: FunctionDescriptor
}

@WithInjektContext class InjectValueParameterDescriptor(
  parent: InjectFunctionDescriptor,
  val underlyingDescriptor: ValueParameterDescriptor
) : ValueParameterDescriptorImpl(
  parent,
  underlyingDescriptor,
  underlyingDescriptor.index,
  underlyingDescriptor.annotations,
  underlyingDescriptor.injektName(),
  underlyingDescriptor.type,
  false,
  underlyingDescriptor.isCrossinline,
  underlyingDescriptor.isNoinline,
  underlyingDescriptor.varargElementType,
  underlyingDescriptor.source
) {
  private val declaresDefaultValue =
    underlyingDescriptor.isInject() || underlyingDescriptor.declaresDefaultValue()
  override fun declaresDefaultValue(): Boolean = declaresDefaultValue
}

val ValueParameterDescriptor.hasDefaultValueIgnoringInject: Boolean
  get() = (this as? InjectValueParameterDescriptor)?.underlyingDescriptor?.hasDefaultValue()
    ?: hasDefaultValue()

@WithInjektContext abstract class AbstractInjectFunctionDescriptor(
  final override val underlyingDescriptor: FunctionDescriptor
) : InjectFunctionDescriptor {
  private val valueParameters = underlyingDescriptor
      .valueParameters
      .mapTo(mutableListOf()) { valueParameter ->
        InjectValueParameterDescriptor(this, valueParameter)
      }

  override fun getValueParameters(): MutableList<ValueParameterDescriptor> =
    valueParameters.cast()
}

@WithInjektContext fun FunctionDescriptor.toInjectFunctionDescriptor(): InjectFunctionDescriptor? {
  if (this is InjectFunctionDescriptor) return this
  if (this is JavaMethodDescriptor) return null
  if (allParameters.none { it.isInject() } &&
    !hasAnnotation(injektFqNames.inject2) &&
    (this !is ConstructorDescriptor ||
        !constructedClass.hasAnnotation(injektFqNames.inject2)))
          return null
  return when (this) {
    is ClassConstructorDescriptor -> InjectConstructorDescriptorImpl(this)
    is SimpleFunctionDescriptor -> InjectSimpleFunctionDescriptorImpl(this)
    else -> InjectFunctionDescriptorImpl(this)
  }
}

@WithInjektContext class InjectConstructorDescriptorImpl(
  underlyingDescriptor: ClassConstructorDescriptor
) : AbstractInjectFunctionDescriptor(underlyingDescriptor),
  ClassConstructorDescriptor by underlyingDescriptor {
  override fun substitute(substitutor: TypeSubstitutor): ClassConstructorDescriptor =
    InjectConstructorDescriptorImpl(
      underlyingDescriptor
        .substitute(substitutor) as ClassConstructorDescriptor
    )

  override fun getValueParameters(): MutableList<ValueParameterDescriptor> =
    super.getValueParameters()
}

@WithInjektContext class InjectFunctionDescriptorImpl(
  underlyingDescriptor: FunctionDescriptor
) : AbstractInjectFunctionDescriptor(underlyingDescriptor),
  FunctionDescriptor by underlyingDescriptor {
  override fun substitute(substitutor: TypeSubstitutor): FunctionDescriptor =
    InjectFunctionDescriptorImpl(underlyingDescriptor.substitute(substitutor) as FunctionDescriptor)

  override fun getValueParameters(): MutableList<ValueParameterDescriptor> =
    super.getValueParameters()
}

@WithInjektContext class InjectSimpleFunctionDescriptorImpl(
  underlyingDescriptor: SimpleFunctionDescriptor
) : AbstractInjectFunctionDescriptor(underlyingDescriptor),
  SimpleFunctionDescriptor by underlyingDescriptor {
  override fun substitute(substitutor: TypeSubstitutor): FunctionDescriptor =
    InjectSimpleFunctionDescriptorImpl(
      underlyingDescriptor
        .substitute(substitutor) as SimpleFunctionDescriptor
    )

  override fun getValueParameters(): MutableList<ValueParameterDescriptor> =
    super.getValueParameters()
}
