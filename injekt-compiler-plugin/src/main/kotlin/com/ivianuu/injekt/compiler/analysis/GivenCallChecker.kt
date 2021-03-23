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

package com.ivianuu.injekt.compiler.analysis

import com.ivianuu.injekt.compiler.InjektContext
import com.ivianuu.injekt.compiler.InjektErrors
import com.ivianuu.injekt.compiler.InjektWritableSlices
import com.ivianuu.injekt.compiler.SourcePosition
import com.ivianuu.injekt.compiler.asNameId
import com.ivianuu.injekt.compiler.isIde
import com.ivianuu.injekt.compiler.resolution.CallableGivenNode
import com.ivianuu.injekt.compiler.resolution.GivenGraph
import com.ivianuu.injekt.compiler.resolution.GivenRequest
import com.ivianuu.injekt.compiler.resolution.HierarchicalResolutionScope
import com.ivianuu.injekt.compiler.resolution.ResolutionResult
import com.ivianuu.injekt.compiler.resolution.isGiven
import com.ivianuu.injekt.compiler.resolution.resolveRequests
import com.ivianuu.injekt.compiler.resolution.substitute
import com.ivianuu.injekt.compiler.resolution.toCallableRef
import com.ivianuu.injekt.compiler.resolution.toClassifierRef
import com.ivianuu.injekt.compiler.resolution.toTypeRef
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.incremental.KotlinLookupLocation
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.checkers.CallChecker
import org.jetbrains.kotlin.resolve.calls.checkers.CallCheckerContext
import org.jetbrains.kotlin.resolve.calls.model.DefaultValueArgument
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class GivenCallChecker(
    private val context: InjektContext,
    private val allowGivenCalls: (ModuleDescriptor) -> Boolean,
    private val bindingContextCollector: ((BindingContext) -> Unit)?
) : CallChecker {
    override fun check(
        resolvedCall: ResolvedCall<*>,
        reportOn: PsiElement,
        context: CallCheckerContext
    ) {
        bindingContextCollector?.invoke(context.trace.bindingContext)
        val resultingDescriptor = resolvedCall.resultingDescriptor
        if (resultingDescriptor !is FunctionDescriptor) return

        if (resultingDescriptor.valueParameters.none {
                it.isGiven(this.context, context.trace)
        }) return

        val substitutionMap = resolvedCall.typeArguments
            .mapKeys { it.key.toClassifierRef(this.context, context.trace) }
            .mapValues { it.value.toTypeRef(this.context, context.trace) }
            .filter { it.key != it.value.classifier }

        val callable = resultingDescriptor.toCallableRef(this.context, context.trace)
            .substitute(substitutionMap)

        val requests = callable.givenParameters
            .filter { parameterName ->
                val parameter = callable.callable.valueParameters.single {
                    it.name.asString() == parameterName
                }
                resolvedCall.valueArguments[parameter] is DefaultValueArgument
            }
            .map { parameterName ->
                val parameterDescriptor = resultingDescriptor.valueParameters
                    .single { it.name.asString() == parameterName }
                GivenRequest(
                    type = callable.parameterTypes[parameterName]!!,
                    isRequired = !parameterDescriptor.hasDefaultValueIgnoringGiven,
                    callableFqName = resultingDescriptor.fqNameSafe,
                    parameterName = parameterName.asNameId(),
                    isInline = resultingDescriptor
                        .safeAs<FunctionDescriptor>()?.isInline == true
                )
            }

        if (requests.isEmpty()) return

        val callExpression = resolvedCall.call.callElement

        if (!allowGivenCalls(resultingDescriptor.module) &&
                requests.all { it.isRequired }) {
            context.trace.report(
                InjektErrors.GIVEN_CALLS_NOT_ALLOWED
                    .on(callExpression)
            )
            return
        }

        // todo
        if (isIde) return

        val scope = HierarchicalResolutionScope(this.context, context.scope, context.trace)
        scope.recordLookup(KotlinLookupLocation(callExpression))

        when (val graph = scope.resolveRequests(requests)) {
            is GivenGraph.Success -> {
                val visited = mutableSetOf<ResolutionResult.Success>()
                fun ResolutionResult.Success.visit() {
                    if (this !is ResolutionResult.Success.WithCandidate.Value) return
                    if (!visited.add(this)) return
                    if (candidate is CallableGivenNode) {
                        context.trace.record(
                            InjektWritableSlices.USED_GIVEN,
                            candidate.callable.callable,
                            Unit
                        )
                        val existingUsedGivensForFile =
                            context.trace.bindingContext[InjektWritableSlices.USED_GIVENS_FOR_FILE,
                                    callExpression.containingKtFile] ?: emptyList()
                        context.trace.record(
                            InjektWritableSlices.USED_GIVENS_FOR_FILE,
                            callExpression.containingKtFile,
                            existingUsedGivensForFile + candidate.callable.callable
                        )
                    }
                    dependencyResults.forEach { it.value.visit() }
                }
                graph.results.forEach { it.value.visit() }

                context.trace.record(
                    InjektWritableSlices.GIVEN_GRAPH,
                    SourcePosition(
                        callExpression.containingKtFile.virtualFilePath,
                        callExpression.startOffset,
                        callExpression.endOffset
                    ),
                    graph
                )
            }
            is GivenGraph.Error -> context.trace.report(
                InjektErrors.UNRESOLVED_GIVEN
                    .on(reportOn, graph)
            )
        }
    }
}