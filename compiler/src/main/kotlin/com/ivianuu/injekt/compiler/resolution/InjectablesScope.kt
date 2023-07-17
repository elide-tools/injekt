/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.injekt.compiler.resolution

import com.ivianuu.injekt.compiler.Context
import com.ivianuu.injekt.compiler.InjektFqNames
import org.jetbrains.kotlin.psi.KtElement

class InjectablesScope(
  val name: String,
  val parent: InjectablesScope?,
  val owner: KtElement? = null,
  val initialInjectables: List<CallableRef> = emptyList(),
  val injectablesPredicate: (CallableRef) -> Boolean = { true },
  val typeParameters: List<ClassifierRef> = emptyList(),
  val nesting: Int = parent?.nesting?.inc() ?: 0,
  val ctx: Context
) {
  val resolutionChain: MutableList<Injectable> = parent?.resolutionChain ?: mutableListOf()
  val resultsByType = mutableMapOf<TypeRef, ResolutionResult>()
  val resultsByCandidate = mutableMapOf<Injectable, ResolutionResult>()

  private val injectables = mutableListOf<CallableRef>()
  private val allInjectables get() = allScopes.flatMap { injectables }

  private val spreadingInjectables: MutableList<CallableRef> =
    parent?.spreadingInjectables?.toMutableList() ?: mutableListOf()

  val allScopes: List<InjectablesScope> = parent?.allScopes?.let { it + this } ?: listOf(this)

  val allStaticTypeParameters = allScopes.flatMap { it.typeParameters }

  data class CallableRequestKey(val type: TypeRef, val staticTypeParameters: List<ClassifierRef>)
  private val injectablesByRequest = mutableMapOf<CallableRequestKey, List<CallableInjectable>>()

  init {
    for (injectable in initialInjectables)
      injectable.collectInjectables(
        scope = this,
        addInjectable = { injectables += it },
        addSpreadingInjectable = { spreadingInjectables += it },
        ctx = ctx
      )

    spreadingInjectables.toList().forEach { spreadInjectables(it) }
  }

  fun injectablesForRequest(
    request: InjectableRequest,
    requestingScope: InjectablesScope
  ): List<Injectable> {
    // we return merged collections
    if (request.type.frameworkKey.isEmpty() &&
      request.type.classifier == ctx.listClassifier) return emptyList()

    return injectablesForType(
      CallableRequestKey(request.type, requestingScope.allStaticTypeParameters)
    ).filter { injectable -> allScopes.all { it.injectablesPredicate(injectable.callable) } }
  }

  private fun injectablesForType(key: CallableRequestKey): List<CallableInjectable> {
    if (injectables.isEmpty())
      return parent?.injectablesForType(key) ?: emptyList()
    return injectablesByRequest.getOrPut(key) {
      buildList {
        parent?.injectablesForType(key)?.let { addAll(it) }

        for (candidate in injectables) {
          if (key.type.frameworkKey.isNotEmpty() &&
            candidate.type.frameworkKey != key.type.frameworkKey) continue
          val context = candidate.type.buildContext(key.type, key.staticTypeParameters, ctx = ctx)
          if (!context.isOk) continue
          this += CallableInjectable(
            this@InjectablesScope,
            candidate.substitute(context.fixedTypeVariables),
            key.type
          )
        }
      }
    }
  }

  fun frameworkInjectableForRequest(request: InjectableRequest): Injectable? = when {
    request.type.isFunctionType && !request.type.isProvide -> LambdaInjectable(this, request)
    request.type.classifier == ctx.listClassifier -> {
      val singleElementType = request.type.arguments[0]
      val collectionElementType = ctx.collectionClassifier.defaultType
        .withArguments(listOf(singleElementType))

      val key = CallableRequestKey(request.type, allStaticTypeParameters)

      val elements = listElementsTypesForType(singleElementType, collectionElementType, key)

      if (elements.isEmpty()) null
      else ListInjectable(
        type = request.type,
        ownerScope = this,
        elements = elements,
        singleElementType = singleElementType,
        collectionElementType = collectionElementType
      )
    }
    request.type.classifier.fqName == InjektFqNames.TypeKey ->
      TypeKeyInjectable(request.type, this)
    request.type.classifier.fqName == InjektFqNames.SourceKey ->
      SourceKeyInjectable(request.type, this)
    else -> null
  }

  private fun listElementsTypesForType(
    singleElementType: TypeRef,
    collectionElementType: TypeRef,
    key: CallableRequestKey
  ): List<TypeRef> {
    if (injectables.isEmpty())
      return parent?.listElementsTypesForType(singleElementType, collectionElementType, key) ?: emptyList()

    return buildList {
      parent?.listElementsTypesForType(singleElementType, collectionElementType, key)
        ?.let { addAll(it) }

      for (candidate in injectables.toList()) {
        var context =
          candidate.type.buildContext(singleElementType, key.staticTypeParameters, ctx = ctx)
        if (!context.isOk)
          context = candidate.type.buildContext(collectionElementType, key.staticTypeParameters, ctx = ctx)
        if (!context.isOk) continue

        val substitutedCandidate = candidate.substitute(context.fixedTypeVariables)

        add(substitutedCandidate.type)
      }
    }
  }

  private fun spreadInjectables(spreadingInjectable: CallableRef) {
    for (candidate in allInjectables.toList())
      spreadInjectables(spreadingInjectable, candidate.type)
  }

  private fun spreadInjectables(spreadingInjectable: CallableRef, candidateType: TypeRef) {
    val context = buildContextForSpreadingInjectable(
      spreadingInjectable.typeParameters.single { it.isSpread }
        .defaultType.substitute(spreadingInjectable.typeArguments),
      candidateType,
      allStaticTypeParameters,
      ctx
    )
    if (!context.isOk) return

    val substitutedInjectable = spreadingInjectable
      .copy(
        type = spreadingInjectable.type
          .substitute(context.fixedTypeVariables),
        parameterTypes = spreadingInjectable.parameterTypes
          .mapValues { it.value.substitute(context.fixedTypeVariables) },
        typeArguments = spreadingInjectable
          .typeArguments
          .mapValues { it.value.substitute(context.fixedTypeVariables) }
      )

    substitutedInjectable.collectInjectables(
      scope = this,
      addInjectable = { next ->
        injectables += next
        for (innerSpreadingInjectable in spreadingInjectables.toList())
          spreadInjectables(innerSpreadingInjectable, next.type)
      },
      addSpreadingInjectable = { spreadingInjectables += it },
      ctx = ctx
    )
  }

  override fun toString(): String = "InjectablesScope($name)"
}
