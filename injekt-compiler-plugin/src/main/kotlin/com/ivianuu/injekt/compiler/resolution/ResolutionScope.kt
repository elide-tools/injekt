package com.ivianuu.injekt.compiler.resolution

import com.ivianuu.injekt.compiler.DeclarationStore
import com.ivianuu.injekt.compiler.InjektFqNames
import com.ivianuu.injekt.compiler.hasAnnotation
import com.ivianuu.injekt.compiler.isExternalDeclaration
import com.ivianuu.injekt.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

class ResolutionScope(
    val name: String,
    val parent: ResolutionScope?,
    val declarationStore: DeclarationStore,
    initialGivensInScope: () -> List<CallableDescriptor>,
    initialGivenCollectionElementsInScope: () -> List<CallableDescriptor>,
) {
    private val givens by unsafeLazy { initialGivensInScope().toMutableList() }
    private val givenCollectionElements by unsafeLazy {
        initialGivenCollectionElementsInScope().toMutableList()
    }

    fun givensForTypeInThisScope(type: TypeRef): List<GivenNode> = givens
        .filter { it.returnType!!.toTypeRef().isAssignableTo(type) }
        .map { it.toGivenNode(type, declarationStore) }

    fun givenCollectionElementsForTypeInThisScope(type: TypeRef): List<CallableDescriptor> =
        givenCollectionElements
            .filter { it.returnType!!.toTypeRef().isAssignableTo(type) }

    fun addIfNeeded(callable: CallableDescriptor) {
        if (callable.hasAnnotation(InjektFqNames.Given)) givens += callable
        else if (callable.hasAnnotation(InjektFqNames.GivenMap) ||
            callable.hasAnnotation(InjektFqNames.GivenSet)
        ) givenCollectionElements += callable
    }

    override fun toString(): String = "ResolutionScope($name)"
}

fun ExternalResolutionScope(declarationStore: DeclarationStore): ResolutionScope {
    return ResolutionScope(
        name = "EXTERNAL",
        declarationStore = declarationStore,
        parent = null,
        initialGivensInScope = {
            declarationStore.allGivens
                .filter { it.isExternalDeclaration() }
                .filter { it.visibility == DescriptorVisibilities.PUBLIC }
        },
        initialGivenCollectionElementsInScope = {
            declarationStore.allGivenCollectionElements
                .filter { it.isExternalDeclaration() }
        }
    )
}

fun InternalResolutionScope(
    parent: ResolutionScope?,
    declarationStore: DeclarationStore,
): ResolutionScope {
    return ResolutionScope(
        name = "INTERNAL",
        declarationStore = declarationStore,
        parent = parent,
        initialGivensInScope = {
            declarationStore.allGivens
                .filterNot { it.isExternalDeclaration() }
        },
        initialGivenCollectionElementsInScope = {
            declarationStore.allGivenCollectionElements
                .filterNot { it.isExternalDeclaration() }
        }
    )
}

fun ClassResolutionScope(
    bindingContext: BindingContext,
    declarationStore: DeclarationStore,
    descriptor: ClassDescriptor,
    parent: ResolutionScope?,
): ResolutionScope {
    return ResolutionScope(
        name = "CLASS(${descriptor.fqNameSafe})",
        declarationStore = declarationStore,
        parent = parent,
        initialGivensInScope = {
            descriptor
                .extractGivensOfDeclaration(bindingContext, declarationStore)
        },
        initialGivenCollectionElementsInScope = {
            descriptor.extractGivenCollectionElementsOfDeclaration(bindingContext)
        }
    )
}

fun FunctionResolutionScope(
    declarationStore: DeclarationStore,
    parent: ResolutionScope?,
    descriptor: FunctionDescriptor,
): ResolutionScope {
    return ResolutionScope(
        name = "FUN(${descriptor.fqNameSafe})",
        declarationStore = declarationStore,
        parent = parent,
        initialGivensInScope = { descriptor.extractGivensOfCallable(declarationStore) },
        initialGivenCollectionElementsInScope = {
            descriptor.extractGivenCollectionElementsOfCallable()
        }
    )
}

fun BlockResolutionScope(
    declarationStore: DeclarationStore,
    parent: ResolutionScope?,
): ResolutionScope {
    return ResolutionScope(
        name = "BLOCK",
        declarationStore = declarationStore,
        parent = parent,
        initialGivensInScope = { emptyList() },
        initialGivenCollectionElementsInScope = { emptyList() }
    )
}
