/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.injekt.compiler.resolution

import com.ivianuu.injekt.compiler.InjektFqNames
import com.ivianuu.injekt.compiler.isExternalDeclaration
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.resolve.descriptorUtil.overriddenTreeUniqueAsSequence
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import java.util.*
import kotlin.reflect.KClass

sealed interface InjectionResult {
  val scope: InjectablesScope
  val callee: CallableRef

  data class Success(
    override val scope: InjectablesScope,
    override val callee: CallableRef,
    val results: Map<InjectableRequest, ResolutionResult.Success>,
    val usages: Map<UsageKey, MutableSet<InjectableRequest>>
  ) : InjectionResult

  data class Error(
    override val scope: InjectablesScope,
    override val callee: CallableRef,
    val failureRequest: InjectableRequest,
    val failure: ResolutionResult.Failure
  ) : InjectionResult
}

sealed interface ResolutionResult {
  sealed interface Success : ResolutionResult {
    object DefaultValue : Success

    data class Value(
      val candidate: Injectable,
      val scope: InjectablesScope,
      val dependencyResults: Map<InjectableRequest, Success>
    ) : Success {
      val highestScope: InjectablesScope = run {
        val anchorScopes = mutableSetOf<InjectablesScope>()

        fun collectScopesRecursive(result: Value) {
          if (result.candidate is CallableInjectable &&
            result.candidate.ownerScope.typeScopeType == null)
            anchorScopes += result.candidate.ownerScope
          for (dependency in result.dependencyResults.values)
            if (dependency is Value)
              collectScopesRecursive(dependency)
        }

        collectScopesRecursive(this)

        scope.allScopes
          .sortedBy { it.nesting }
          .firstOrNull { candidateScope ->
            candidateScope.isDeclarationContainer &&
                anchorScopes.all {
                  candidateScope.canSeeInjectablesOf(it) ||
                      candidateScope.canSeeInjectablesOf(scope)
                } &&
                candidateScope.callContext.canCall(candidate.callContext)
          } ?: scope
      }

      val usageKey = UsageKey(candidate.usageKey, candidate::class, highestScope)
    }
  }

  sealed interface Failure : ResolutionResult {
    val failureOrdering: Int

    sealed interface WithCandidate : Failure {
      val candidate: Injectable

      data class CallContextMismatch(
        val actualCallContext: CallContext,
        override val candidate: Injectable,
      ) : WithCandidate {
        override val failureOrdering: Int
          get() = 1
      }

      data class DivergentInjectable(override val candidate: Injectable) : WithCandidate {
        override val failureOrdering: Int
          get() = 2
      }

      data class ReifiedTypeArgumentMismatch(
        val parameter: ClassifierRef,
        val argument: ClassifierRef,
        override val candidate: Injectable
      ) : WithCandidate {
        override val failureOrdering: Int
          get() = 1
      }

      data class DependencyFailure(
        override val candidate: Injectable,
        val dependencyRequest: InjectableRequest,
        val dependencyFailure: Failure,
      ) : WithCandidate {
        override val failureOrdering: Int
          get() = 1
      }
    }

    data class CandidateAmbiguity(
      val request: InjectableRequest,
      val candidateResults: List<Success.Value>
    ) : Failure {
      override val failureOrdering: Int
        get() = 0
    }

    data class NoCandidates(
      val scope: InjectablesScope,
      val request: InjectableRequest
    ) : Failure {
      override val failureOrdering: Int
        get() = 3
    }
  }
}

private fun InjectablesScope.canSeeInjectablesOf(other: InjectablesScope): Boolean =
  other in allScopes

data class UsageKey(
  val key: Any,
  val type: KClass<out Injectable>,
  val highestScope: InjectablesScope
)

fun InjectablesScope.resolveRequests(
  callee: CallableRef,
  requests: List<InjectableRequest>,
  lookupLocation: LookupLocation,
  lookups: MutableSet<String>,
  onEachResult: (InjectableRequest, ResolutionResult) -> Unit
): InjectionResult {
  recordLookup(lookupLocation, lookups)
  val successes = mutableMapOf<InjectableRequest, ResolutionResult.Success>()
  var failureRequest: InjectableRequest? = null
  var failure: ResolutionResult.Failure? = null
  for (request in requests) {
    when (val result = resolveRequest(request, lookupLocation, lookups, false)) {
      is ResolutionResult.Success -> successes[request] = result
      is ResolutionResult.Failure ->
        if (request.isRequired || result.unwrapDependencyFailure() is ResolutionResult.Failure.CandidateAmbiguity) {
          if (compareResult(result, failure) < 0) {
            failureRequest = request
            failure = result
          }
        } else {
          successes[request] = ResolutionResult.Success.DefaultValue
        }
    }
  }
  val usages = mutableMapOf<UsageKey, MutableSet<InjectableRequest>>()
  return if (failure == null) InjectionResult.Success(
    this,
    callee,
    successes,
    usages
  ).also { it.postProcess(onEachResult, usages) }
  else InjectionResult.Error(
    this,
    callee,
    failureRequest!!,
    failure
  )
}

private fun InjectablesScope.resolveRequest(
  request: InjectableRequest,
  lookupLocation: LookupLocation,
  lookups: MutableSet<String>,
  fromTypeScope: Boolean
): ResolutionResult {
  if (request.type.hasErrors)
    return ResolutionResult.Failure.NoCandidates(this, request)

  if (scopeToUse != this)
    return scopeToUse.resolveRequest(request, lookupLocation, lookups, fromTypeScope)

  resultsByType[request.type]?.let { return it }

  val result: ResolutionResult = tryToResolveRequestWithUserInjectables(request, lookupLocation, lookups)
    .let { userResult ->
      if (userResult is ResolutionResult.Success ||
          userResult is ResolutionResult.Failure.CandidateAmbiguity)
            userResult
      else if (!fromTypeScope) {
        tryToResolveRequestInTypeScope(request, lookupLocation, lookups)
          ?.takeUnless { it is ResolutionResult.Failure.NoCandidates }
          .let { typeScopeResult ->
            when (typeScopeResult) {
              is ResolutionResult.Failure.CandidateAmbiguity -> typeScopeResult
              is ResolutionResult.Failure.WithCandidate.DivergentInjectable -> userResult
              else -> if (compareResult(userResult, typeScopeResult) < 0) userResult else typeScopeResult
            }
          }
          ?: tryToResolveRequestWithFrameworkInjectable(request, lookupLocation, lookups)
          ?: userResult
      } else userResult
    } ?: ResolutionResult.Failure.NoCandidates(this, request)

  resultsByType[request.type] = result
  return result
}

private fun InjectablesScope.tryToResolveRequestWithUserInjectables(
  request: InjectableRequest,
  lookupLocation: LookupLocation,
  lookups: MutableSet<String>
): ResolutionResult? = injectablesForRequest(request, this)
  .takeIf { it.isNotEmpty() }
  ?.let { resolveCandidates(request, it, lookupLocation, lookups) }

private fun InjectablesScope.tryToResolveRequestInTypeScope(
  request: InjectableRequest,
  lookupLocation: LookupLocation,
  lookups: MutableSet<String>
): ResolutionResult? =
  // try the type scope if the requested type is not a framework type
  if (request.type.frameworkKey.isEmpty() &&
    !request.type.isFunctionType &&
    request.type.classifier != ctx.listClassifier &&
    request.type.classifier.fqName != InjektFqNames.TypeKey &&
    request.type.classifier.fqName != InjektFqNames.SourceKey) {
    TypeInjectablesScope(request.type, this, ctx)
      .also { it.recordLookup(lookupLocation, lookups) }
      .takeUnless { it.isEmpty }
      ?.resolveRequest(request, lookupLocation, lookups, true)
  } else null

private fun InjectablesScope.tryToResolveRequestWithFrameworkInjectable(
  request: InjectableRequest,
  lookupLocation: LookupLocation,
  lookups: MutableSet<String>
): ResolutionResult? =
  frameworkInjectableForRequest(request)?.let { resolveCandidate(request, it, lookupLocation, lookups) }

private fun InjectablesScope.computeForCandidate(
  request: InjectableRequest,
  candidate: Injectable,
  compute: () -> ResolutionResult,
): ResolutionResult {
  resultsByCandidate[candidate]?.let { return it }

  if (candidate.dependencies.isEmpty())
    return compute().also { resultsByCandidate[candidate] = it }

  if (chain.isNotEmpty()) {
    for (i in chain.lastIndex downTo 0) {
      val prev = chain[i]

      if (prev.second.callableFqName == candidate.callableFqName &&
        prev.second.type.coveringSet == candidate.type.coveringSet &&
        (prev.second.type.typeSize < candidate.type.typeSize ||
            prev.second.type == candidate.type)) {
        val result = ResolutionResult.Failure.WithCandidate.DivergentInjectable(candidate)
        resultsByCandidate[candidate] = result
        return result
      }
    }
  }

  val pair = request to candidate
  chain += pair
  val result = compute()
  resultsByCandidate[candidate] = result
  chain -= pair
  return result
}

private fun InjectablesScope.resolveCandidates(
  request: InjectableRequest,
  candidates: List<Injectable>,
  lookupLocation: LookupLocation,
  lookups: MutableSet<String>
): ResolutionResult {
  if (candidates.size == 1) {
    val candidate = candidates.single()
    return resolveCandidate(request, candidate, lookupLocation, lookups)
  }

  val successes = mutableListOf<ResolutionResult.Success>()
  var failure: ResolutionResult.Failure? = null
  val remaining = candidates
    .sortedWith { a, b -> compareCandidate(a, b) }
    .distinctBy {
      if (it is CallableInjectable) it.usageKey
      else it
    }
    .toCollection(LinkedList())
  while (remaining.isNotEmpty()) {
    val candidate = remaining.removeFirst()
    if (compareCandidate(
        successes.firstOrNull()
          ?.safeAs<ResolutionResult.Success.Value>()?.candidate, candidate
      ) < 0
    ) {
      // we cannot get a better result
      break
    }

    when (val candidateResult = resolveCandidate(request, candidate, lookupLocation, lookups)) {
      is ResolutionResult.Success -> {
        val firstSuccessResult = successes.firstOrNull()
        when (compareResult(candidateResult, firstSuccessResult)) {
          -1 -> {
            successes.clear()
            successes += candidateResult
          }
          0 -> successes += candidateResult
        }
      }
      is ResolutionResult.Failure -> {
        if (compareResult(candidateResult, failure) < 0)
          failure = candidateResult
      }
    }
  }

  return when {
    successes.size == 1 -> successes.single()
    successes.isNotEmpty() -> ResolutionResult.Failure.CandidateAmbiguity(request, successes.cast())
    else -> failure!!
  }
}

private fun InjectablesScope.resolveCandidate(
  request: InjectableRequest,
  candidate: Injectable,
  lookupLocation: LookupLocation,
  lookups: MutableSet<String>
): ResolutionResult = computeForCandidate(request, candidate) {
  if (!callContext.canCall(candidate.callContext))
    return@computeForCandidate ResolutionResult.Failure.WithCandidate.CallContextMismatch(callContext, candidate)

  if (candidate is CallableInjectable) {
    for ((typeParameter, typeArgument) in candidate.callable.typeArguments) {
      val argumentDescriptor = typeArgument.classifier.descriptor as? TypeParameterDescriptor
        ?: continue
      val parameterDescriptor = typeParameter.descriptor as TypeParameterDescriptor
      if (parameterDescriptor.isReified && !argumentDescriptor.isReified) {
        return@computeForCandidate ResolutionResult.Failure.WithCandidate.ReifiedTypeArgumentMismatch(
          typeParameter,
          typeArgument.classifier,
          candidate
        )
      }
    }
  }

  if (candidate.dependencies.isEmpty())
    return@computeForCandidate ResolutionResult.Success.Value(
      candidate,
      this,
      emptyMap()
    )

  val successDependencyResults = mutableMapOf<InjectableRequest, ResolutionResult.Success>()
  for (dependency in candidate.dependencies) {
    val dependencyScope = candidate.dependencyScopes[dependency] ?: this
    when (val dependencyResult = dependencyScope.resolveRequest(dependency, lookupLocation, lookups, false)) {
      is ResolutionResult.Success -> successDependencyResults[dependency] = dependencyResult
      is ResolutionResult.Failure -> {
        when {
          dependency.isRequired && candidate is ProviderInjectable &&
              dependencyResult is ResolutionResult.Failure.NoCandidates ->
            return@computeForCandidate ResolutionResult.Failure.NoCandidates(dependencyScope, dependency)
          dependency.isRequired ||
              dependencyResult.unwrapDependencyFailure() is ResolutionResult.Failure.CandidateAmbiguity ->
            return@computeForCandidate ResolutionResult.Failure.WithCandidate.DependencyFailure(
              candidate,
              dependency,
              dependencyResult
            )
          else -> successDependencyResults[dependency] = ResolutionResult.Success.DefaultValue
        }
      }
    }
  }
  return@computeForCandidate ResolutionResult.Success.Value(
    candidate,
    this,
    successDependencyResults
  )
}

private fun InjectablesScope.compareResult(a: ResolutionResult?, b: ResolutionResult?): Int {
  if (a === b) return 0

  if (a != null && b == null) return -1
  if (b != null && a == null) return 1
  if (a == null && b == null) return 0
  a!!
  b!!

  if (a is ResolutionResult.Success && b !is ResolutionResult.Success) return -1
  if (b is ResolutionResult.Success && a !is ResolutionResult.Success) return 1

  if (a is ResolutionResult.Success && b is ResolutionResult.Success) {
    if (a !is ResolutionResult.Success.DefaultValue &&
      b is ResolutionResult.Success.DefaultValue
    ) return -1
    if (b !is ResolutionResult.Success.DefaultValue &&
      a is ResolutionResult.Success.DefaultValue
    ) return 1

    if (a is ResolutionResult.Success.Value &&
      b is ResolutionResult.Success.Value
    )
      return compareCandidate(a.candidate, b.candidate)

    return 0
  } else {
    a as ResolutionResult.Failure
    b as ResolutionResult.Failure

    return a.failureOrdering.compareTo(b.failureOrdering)
  }
}

private fun InjectablesScope.compareCandidate(a: Injectable?, b: Injectable?): Int {
  if (a === b) return 0

  if (a != null && b == null) return -1
  if (b != null && a == null) return 1

  a!!
  b!!

  val aIsFromTypeScope = a.ownerScope.typeScopeType != null
  val bIsFromTypeScope = b.ownerScope.typeScopeType != null
  if (!aIsFromTypeScope && bIsFromTypeScope) return -1
  if (!bIsFromTypeScope && aIsFromTypeScope) return 1

  val aScopeNesting = a.ownerScope.nesting
  val bScopeNesting = b.ownerScope.nesting
  if (aScopeNesting > bScopeNesting) return -1
  if (bScopeNesting > aScopeNesting) return 1

  return compareCallable(
    a.safeAs<CallableInjectable>()?.callable,
    b.safeAs<CallableInjectable>()?.callable
  )
}

private fun InjectablesScope.compareCallable(
  a: CallableRef?,
  b: CallableRef?
): Int {
  if (a == b) return 0

  if (a != null && b == null) return -1
  if (b != null && a == null) return 1
  a!!
  b!!

  val ownerA = a.callable.containingDeclaration
  val ownerB = b.callable.containingDeclaration
  if (ownerA == ownerB) {
    val aSubClassNesting = a.callable
      .overriddenTreeUniqueAsSequence(false).count().dec()
    val bSubClassNesting = b.callable
      .overriddenTreeUniqueAsSequence(false).count().dec()

    if (aSubClassNesting < bSubClassNesting) return -1
    if (bSubClassNesting < aSubClassNesting) return 1
  }

  val diff = compareType(a.originalType, b.originalType)
  if (diff < 0) return -1
  if (diff > 0) return 1

  if (a.chainLength < b.chainLength)
    return -1
  if (b.chainLength < a.chainLength)
    return 1

  return 0
}

private fun InjectablesScope.compareType(
  a: TypeRef?,
  b: TypeRef?,
  comparedTypes: MutableSet<Pair<TypeRef, TypeRef>> = mutableSetOf()
): Int {
  if (a == b) return 0

  if (a != null && b == null) return -1
  if (b != null && a == null) return 1
  a!!
  b!!

  if (!a.isStarProjection && b.isStarProjection) return -1
  if (a.isStarProjection && !b.isStarProjection) return 1

  if (!a.isMarkedNullable && b.isMarkedNullable) return -1
  if (!b.isMarkedNullable && a.isMarkedNullable) return 1

  if (!a.classifier.isTypeParameter && b.classifier.isTypeParameter) return -1
  if (a.classifier.isTypeParameter && !b.classifier.isTypeParameter) return 1

  val pair = a to b
  if (!comparedTypes.add(pair)) return 0

  fun compareSameClassifier(a: TypeRef?, b: TypeRef?): Int {
    if (a == b) return 0

    if (a != null && b == null) return -1
    if (b != null && a == null) return 1
    a!!
    b!!

    var diff = 0
    a.arguments.zip(b.arguments).forEach { (aTypeArgument, bTypeArgument) ->
      diff += compareType(aTypeArgument, bTypeArgument, comparedTypes)
    }
    if (diff < 0) return -1
    if (diff > 0) return 1
    return 0
  }

  if (a.classifier != b.classifier) {
    val aSubTypeOfB = a.isSubTypeOf(b, ctx)
    val bSubTypeOfA = b.isSubTypeOf(a, ctx)
    if (aSubTypeOfB && !bSubTypeOfA) return -1
    if (bSubTypeOfA && !aSubTypeOfB) return 1
    val aCommonSuperType = commonSuperType(a.superTypes, ctx = ctx)
    val bCommonSuperType = commonSuperType(b.superTypes, ctx = ctx)
    val diff = compareType(aCommonSuperType, bCommonSuperType, comparedTypes)
    if (diff < 0) return -1
    if (diff > 0) return 1
  } else {
    val diff = compareSameClassifier(a, b)
    if (diff < 0) return -1
    if (diff > 0) return 1
  }

  return 0
}

private fun InjectionResult.Success.postProcess(
  onEachResult: (InjectableRequest, ResolutionResult) -> Unit,
  usages: MutableMap<UsageKey, MutableSet<InjectableRequest>>
) {
  visitRecursive { request, result ->
    if (result is ResolutionResult.Success.Value)
      usages.getOrPut(result.usageKey) { mutableSetOf() } += request
    onEachResult(request, result)
  }
}

fun ResolutionResult.visitRecursive(
  request: InjectableRequest,
  action: (InjectableRequest, ResolutionResult) -> Unit
) {
  action(request, this)
  if (this is ResolutionResult.Success.Value) {
    dependencyResults
      .forEach { (request, result) ->
        result.visitRecursive(request, action)
      }
  }
}

fun InjectionResult.visitRecursive(action: (InjectableRequest, ResolutionResult) -> Unit) {
  val results = when (this) {
    is InjectionResult.Success -> results
    is InjectionResult.Error -> mapOf(failureRequest to failure)
  }

  for ((request, result) in results)
    result.visitRecursive(request, action)
}

private fun ResolutionResult.Failure.unwrapDependencyFailure(): ResolutionResult.Failure =
  if (this is ResolutionResult.Failure.WithCandidate.DependencyFailure)
    dependencyFailure.unwrapDependencyFailure()
  else this