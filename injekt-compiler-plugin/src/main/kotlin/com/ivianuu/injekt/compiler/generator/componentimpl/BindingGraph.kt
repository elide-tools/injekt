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

package com.ivianuu.injekt.compiler.generator.componentimpl

import com.ivianuu.injekt.Assisted
import com.ivianuu.injekt.Binding
import com.ivianuu.injekt.compiler.generator.Callable
import com.ivianuu.injekt.compiler.generator.DeclarationStore
import com.ivianuu.injekt.compiler.generator.ModuleDescriptor
import com.ivianuu.injekt.compiler.generator.TypeRef
import com.ivianuu.injekt.compiler.generator.TypeTranslator
import com.ivianuu.injekt.compiler.generator.asNameId
import com.ivianuu.injekt.compiler.generator.callableKind
import com.ivianuu.injekt.compiler.generator.defaultType
import com.ivianuu.injekt.compiler.generator.getSubstitutionMap
import com.ivianuu.injekt.compiler.generator.isAssignable
import com.ivianuu.injekt.compiler.generator.nonInlined
import com.ivianuu.injekt.compiler.generator.render
import com.ivianuu.injekt.compiler.generator.replaceTypeParametersWithStars
import com.ivianuu.injekt.compiler.generator.substitute
import com.ivianuu.injekt.compiler.generator.substituteStars
import com.ivianuu.injekt.compiler.generator.uniqueTypeName
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

@Binding
class BindingGraph(
    private val owner: @Assisted ComponentImpl,
    collectionsFactory: (ComponentImpl, BindingCollections?) -> BindingCollections,
    private val declarationStore: DeclarationStore,
    private val componentImplFactory: (
        TypeRef,
        Name,
        List<TypeRef>,
        List<Callable>,
        ComponentImpl?,
    ) -> ComponentImpl,
    private val moduleDescriptor: org.jetbrains.kotlin.descriptors.ModuleDescriptor,
    private val typeTranslator: TypeTranslator
) {

    private val parent = owner.parent?.graph

    private val moduleBindingCallables = mutableListOf<CallableWithReceiver>()
    private val parentModuleBindingCallables = owner.parent?.graph?.moduleBindingCallables
        ?: emptyList()

    private val collections: BindingCollections = collectionsFactory(owner, parent?.collections)

    val resolvedBindings = mutableMapOf<TypeRef, BindingNode>()
    private val checkedBindings = mutableSetOf<BindingNode>()

    private val chain = mutableListOf<BindingNode>()
    private var locked = false

    init {
        fun ModuleDescriptor.collectContributions(parentAccessExpression: ComponentExpression?) {
            for (callable in callables) {
                if (callable.contributionKind == null) continue
                val finalCallable = if (callable.visibility == Visibilities.PROTECTED) {
                    val accessorName = "_${callable.name}".asNameId()
                    owner.members += ComponentCallable(
                        name = accessorName,
                        type = callable.type,
                        isProperty = !callable.isCall,
                        callableKind = callable.callableKind,
                        initializer = null,
                        body = {
                            emit("${callable.name}")
                            if (callable.isCall) {
                                emit("(")
                                callable.valueParameters.forEachIndexed { index, parameter ->
                                    emit(parameter.name)
                                    if (index != callable.valueParameters.lastIndex) emit(", ")
                                }
                                emit(")")
                            }
                        },
                        isMutable = false,
                        isOverride = false,
                        isInline = false,
                        canBePrivate = true,
                        valueParameters = callable.valueParameters
                            .map {
                                ComponentCallable.ValueParameter(
                                    it.name,
                                    it.type
                                )
                            },
                        typeParameters = callable.typeParameters
                            .map {
                                ComponentCallable.TypeParameter(
                                    it.fqName.shortName(),
                                    it.superTypes
                                )
                            }
                    )
                    callable.copy(
                        name = accessorName,
                        visibility = Visibilities.INTERNAL
                    )
                } else {
                    callable
                }
                when (finalCallable.contributionKind!!) {
                    Callable.ContributionKind.BINDING -> moduleBindingCallables += CallableWithReceiver(
                        finalCallable,
                        parentAccessExpression,
                        owner,
                        emptyMap()
                    )
                    Callable.ContributionKind.MAP_ENTRIES -> {
                        collections.addMapEntries(
                            CallableWithReceiver(
                                finalCallable,
                                parentAccessExpression,
                                owner,
                                emptyMap()
                            )
                        )
                    }
                    Callable.ContributionKind.SET_ELEMENTS -> {
                        collections.addSetElements(
                            CallableWithReceiver(
                                finalCallable,
                                parentAccessExpression,
                                owner,
                                emptyMap()
                            )
                        )
                    }
                    Callable.ContributionKind.MODULE -> {
                        declarationStore.moduleForType(finalCallable.type)
                            .collectContributions(parentAccessExpression.child(finalCallable))
                    }
                }.let {}
            }
        }

        declarationStore.moduleForType(owner.componentType)
            .collectContributions(null)
        owner.mergeDeclarations
            .filter { it.isModule }
            .map { declarationStore.moduleForType(it) }
            .onEach { includedModule ->
                if (includedModule.type.classifier.isObject) {
                    includedModule.collectContributions {
                        emit("${includedModule.type.classifier.fqName}")
                    }
                } else {
                    val callable = ComponentCallable(
                        name = includedModule.type.uniqueTypeName(),
                        isOverride = false,
                        type = includedModule.type,
                        body = null,
                        isProperty = true,
                        callableKind = Callable.CallableKind.DEFAULT,
                        initializer = {
                            emit("${includedModule.type.classifier.fqName}")
                            if (!includedModule.type.classifier.isObject)
                                emit("()")
                        },
                        isMutable = false,
                        isInline = false,
                        canBePrivate = true,
                        valueParameters = emptyList(),
                        typeParameters = emptyList()
                    ).also { owner.members += it }

                    includedModule.collectContributions {
                        emit("${callable.name}")
                    }
                }
            }
    }

    private fun ComponentExpression?.child(callable: Callable): ComponentExpression = {
        if (this@child != null) {
            this@child()
            emit(".")
        }
        emit("${callable.name}")
        if (callable.isCall) emit("()")
    }

    fun checkRequests(requests: List<BindingRequest>) {
        requests.forEach { check(it) }
        requests.forEach { request ->
            val binding = getBinding(request)
            if (binding.callableKind != Callable.CallableKind.DEFAULT &&
                binding.callableKind != request.callableKind) {
                error("Call context mismatch. '${request.origin.orUnknown()}' is a ${request.callableKind.name} callable but " +
                        "dependency '${binding.origin.orUnknown()}' is a ${binding.callableKind.name} callable.")
            }
        }
        postProcess()
        locked = true
    }

    // temporary function because it's not possible to properly inline '*' star types
    // with our current binding resolution algorithm.
    // we collect all duplicated bindings and rewrite the graph to point to a single binding
    private fun postProcess() {
        class MergeBindingGroup(
            val type: TypeRef,
            val bindingToUse: BindingNode
        ) {
            val keysToReplace = mutableListOf<TypeRef>()
        }

        val bindingGroups = mutableListOf<MergeBindingGroup>()
        resolvedBindings.forEach { (key, binding) ->
            val bindingGroup = bindingGroups.singleOrNull {
                it.type == binding.type
            }
            if (bindingGroup != null) {
                bindingGroup.keysToReplace += key
                // The components aren't needed if we get delegate to another binding
                if (binding is AssistedBindingNode)
                    owner.children -= binding.childComponent
                if (binding is ChildComponentBindingNode)
                    owner.children -= binding.childComponent
            } else {
                bindingGroups += MergeBindingGroup(key, binding)
                    .also { it.keysToReplace += binding.type }
            }
        }

        bindingGroups.forEach { bindingGroup ->
            bindingGroup.keysToReplace.forEach { key ->
                resolvedBindings[key] = bindingGroup.bindingToUse
            }
        }
    }

    private fun check(binding: BindingNode) {
        if (binding in chain) {
            val relevantSubchain = chain.subList(
                chain.indexOf(binding), chain.lastIndex
            )
            if (relevantSubchain.any {
                    it is ProviderBindingNode ||
                            it.type.classifier.fqName.asString().startsWith("kotlin.Function") ||
                            (it is CallableBindingNode && it.callable.isFunBinding)
                }) return
            error(
                "Circular dependency ${relevantSubchain.map { it.type.render() }} already contains ${binding.type.render()} $chain"
            )
        }

        checkedBindings += binding

        chain.push(binding)

        // recursive check all dependencies
        binding
            .dependencies
            .forEach { check(it) }
        (binding as? ChildComponentBindingNode)?.childComponent?.initialize()
        (binding as? AssistedBindingNode)?.childComponent?.initialize()

        // check that all calling contexts are the same
        if (binding.callableKind == Callable.CallableKind.DEFAULT) {
            val dependenciesByCallableKind = binding.dependencies
                .map { getBinding(it) }
                .filter { it.callableKind != Callable.CallableKind.DEFAULT }
                .groupBy { it.callableKind }
            if (dependenciesByCallableKind.size > 1) {
                error("Dependencies call context mismatch. Dependencies of '${binding.origin.orUnknown()}' have different call contexts\n" +
                        binding.dependencies.joinToString("\n") { dependency ->
                            val dependencyBinding = getBinding(dependency)
                            "${dependency.origin} -> '${dependencyBinding.origin.orUnknown()}' = ${dependencyBinding.callableKind.name}"
                        }
                )
            }
        }

        // adapt call context of dependencies or throw if not possible
        binding.dependencies
            .map { getBinding(it) }
            .filter { it.callableKind != Callable.CallableKind.DEFAULT }
            .forEach { dependency ->
                if (binding.callableKind != Callable.CallableKind.DEFAULT &&
                        binding.callableKind != dependency.callableKind) {
                    error("Call context mismatch. '${binding.origin.orUnknown()}' is a ${binding.callableKind.name} callable but " +
                            "dependency '${dependency.origin.orUnknown()}' is a ${dependency.callableKind.name} callable.")
                } else {
                    binding.callableKind = dependency.callableKind
                }
            }

        chain.pop()

        binding.refineType(binding.dependencies.map { getBinding(it) })
    }

    private fun check(request: BindingRequest) {
        val binding = getBinding(request)
        binding.owner.graph.check(binding)
    }

    fun getBinding(request: BindingRequest): BindingNode {
        val finalRequest = if (request.type.isInlineProvider)
            request.copy(type = request.type.nonInlined()) else request
        var binding = getBindingOrNull(finalRequest)
        if (binding != null) return binding

        if (finalRequest.type.isMarkedNullable || !finalRequest.required) {
            binding = MissingBindingNode(finalRequest.type, owner)
            resolvedBindings[finalRequest.type] = binding
            return binding
        }

        error(
            buildString {
                var indendation = ""
                fun indent() {
                    indendation = "$indendation    "
                }
                appendLine("No binding found for '${finalRequest.type.render()}' in '${owner.nonAssistedComponent.componentType.render()}':")
                appendLine("${finalRequest.origin.orUnknown()} requires '${finalRequest.type.render()}'")
                chain.forEach {
                    appendLine("chain $it" + it.origin.orUnknown())
                }
                /*chain.forEachIndexed { index, binding ->
                    appendLine("${indendation}${binding.origin.orUnknown()} requires binding '${binding.type.render()}'")
                    indent()
                }*/
            }
        )
    }

    private fun getBindingOrNull(request: BindingRequest): BindingNode? {
        var binding = resolvedBindings[request.type]
        if (binding != null) return binding

        check(!locked) {
            "Cannot create request new bindings in ${owner.nonAssistedComponent.componentType} for $request " +
                    "existing ${resolvedBindings.keys}"
        }

        fun List<BindingNode>.mostSpecificOrFail(bindingKind: String): BindingNode? {
            if (isEmpty()) return null

            if (size == 1) return single()

            val exact = filter { it.rawType == request.type }
            if (exact.size == 1) return exact.single()

            exact
                .singleOrNull { candidate ->
                    candidate.dependencies.all { dependency ->
                        getBindingOrNull(dependency) != null
                    }
                }
                ?.let { return it }

            singleOrNull { candidate ->
                candidate.dependencies.all { dependency ->
                    getBindingOrNull(dependency) != null
                }
            }?.let { return it }

            error(
                "Multiple $bindingKind bindings found for '${request.type.render()}' required by ${request.origin} at:\n${
                    joinToString("\n") { "    '${it.origin.orUnknown()}'" }
                }"
            )
        }

        val explicitBindings = getExplicitBindingsForType(request)
        binding = explicitBindings.mostSpecificOrFail("explicit")
        binding?.let {
            resolvedBindings[request.type] = it
            return it
        }

        val (implicitInternalUserBindings, externalImplicitUserBindings) = getImplicitUserBindingsForType(request)
            .partition { !it.isExternal }

        binding = implicitInternalUserBindings.mostSpecificOrFail("internal implicit")
        binding?.let {
            resolvedBindings[request.type] = it
            return it
        }

        binding = externalImplicitUserBindings.mostSpecificOrFail("external implicit")
        binding?.let {
            resolvedBindings[request.type] = it
            return it
        }

        val implicitFrameworkBindings = getImplicitFrameworkBindingsForType(request)
        binding = implicitFrameworkBindings.mostSpecificOrFail("implicit framework")
        binding?.let {
            resolvedBindings[request.type] = it
            return it
        }

        if (owner.isAssisted) {
            val explicitParentBindings = getExplicitParentBindingsForType(request)
            explicitParentBindings.singleOrNull()?.let {
                resolvedBindings[request.type] = it
                return it
            }
        }

        parent?.getBindingOrNull(request)?.let {
            resolvedBindings[request.type] = it
            return it
        }

        return null
    }

    private fun getExplicitBindingsForType(request: BindingRequest): List<BindingNode> = buildList<BindingNode> {
        this += owner.additionalInputTypes
            .filter { request.type.isAssignable(it) }
            .map { InputBindingNode(it, owner) }

        this += moduleBindingCallables
            .filter { request.type.isAssignable(it.callable.type) }
            .map { (callable, receiver, bindingOwner) ->
                val substitutionMap = request.type.getSubstitutionMap(callable.type)
                CallableBindingNode(
                    type = request.type.substituteStars(callable.type),
                    rawType = callable.type,
                    owner = owner,
                    declaredInComponent = bindingOwner,
                    dependencies = callable.valueParameters
                        .map {
                            BindingRequest(
                                it.type.substitute(substitutionMap)
                                    .replaceTypeParametersWithStars(),
                                callable.fqName.child(it.name),
                                !it.hasDefault,
                                callable.callableKind
                            )
                        },
                    receiver = receiver,
                    callable = callable
                )
            }
    }

    private fun getExplicitParentBindingsForType(request: BindingRequest): List<BindingNode> = buildList<BindingNode> {
        this += parentModuleBindingCallables
            .filter { request.type.isAssignable(it.callable.type) }
            .map { (callable, receiver, bindingOwner) ->
                val substitutionMap = request.type.getSubstitutionMap(callable.type)
                CallableBindingNode(
                    type = request.type.substituteStars(callable.type),
                    rawType = callable.type,
                    owner = owner,
                    declaredInComponent = bindingOwner,
                    dependencies = callable.valueParameters
                        .map {
                            BindingRequest(
                                it.type.substitute(substitutionMap)
                                    .replaceTypeParametersWithStars(),
                                callable.fqName.child(it.name),
                                !it.hasDefault,
                                callable.callableKind
                            )
                        },
                    receiver = receiver,
                    callable = callable
                )
            }
    }

    private fun getImplicitUserBindingsForType(request: BindingRequest): List<BindingNode> = buildList<BindingNode> {
        this += declarationStore.bindingsForType(request.type)
            .filterNot { it.isFunBinding }
            .filter {
                it.targetComponent == null || it.targetComponent == owner.componentType ||
                        (owner.isAssisted && request.type == owner.assistedRequests.single().type)
            }
            .map { callable ->
                val substitutionMap = request.type.getSubstitutionMap(callable.type)
                CallableBindingNode(
                    type = request.type.substituteStars(callable.type),
                    rawType = callable.type,
                    owner = owner,
                    declaredInComponent = null,
                    dependencies = callable.valueParameters
                        .map {
                            BindingRequest(
                                it.type.substitute(substitutionMap)
                                    .replaceTypeParametersWithStars(),
                                callable.fqName.child(it.name),
                                !it.hasDefault,
                                callable.callableKind
                            )
                        },
                    receiver = null,
                    callable = callable
                )
            }
    }

    private fun getImplicitFrameworkBindingsForType(request: BindingRequest): List<BindingNode> = buildList<BindingNode> {
        if (request.type == owner.componentType) {
            this += SelfBindingNode(
                type = request.type,
                component = owner
            )
        }

        if (request.type.isFunction && request.type.typeArguments.last().let {
                it.isChildComponent || it.isMergeChildComponent
            }) {
            // todo check if the arguments match the constructor arguments of the child component
            val childComponentType = request.type.typeArguments.last()
            val childComponentConstructor = declarationStore.constructorForComponent(childComponentType)
            val additionalInputTypes = request.type.typeArguments.dropLast(1)
                .filter { inputType ->
                    childComponentConstructor == null ||
                            childComponentConstructor.valueParameters.none {
                                it.type == inputType
                            }
                }
            val existingComponents = mutableSetOf<TypeRef>()
            var currentComponent: ComponentImpl? = owner
            while (currentComponent != null) {
                existingComponents += currentComponent.componentType
                currentComponent = currentComponent.parent
            }
            if (childComponentType !in existingComponents) {
                val componentImpl = componentImplFactory(
                    childComponentType,
                    owner.contextTreeNameProvider(
                        "${owner.rootComponent.name}_${childComponentType.classifier.fqName.shortName().asString()}Impl"
                    ).asNameId(),
                    additionalInputTypes,
                    emptyList(),
                    owner
                )
                this += ChildComponentBindingNode(
                    type = request.type,
                    owner = owner,
                    origin = null,
                    childComponent = componentImpl
                )
            }
        }

        if ((request.type.isFunction || request.type.isSuspendFunction) && request.type.typeArguments.size == 1 &&
            request.type.typeArguments.last().let {
                !it.isChildComponent && !it.isMergeChildComponent
            }) {
            this += ProviderBindingNode(
                type = request.type,
                owner = owner,
                dependencies = listOf(
                    BindingRequest(
                        request.type.typeArguments.single(),
                        FqName.ROOT, // todo
                        true,
                        request.type.callableKind
                    )
                ),
                FqName.ROOT // todo
            )
        }

        this += declarationStore.bindingsForType(request.type)
            .filter { it.isFunBinding }
            .filter {
                it.targetComponent == null || it.targetComponent == owner.componentType ||
                        (owner.isAssisted && request.type == owner.assistedRequests.single().type)
            }
            .map { callable ->
                val substitutionMap = request.type.getSubstitutionMap(callable.type)
                CallableBindingNode(
                    type = request.type.substituteStars(callable.type),
                    rawType = callable.type,
                    owner = owner,
                    declaredInComponent = null,
                    dependencies = callable.valueParameters
                        .map {
                            BindingRequest(
                                it.type.substitute(substitutionMap)
                                    .replaceTypeParametersWithStars(),
                                callable.fqName.child(it.name),
                                !it.hasDefault,
                                callable.callableKind
                            )
                        },
                    receiver = null,
                    callable = callable
                )
            }

        if ((request.type.isFunction || request.type.isSuspendFunction) &&
            request.type.typeArguments.last().let {
                !it.isChildComponent && !it.isMergeChildComponent
            }) {
            val assistedTypes = request.type.typeArguments.dropLast(1).distinct()
            if (assistedTypes.isNotEmpty()) {
                val returnType = request.type.typeArguments.last()
                val childComponentType = typeTranslator.toClassifierRef(
                    moduleDescriptor.builtIns.any
                ).defaultType
                val bindingCallable = Callable(
                    packageFqName = FqName.ROOT,
                    fqName = request.origin,
                    name = "invoke".asNameId(),
                    type = returnType,
                    typeParameters = emptyList(),
                    valueParameters = emptyList(),
                    targetComponent = null,
                    contributionKind = null,
                    isCall = true,
                    callableKind = request.type.callableKind,
                    bindingAdapters = emptyList(),
                    isEager = false,
                    isExternal = false,
                    isInline = false,
                    isFunBinding = false,
                    visibility = Visibilities.INTERNAL,
                    modality = Modality.FINAL,
                    receiver = null
                )
                val childComponent = componentImplFactory(
                    childComponentType,
                    owner.contextTreeNameProvider("${owner.rootComponent.name}_AC").asNameId(),
                    assistedTypes,
                    listOf(bindingCallable),
                    owner
                )
                this += AssistedBindingNode(
                    type = request.type,
                    owner = owner,
                    childComponent = childComponent,
                    assistedTypes = assistedTypes
                )
            }
        }

        this += collections.getNodes(request)
    }

}

private fun FqName?.orUnknown(): String = this?.asString() ?: "unknown origin"

@Binding
class BindingCollections(
    private val declarationStore: DeclarationStore,
    private val owner: @Assisted ComponentImpl,
    private val parent: @Assisted BindingCollections?,
) {

    private val thisMapEntries = mutableMapOf<TypeRef, MutableList<CallableWithReceiver>>()
    private val thisSetElements = mutableMapOf<TypeRef, MutableList<CallableWithReceiver>>()

    fun addMapEntries(entries: CallableWithReceiver) {
        thisMapEntries.getOrPut(entries.callable.type) { mutableListOf() } += entries
    }

    fun addSetElements(elements: CallableWithReceiver) {
        thisSetElements.getOrPut(elements.callable.type) { mutableListOf() } += elements
    }

    private val mapEntriesByType = mutableMapOf<TypeRef, List<CallableWithReceiver>>()
    private fun getMapEntries(type: TypeRef): List<CallableWithReceiver> {
        return mapEntriesByType.getOrPut(type) {
            (parent?.getMapEntries(type) ?: emptyList()) +
                    (if (parent == null) declarationStore.mapEntriesByType(type)
                        .map { CallableWithReceiver(it, null, null, emptyMap()) }
                    else emptyList()) +
                    (thisMapEntries[type] ?: emptyList())
        }
    }

    private val setElementsByType = mutableMapOf<TypeRef, List<CallableWithReceiver>>()
    private fun getSetElements(type: TypeRef): List<CallableWithReceiver> {
        return setElementsByType.getOrPut(type) {
            (parent?.getSetElements(type) ?: emptyList()) +
                    (if (parent == null) declarationStore.setElementsByType(type)
                        .map { CallableWithReceiver(it, null, null, emptyMap()) }
                    else emptyList()) +
                (thisSetElements[type] ?: emptyList())
        }
    }

    fun getNodes(request: BindingRequest): List<BindingNode> {
        return listOfNotNull(
            getMapEntries(request.type)
                .takeIf { it.isNotEmpty() }
                ?.let { entries ->
                    MapBindingNode(
                        type = request.type,
                        owner = owner,
                        dependencies = entries.flatMap { (entry, _, _, substitutionMap) ->
                            entry.valueParameters
                                .map {
                                    BindingRequest(
                                        it.type.substitute(substitutionMap),
                                        entry.fqName.child(it.name),
                                        it.hasDefault,
                                        entry.callableKind
                                    )
                                }
                        },
                        entries = entries
                    )
                },
            getSetElements(request.type)
                .takeIf { it.isNotEmpty() }
                ?.let { elements ->
                    SetBindingNode(
                        type = request.type,
                        owner = owner,
                        dependencies = elements.flatMap { (element, _, _, substitutionMap) ->
                            element.valueParameters
                                .map {
                                    BindingRequest(
                                        it.type.substitute(substitutionMap),
                                        element.fqName.child(it.name),
                                        it.hasDefault,
                                        element.callableKind
                                    )
                                }
                        },
                        elements = elements
                    )
                }
        )
    }
}
