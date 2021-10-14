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

package com.ivianuu.injekt.compiler.resolution

import com.ivianuu.injekt.compiler.DISPATCH_RECEIVER_NAME
import com.ivianuu.injekt.compiler.InjektContext
import com.ivianuu.injekt.compiler.asNameId
import com.ivianuu.injekt.compiler.generateFrameworkKey
import com.ivianuu.injekt.compiler.hasAnnotation
import com.ivianuu.injekt.compiler.injectablesLookupName
import com.ivianuu.injekt.compiler.injektFqNames
import com.ivianuu.injekt.compiler.isIde
import com.ivianuu.injekt_shaded.Inject
import com.ivianuu.injekt_shaded.Provide
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitClassReceiver
import org.jetbrains.kotlin.utils.addToStdlib.cast

class InjectablesScope(
  val name: String,
  val parent: InjectablesScope?,
  @Inject @Provide val context: InjektContext,
  val callContext: CallContext,
  val ownerDescriptor: DeclarationDescriptor?,
  val file: KtFile?,
  val componentType: TypeRef? = null,
  val initialInjectables: List<CallableRef>,
  val injectablesPredicate: (CallableRef) -> Boolean = { true },
  imports: List<ResolvedProviderImport>,
  val typeParameters: List<ClassifierRef>,
  val nesting: Int
) {
  val chain: MutableList<Pair<InjectableRequest, Injectable>> = parent?.chain ?: mutableListOf()
  val resultsByType = mutableMapOf<TypeRef, ResolutionResult>()
  val resultsByCandidate = mutableMapOf<Injectable, ResolutionResult>()
  val typeScopes = mutableMapOf<TypeRefKey, InjectablesScope>()

  private val imports = imports.toMutableList()

  private val injectables = mutableListOf<CallableRef>()

  private val spreadingInjectables = mutableListOf<SpreadingInjectable>()
  private val spreadingInjectableCandidateTypes = mutableListOf<TypeRef>()

  private data class SpreadingInjectable(
    val callable: CallableRef,
    val constraintType: TypeRef = callable.typeParameters.single {
      it.isSpread
    }.defaultType.substitute(callable.typeArguments),
    val processedCandidateTypes: MutableSet<TypeRef> = mutableSetOf(),
    val resultingFrameworkKeys: MutableSet<Int> = mutableSetOf()
  ) {
    fun copy() = SpreadingInjectable(
      callable,
      constraintType,
      processedCandidateTypes.toMutableSet(),
      resultingFrameworkKeys.toMutableSet()
    )
  }

  val allScopes: List<InjectablesScope> = parent?.allScopes?.let { it + this } ?: listOf(this)

  private val allStaticTypeParameters = allScopes.flatMap { it.typeParameters }

  data class CallableRequestKey(val type: TypeRef, val staticTypeParameters: List<ClassifierRef>)

  private val injectablesByRequest = mutableMapOf<CallableRequestKey, List<CallableInjectable>>()

  private val setElementsByType = mutableMapOf<CallableRequestKey, List<TypeRef>>()

  private data class ProviderRequestKey(val type: TypeRef, val callContext: CallContext)

  private val providerInjectablesByRequest = mutableMapOf<ProviderRequestKey, ProviderInjectable>()
  private val setInjectablesByType = mutableMapOf<TypeRef, SetInjectable?>()

  private val componentTypes: MutableList<TypeRef> =
    parent?.componentTypes?.toMutableList() ?: mutableListOf()
  private val entryPointTypes: MutableList<TypeRef> =
    parent?.entryPointTypes?.toMutableList() ?: mutableListOf()

  val isTypeScope = name.startsWith("TYPE ")

  init {
    initialInjectables
      .forEach { injectable ->
        injectable.collectInjectables(
          scope = this,
          addImport = { importFqName, packageFqName ->
            this.imports += ResolvedProviderImport(
              null,
              "${importFqName}.*",
              packageFqName
            )
          },
          addInjectable = { callable ->
            injectables += callable
            val typeWithFrameworkKey = callable.type
              .copy(frameworkKey = generateFrameworkKey())
            injectables += callable.copy(type = typeWithFrameworkKey)
            spreadingInjectableCandidateTypes += typeWithFrameworkKey
          },
          addSpreadingInjectable = { callable ->
            spreadingInjectables += SpreadingInjectable(callable)
          },
          addComponent = { componentType ->
            componentTypes += componentType
            val typeWithFrameworkKey = componentType.copy(frameworkKey = generateFrameworkKey())
            spreadingInjectableCandidateTypes += typeWithFrameworkKey
          },
          addEntryPoint = { entryPointTypes += it }
        )
      }

    val hasSpreadingInjectables = spreadingInjectables.isNotEmpty()
    val hasSpreadingInjectableCandidates = spreadingInjectableCandidateTypes.isNotEmpty()
    if (parent != null) {
      spreadingInjectables.addAll(
        0,
        parent.spreadingInjectables
          .map { if (hasSpreadingInjectableCandidates) it.copy() else it }
      )
      spreadingInjectableCandidateTypes.addAll(0, parent.spreadingInjectableCandidateTypes)
    }

    if ((hasSpreadingInjectables && spreadingInjectableCandidateTypes.isNotEmpty()) ||
      (hasSpreadingInjectableCandidates && spreadingInjectables.isNotEmpty())
    ) {
      spreadingInjectableCandidateTypes
        .toList()
        .forEach { spreadInjectables(it) }
    }
  }

  fun recordLookup(lookupLocation: LookupLocation) {
    if (isIde) return
    parent?.recordLookup(lookupLocation)
    for (import in imports) {
      context.injektContext.memberScopeForFqName(import.packageFqName, lookupLocation)
        ?.recordLookup(
          injectablesLookupName(
            FqName(import.importPath!!.removeSuffix(".*")),
            import.packageFqName
          ),
          lookupLocation
        )
    }
  }

  fun injectablesForRequest(
    request: InjectableRequest,
    requestingScope: InjectablesScope
  ): List<Injectable> {
    // we return merged collections
    if (request.type.frameworkKey == 0 &&
      request.type.classifier == context.injektContext.setClassifier
    ) return emptyList()

    return injectablesForType(CallableRequestKey(request.type, requestingScope.allStaticTypeParameters))
      .filter { it.isValidObjectRequest(request) }
  }

  private fun injectablesForType(key: CallableRequestKey): List<CallableInjectable> {
    if (injectables.isEmpty())
      return parent?.injectablesForType(key) ?: emptyList()
    return injectablesByRequest.getOrPut(key) {
      val thisInjectables = injectables
        .mapNotNull { candidate ->
          if (candidate.type.frameworkKey != key.type.frameworkKey)
            return@mapNotNull null
          val context = candidate.buildContext(key.staticTypeParameters, key.type)
          if (!context.isOk)
            return@mapNotNull null
          val substitutionMap = context.fixedTypeVariables
          val finalCandidate = candidate.substitute(substitutionMap)
          CallableInjectable(
            key.type,
            finalCandidate.getInjectableRequests(),
            this,
            finalCandidate
          )
        }
      val parentInjectables = parent?.injectablesForType(key)
      if (parentInjectables != null) parentInjectables + thisInjectables
      else thisInjectables
    }
  }

  fun frameworkInjectablesForRequest(request: InjectableRequest): List<Injectable> {
    if (request.type.frameworkKey != 0) return emptyList()

    when {
      request.type.isProviderFunctionType -> {
        val finalCallContext = if (request.isInline) callContext
        else request.type.callContext
        return listOf(
          providerInjectablesByRequest.getOrPut(
            ProviderRequestKey(request.type, finalCallContext)
          ) {
            ProviderInjectable(
              type = request.type,
              ownerScope = this,
              dependencyCallContext = finalCallContext,
              isInline = request.isInline
            )
          }
        )
      }
      request.type.classifier == context.injektContext.setClassifier -> return listOfNotNull(
        setInjectablesByType.getOrPut(request.type) {
          val singleElementType = request.type.arguments[0]
          val collectionElementType = context.injektContext.collectionClassifier.defaultType
            .withArguments(listOf(singleElementType))

          var key = CallableRequestKey(request.type, allStaticTypeParameters)

          var elements = setElementsForType(singleElementType, collectionElementType, key) +
              frameworkSetElementsForType(singleElementType, collectionElementType, key)
          if (elements.isEmpty() && singleElementType.isProviderFunctionType) {
            val providerReturnType = singleElementType.arguments.last()
            key = CallableRequestKey(providerReturnType, allStaticTypeParameters)

            elements = (setElementsForType(
              providerReturnType, context.injektContext.collectionClassifier
                .defaultType.withArguments(listOf(providerReturnType)),
              key) + frameworkSetElementsForType(singleElementType, collectionElementType, key))
              .map { elementType ->
                singleElementType.copy(
                  arguments = singleElementType.arguments
                    .dropLast(1) + elementType
                )
              }
          }

          if (elements.isNotEmpty()) {
            val elementRequests = elements
              .mapIndexed { index, element ->
                InjectableRequest(
                  type = element,
                  isRequired = true,
                  callableFqName = FqName("com.ivianuu.injekt.injectSetOf<${request.type.arguments[0].renderToString()}>"),
                  parameterName = "element$index".asNameId(),
                  parameterIndex = index,
                  isInline = false,
                  isLazy = false
                )
              }
            SetInjectable(
              type = request.type,
              ownerScope = this,
              dependencies = elementRequests,
              singleElementType = singleElementType,
              collectionElementType = collectionElementType
            )
          } else null
        }
      )
      request.type.classifier.fqName == injektFqNames().typeKey ->
        return listOf(TypeKeyInjectable(request.type, this))
      request.type.classifier.fqName == injektFqNames().sourceKey ->
        return listOf(SourceKeyInjectable(request.type, this))
      request.type.classifier.isComponent ->
        return listOf(ComponentInjectable(request.type, this))
      else -> return emptyList()
    }
  }

  private fun setElementsForType(
    singleElementType: TypeRef,
    collectionElementType: TypeRef,
    key: CallableRequestKey
  ): List<TypeRef> {
    if (injectables.isEmpty())
      return parent?.setElementsForType(singleElementType, collectionElementType, key) ?: emptyList()
    return setElementsByType.getOrPut(key) {
      val thisElements: List<TypeRef> = injectables
        .mapNotNull { candidate ->
          if (candidate.type.frameworkKey != key.type.frameworkKey)
            return@mapNotNull null
          var context =
            candidate.buildContext(key.staticTypeParameters, singleElementType)
          if (!context.isOk) {
            context = candidate.buildContext(key.staticTypeParameters, collectionElementType)
          }
          if (!context.isOk) return@mapNotNull null
          val substitutionMap = context.fixedTypeVariables
          candidate.substitute(substitutionMap)
        }
        .map { callable ->
          val typeWithFrameworkKey = callable.type.copy(
            frameworkKey = generateFrameworkKey()
          )
          injectables += callable.copy(type = typeWithFrameworkKey)
          typeWithFrameworkKey
        }
      val parentElements = parent?.setElementsForType(singleElementType, collectionElementType, key)
      if (parentElements != null) parentElements + thisElements
      else thisElements
    }
  }

  private fun frameworkSetElementsForType(
    singleElementType: TypeRef,
    collectionElementType: TypeRef,
    key: CallableRequestKey
  ): List<TypeRef> {
    if (componentTypes.isEmpty()) return emptyList()
    return componentTypes
      .mapNotNull { candidate ->
        var context =
          candidate.buildContext(singleElementType, key.staticTypeParameters)
        if (!context.isOk) {
          context = candidate.buildContext(collectionElementType, key.staticTypeParameters)
        }
        if (!context.isOk) return@mapNotNull null
        val substitutionMap = context.fixedTypeVariables
        candidate.substitute(substitutionMap)
      }
      .map { componentType ->
        val typeWithFrameworkKey = componentType.copy(frameworkKey = generateFrameworkKey())
        componentTypes += typeWithFrameworkKey
        typeWithFrameworkKey
      }
  }

  fun entryPointsForType(componentType: TypeRef): List<TypeRef> = entryPointTypes
    .mapNotNull { candidate ->
      val context = candidate.classifier.entryPointComponentType!!
        .buildContext(componentType, allStaticTypeParameters)
      if (!context.isOk) return@mapNotNull null
      val substitutionMap = context.fixedTypeVariables
      candidate.substitute(substitutionMap)
    }

  private fun spreadInjectables(candidateType: TypeRef) {
    for (spreadingInjectable in spreadingInjectables.toList())
      spreadInjectables(spreadingInjectable, candidateType)
  }

  private fun spreadInjectables(
    spreadingInjectable: SpreadingInjectable,
    candidateType: TypeRef
  ) {
    if (candidateType.frameworkKey in spreadingInjectable.resultingFrameworkKeys) return
    if (candidateType in spreadingInjectable.processedCandidateTypes) return
    spreadingInjectable.processedCandidateTypes += candidateType
    val (context, substitutionMap) = buildContextForSpreadingInjectable(
      spreadingInjectable.constraintType,
      candidateType,
      allStaticTypeParameters
    )
    if (!context.isOk) return

    val newInjectableType = spreadingInjectable.callable.type
      .substitute(substitutionMap)
      .copy(frameworkKey = 0)
    val newInjectable = spreadingInjectable.callable
      .copy(
        type = newInjectableType,
        originalType = newInjectableType,
        parameterTypes = spreadingInjectable.callable.parameterTypes
          .mapValues { it.value.substitute(substitutionMap) },
        typeArguments = spreadingInjectable.callable
          .typeArguments
          .mapValues { it.value.substitute(substitutionMap) }
      )

    newInjectable.collectInjectables(
      scope = this,
      addImport = { importFqName, packageFqName ->
        this.imports += ResolvedProviderImport(
          null,
          "${importFqName}.*",
          packageFqName
        )
      },
      addInjectable = { newInnerInjectable ->
        val finalNewInnerInjectable = newInnerInjectable
          .copy(originalType = newInnerInjectable.type)
        injectables += finalNewInnerInjectable
        val newInnerInjectableWithFrameworkKey = finalNewInnerInjectable.copy(
          type = finalNewInnerInjectable.type.copy(
            frameworkKey = generateFrameworkKey()
              .also { spreadingInjectable.resultingFrameworkKeys += it }
          )
        )
        injectables += newInnerInjectableWithFrameworkKey
        spreadingInjectableCandidateTypes += newInnerInjectableWithFrameworkKey.type
        spreadInjectables(newInnerInjectableWithFrameworkKey.type)
      },
      addSpreadingInjectable = { newInnerInjectable ->
        val finalNewInnerInjectable = newInnerInjectable
          .copy(originalType = newInnerInjectable.type)
        val newSpreadingInjectable = SpreadingInjectable(finalNewInnerInjectable)
        spreadingInjectables += newSpreadingInjectable
        spreadingInjectableCandidateTypes
          .toList()
          .forEach { spreadInjectables(newSpreadingInjectable, it) }
      },
      addComponent = { newInnerComponentType ->
        val finalNewInnerComponentType = newInnerComponentType
        componentTypes += finalNewInnerComponentType
        val newInnerComponentTypeWithFrameworkKey = finalNewInnerComponentType.copy(
          frameworkKey = generateFrameworkKey()
            .also { spreadingInjectable.resultingFrameworkKeys += it }
        )
        componentTypes += newInnerComponentTypeWithFrameworkKey
        spreadingInjectableCandidateTypes += newInnerComponentTypeWithFrameworkKey
        spreadInjectables(newInnerComponentTypeWithFrameworkKey)
      },
      addEntryPoint = { TODO() }
    )
  }

  /**
   * We add implicit injectables for objects under some circumstances to allow
   * callables in it to resolve their dispatch receiver parameter
   * Here we ensure that the user cannot resolve such implicit object injectable if they are not
   * provided by the user
   */
  private fun Injectable.isValidObjectRequest(request: InjectableRequest): Boolean =
    !originalType.classifier.isObject ||
        request.parameterName == DISPATCH_RECEIVER_NAME ||
        (this !is CallableInjectable ||
            callable.callable !is ReceiverParameterDescriptor ||
            callable.callable.cast<ReceiverParameterDescriptor>()
              .value !is ImplicitClassReceiver ||
            originalType.classifier.descriptor!!.hasAnnotation(injektFqNames().provide))

  override fun toString(): String = "InjectablesScope($name)"
}
