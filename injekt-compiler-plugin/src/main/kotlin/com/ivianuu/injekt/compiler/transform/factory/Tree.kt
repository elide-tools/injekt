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

package com.ivianuu.injekt.compiler.transform.factory

import com.ivianuu.injekt.compiler.MapKey
import com.ivianuu.injekt.compiler.getQualifiers
import com.ivianuu.injekt.compiler.isNoArgProvider
import com.ivianuu.injekt.compiler.isTypeParameter
import com.ivianuu.injekt.compiler.toAnnotationDescriptor
import com.ivianuu.injekt.compiler.transform.InjektDeclarationIrBuilder
import com.ivianuu.injekt.compiler.typeArguments
import com.ivianuu.injekt.compiler.typeOrFail
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.FqNameEqualityChecker
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrErrorType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.FqName

interface Node

interface RequirementNode : Node {
    val key: Key
    val accessor: FactoryExpression
}

class ModuleNode(
    val module: IrClass,
    override val key: Key,
    override val accessor: FactoryExpression,
    val typeParametersMap: Map<IrTypeParameterSymbol, IrType>
) : RequirementNode {
    val descriptor = module.declarations.single {
        it.descriptor.name.asString() == "Descriptor"
    } as IrClass
    val descriptorTypeParametersMap = descriptor.typeParameters
        .associateWith { typeParametersMap.values.toList()[it.index] }
        .mapKeys { it.key.symbol }

    init {
        typeParametersMap.forEach {
            check(!it.value.isTypeParameter()) {
                "Must be concrete type ${it.key.owner.dump()} -> ${it.value.render()}"
            }
        }
    }
}

class FactoryNode(
    val factory: FactoryImpl,
    override val key: Key,
    override val accessor: FactoryExpression
) : RequirementNode

class DependencyNode(
    val dependency: IrClass,
    override val key: Key,
    override val accessor: FactoryExpression
) : RequirementNode

class BindingRequest(
    val key: Key,
    val requestingKey: Key?,
    val requestOrigin: FqName?,
    val requestType: RequestType = key.inferRequestType()
) {

    fun copy(
        key: Key = this.key,
        requestingKey: Key? = this.requestingKey,
        requestOrigin: FqName? = this.requestOrigin,
        requestType: RequestType = this.requestType
    ): BindingRequest = BindingRequest(
        key, requestingKey, requestOrigin, requestType
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BindingRequest

        if (key != other.key) return false
        if (requestType != other.requestType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + requestType.hashCode()
        return result
    }

    override fun toString(): String {
        return "BindingRequest(key=$key, requestingKey=$requestingKey, requestOrigin=$requestOrigin, requestType=$requestType)"
    }

}

fun Key.inferRequestType() = when {
    type.isNoArgProvider() -> RequestType.Provider
    else -> RequestType.Instance
}

enum class RequestType {
    Instance,
    Provider
}

sealed class BindingNode(
    val key: Key,
    val context: IrClass?,
    val dependencies: List<BindingRequest>,
    val targetScope: IrType?,
    val scoped: Boolean,
    val module: ModuleNode?,
    val owner: FactoryImpl,
    val origin: FqName?
) : Node

class AssistedProvisionBindingNode(
    key: Key,
    context: IrClass?,
    dependencies: List<BindingRequest>,
    targetScope: IrType?,
    scoped: Boolean,
    module: ModuleNode?,
    owner: FactoryImpl,
    origin: FqName?,
    val createExpression: IrBuilderWithScope.(Map<InjektDeclarationIrBuilder.FactoryParameter, () -> IrExpression?>) -> IrExpression,
    val parameters: List<InjektDeclarationIrBuilder.FactoryParameter>
) : BindingNode(key, context, dependencies, targetScope, scoped, module, owner, origin)

class ChildFactoryBindingNode(
    key: Key,
    owner: FactoryImpl,
    origin: FqName?,
    val parent: IrClass,
    val childFactoryExpression: FactoryExpression
) : BindingNode(
    key, null, listOf(
        BindingRequest(
            parent.defaultType.asKey(),
            key,
            null
        )
    ),
    null, false, null, owner, origin
)

class DelegateBindingNode(
    key: Key,
    context: IrClass?,
    owner: FactoryImpl,
    origin: FqName?,
    val originalKey: Key,
    val requestOrigin: FqName
) : BindingNode(
    key, context, listOf(
        BindingRequest(originalKey, key, requestOrigin)
    ), null, false, null, owner, origin
)

class DependencyBindingNode(
    key: Key,
    owner: FactoryImpl,
    origin: FqName?,
    val function: IrFunction,
    val requirementNode: DependencyNode
) : BindingNode(key, null, emptyList(), null, false, null, owner, origin)

class FactoryImplementationBindingNode(
    val factoryNode: FactoryNode,
) : BindingNode(
    factoryNode.key,
    null,
    emptyList(),
    null,
    false,
    null,
    factoryNode.factory,
    factoryNode.key.type.classOrNull!!.owner!!.fqNameForIrSerialization
)

class MapBindingNode(
    key: Key,
    owner: FactoryImpl,
    origin: FqName?,
    val entries: Map<MapKey, BindingRequest>
) : BindingNode(key, null, entries.values.toList(), null, false, null, owner, origin) {
    val keyKey = key.type.typeArguments[0].typeOrFail.asKey()
    val valueKey = key.type.typeArguments[1].typeOrFail.asKey()
}

class NullBindingNode(
    key: Key,
    owner: FactoryImpl
) : BindingNode(
    key,
    null,
    emptyList(),
    null,
    false,
    null,
    owner,
    null
)

class ProviderBindingNode(
    key: Key,
    owner: FactoryImpl,
    origin: FqName?
) : BindingNode(
    key,
    null,
    listOf(
        BindingRequest(
            key.type.typeArguments.single().typeOrFail.asKey(),
            key,
            origin
        )
    ),
    null,
    false,
    null,
    owner,
    origin
)

class ProvisionBindingNode(
    key: Key,
    context: IrClass?,
    dependencies: List<BindingRequest>,
    targetScope: IrType?,
    scoped: Boolean,
    module: ModuleNode?,
    owner: FactoryImpl,
    origin: FqName?,
    val createExpression: IrBuilderWithScope.(Map<InjektDeclarationIrBuilder.FactoryParameter, () -> IrExpression?>) -> IrExpression,
    val parameters: List<InjektDeclarationIrBuilder.FactoryParameter>
) : BindingNode(key, context, dependencies, targetScope, scoped, module, owner, origin)

class SetBindingNode(
    key: Key,
    owner: FactoryImpl,
    origin: FqName?,
    val elements: Set<BindingRequest>
) : BindingNode(key, null, elements.toList(), null, false, null, owner, origin) {
    val elementKey = key.type.typeArguments.single().typeOrFail.asKey()
}

fun IrType.asKey(): Key {
    return Key(this)
}

class Key(val type: IrType) {

    init {
        check(type !is IrErrorType) {
            "Cannot be error type ${type.render()}"
        }
        check(!type.isTypeParameter()) {
            "Must be concrete type ${type.render()}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Key

        if (!type.equalsForKey(other.type)) return false

        return true
    }

    override fun hashCode(): Int {
        return type.hashCodeForKey()
    }

    override fun toString(): String = type.render()

    private fun IrType.hashCodeForKey(): Int {
        var result = classifierOrNull?.hashCode() ?: 0
        result = 31 * result + qualifiersHash()
        result = 32 * result + typeArguments.map { it.typeOrNull?.hashCodeForKey() }.hashCode()
        return result
    }

    private fun IrType.equalsForKey(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IrSimpleType) return false
        this as IrSimpleType
        if (!FqNameEqualityChecker.areEqual(classifier, other.classifier)) return false
        if (!getQualifiers().qualifiersEquals(other.getQualifiers())) return false

        if (arguments.size != arguments.size) return false

        arguments
            .forEachIndexed { index, argument ->
                val type = argument.typeOrNull
                val otherArgument = other.arguments[index]
                val otherType = otherArgument.typeOrNull
                if ((type != null && otherType != null && !type.equalsForKey(otherType)) ||
                    (type != null) != (otherType != null)
                ) return false
            }

        return true
    }

    private fun List<IrConstructorCall>.qualifiersEquals(other: List<IrConstructorCall>): Boolean {
        if (size != other.size) return false
        for (i in indices) {
            val thisAnnotation = this[i]
            val thisAnnotationDescriptor = thisAnnotation.toAnnotationDescriptor()
            val otherAnnotation = other[i]
            val otherAnnotationDescriptor = otherAnnotation.toAnnotationDescriptor()
            if (thisAnnotation.hash() != otherAnnotation.hash()) return false
            val thisValues = thisAnnotationDescriptor.allValueArguments.entries.toList()
            val otherValues = otherAnnotationDescriptor.allValueArguments.entries.toList()
            if (thisValues.size != otherValues.size) return false
            for (j in thisValues.indices) {
                val thisValue = thisValues[j]
                val otherValue = otherValues[j]
                if (thisValue.key != otherValue.key) return false
                if (thisValue.value.value != otherValue.value.value) return false
            }
        }

        return true
    }

    private fun IrType.qualifiersHash() = getQualifiers()
        .map { it.hash() }
        .hashCode()

    private fun IrConstructorCall.hash(): Int {
        var result = type.hashCodeForKey()
        val descriptor = toAnnotationDescriptor()
        result = 31 * result + descriptor
            .allValueArguments
            .map { it.key to it.value.value }
            .hashCode()
        return result
    }

}
