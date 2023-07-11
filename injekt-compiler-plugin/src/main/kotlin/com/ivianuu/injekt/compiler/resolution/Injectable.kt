/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(UnsafeCastFunction::class)

package com.ivianuu.injekt.compiler.resolution

import com.ivianuu.injekt.compiler.Context
import com.ivianuu.injekt.compiler.allParametersWithContext
import com.ivianuu.injekt.compiler.analysis.hasDefaultValueIgnoringInject
import com.ivianuu.injekt.compiler.asNameId
import com.ivianuu.injekt.compiler.injektIndex
import com.ivianuu.injekt.compiler.injektName
import com.ivianuu.injekt.compiler.transform
import com.ivianuu.injekt.compiler.uniqueKey
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.inline.InlineUtil
import org.jetbrains.kotlin.utils.addToStdlib.UnsafeCastFunction
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

sealed interface Injectable {
  val type: TypeRef
  val originalType: TypeRef get() = type
  val dependencies: List<InjectableRequest> get() = emptyList()
  val dependencyScopes: Map<InjectableRequest, InjectablesScope> get() = emptyMap()
  val callableFqName: FqName
  val ownerScope: InjectablesScope
  val usageKey: Any get() = type
}

class CallableInjectable(
  override val ownerScope: InjectablesScope,
  val callable: CallableRef
) : Injectable {
  override val type: TypeRef
    get() = callable.type
  override val dependencies: List<InjectableRequest> = callable.getInjectableRequests(ownerScope.ctx)
  override val callableFqName: FqName = if (callable.callable is ClassConstructorDescriptor)
    callable.callable.constructedClass.fqNameSafe
  else callable.callable.fqNameSafe
  override val originalType: TypeRef
    get() = callable.originalType
  override val usageKey: Any =
    listOf(callable.callable.uniqueKey(ownerScope.ctx), callable.parameterTypes, callable.type)

  override fun equals(other: Any?): Boolean =
    other is CallableInjectable && other.usageKey == usageKey

  override fun hashCode(): Int = usageKey.hashCode()
}

class ListInjectable(
  override val type: TypeRef,
  override val ownerScope: InjectablesScope,
  elements: List<TypeRef>,
  val singleElementType: TypeRef,
  val collectionElementType: TypeRef
) : Injectable {
  override val callableFqName = FqName("listOf")
  override val dependencies: List<InjectableRequest> = elements
    .mapIndexed { index, element ->
      InjectableRequest(
        type = element,
        callableFqName = callableFqName,
        callableTypeArguments = type.classifier.typeParameters.zip(type.arguments).toMap(),
        parameterName = "element$index".asNameId(),
        parameterIndex = index
      )
    }
  override val dependencyScopes = dependencies.associateWith { ownerScope }
}

class ProviderInjectable(
  override val type: TypeRef,
  override val ownerScope: InjectablesScope,
  val isInline: Boolean
) : Injectable {
  override val callableFqName = FqName("providerOf")
  override val dependencies: List<InjectableRequest> = listOf(
    InjectableRequest(
      type = type.unwrapTags().arguments.last(),
      callableFqName = callableFqName,
      parameterName = "instance".asNameId(),
      parameterIndex = 0,
      isInline = isInline
    )
  )

  val parameterDescriptors = type
    .unwrapTags()
    .classifier
    .descriptor!!
    .cast<ClassDescriptor>()
    .unsubstitutedMemberScope
    .getContributedFunctions("invoke".asNameId(), NoLookupLocation.FROM_BACKEND)
    .first()
    .valueParameters
    .map { ProviderValueParameterDescriptor(it) }

  // only create a new scope if we have parameters
  override val dependencyScopes = mapOf(
    dependencies.single() to InjectableScopeOrParent(
      name = "PROVIDER $type",
      parent = ownerScope,
      ctx = ownerScope.ctx,
      initialInjectables = parameterDescriptors
        .mapIndexed { index, parameter ->
          parameter
            .toCallableRef(ownerScope.ctx)
            .copy(type = type.unwrapTags().arguments[index])
        }
    )
  )

  override val originalType: TypeRef
    get() = type.unwrapTags().classifier.defaultType

  // required to distinct between individual providers in codegen
  class ProviderValueParameterDescriptor(
    private val delegate: ValueParameterDescriptor
  ) : ValueParameterDescriptor by delegate
}

class SourceKeyInjectable(
  override val type: TypeRef,
  override val ownerScope: InjectablesScope
) : Injectable {
  override val callableFqName: FqName = FqName("com.ivianuu.injekt.common.sourceKey")
}

class TypeKeyInjectable(
  override val type: TypeRef,
  override val ownerScope: InjectablesScope
) : Injectable {
  override val callableFqName: FqName = FqName("com.ivianuu.injekt.common.typeKeyOf<${type.renderToString()}>")
  override val dependencies: List<InjectableRequest> = run {
    val typeParameterDependencies = mutableSetOf<ClassifierRef>()
    type.allTypes.forEach {
      if (it.classifier.isTypeParameter)
        typeParameterDependencies += it.classifier
    }
    typeParameterDependencies
      .mapIndexed { index, typeParameter ->
        InjectableRequest(
          type = ownerScope.ctx.typeKeyClassifier!!.defaultType
            .withArguments(listOf(typeParameter.defaultType)),
          callableFqName = callableFqName,
          callableTypeArguments = type.classifier.typeParameters.zip(type.arguments).toMap(),
          parameterName = "${typeParameter.fqName.shortName()}Key".asNameId(),
          parameterIndex = index
        )
      }
  }
}

fun CallableRef.getInjectableRequests(ctx: Context): List<InjectableRequest> = callable.allParametersWithContext
  .transform {
    if (it === callable.dispatchReceiverParameter ||
      it === callable.extensionReceiverParameter ||
      it in callable.contextReceiverParameters ||
      it.isProvide(ctx) ||
      parameterTypes[it.injektIndex(ctx)]?.isInject == true)
      add(it.toInjectableRequest(this@getInjectableRequests, ctx))
  }

data class InjectableRequest(
  val type: TypeRef,
  val callableFqName: FqName,
  val callableTypeArguments: Map<ClassifierRef, TypeRef> = emptyMap(),
  val parameterName: Name,
  val parameterIndex: Int,
  val isRequired: Boolean = true,
  val isInline: Boolean = false
)

fun ParameterDescriptor.toInjectableRequest(callable: CallableRef, ctx: Context) =
  InjectableRequest(
    type = callable.parameterTypes[injektIndex(ctx)]!!,
    callableFqName = containingDeclaration.safeAs<ConstructorDescriptor>()
      ?.constructedClass?.fqNameSafe ?: containingDeclaration.fqNameSafe,
    callableTypeArguments = callable.typeArguments,
    parameterName = injektName(ctx),
    parameterIndex = injektIndex(ctx),
    isRequired = this !is ValueParameterDescriptor || !hasDefaultValueIgnoringInject,
    isInline = callable.callable.safeAs<FunctionDescriptor>()?.isInline == true &&
        InlineUtil.isInlineParameter(this) &&
        safeAs<ValueParameterDescriptor>()?.isCrossinline != true
  )
