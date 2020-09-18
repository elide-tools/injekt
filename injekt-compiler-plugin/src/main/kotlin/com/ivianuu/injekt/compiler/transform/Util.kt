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

package com.ivianuu.injekt.compiler.transform

import com.ivianuu.injekt.compiler.InjektSymbols
import com.ivianuu.injekt.compiler.UniqueNameProvider
import com.ivianuu.injekt.compiler.addMetadataIfNotLocal
import com.ivianuu.injekt.compiler.asNameId
import com.ivianuu.injekt.compiler.buildClass
import com.ivianuu.injekt.compiler.getJoinedName
import com.ivianuu.injekt.compiler.remapTypeParametersByName
import com.ivianuu.injekt.compiler.removeIllegalChars
import com.ivianuu.injekt.compiler.uniqueKey
import com.ivianuu.injekt.compiler.uniqueTypeName
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.ir.copyTypeParameters
import org.jetbrains.kotlin.backend.common.ir.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

fun IrDeclarationWithName.getContextName(): Name {
    return (getJoinedName(
        getPackageFragment()!!.fqName,
        descriptor.fqNameSafe
            .parent().child(name.asString().asNameId())
    ).asString().removeIllegalChars() + "${uniqueKey().hashCode()}__Context")
        .removeIllegalChars()
        .asNameId()
}

fun createContext(
    owner: IrDeclarationWithName,
    origin: FqName,
    capturedTypeParameters: List<IrTypeParameter>,
    pluginContext: IrPluginContext,
    module: IrModuleFragment,
    injektSymbols: InjektSymbols
) = buildClass {
    kind = ClassKind.INTERFACE
    name = owner.getContextName()
    visibility = Visibilities.INTERNAL
}.apply {
    parent = owner.file
    createImplicitParameterDeclarationWithWrappedDescriptor()
    addMetadataIfNotLocal()
    copyTypeParameters(capturedTypeParameters)

    annotations += DeclarationIrBuilder(pluginContext, symbol).run {
        irCall(injektSymbols.contextMarker.constructors.single())
    }
    annotations += DeclarationIrBuilder(pluginContext, symbol).run {
        irCall(injektSymbols.origin.constructors.single()).apply {
            putValueArgument(0, irString(origin.asString()))
        }
    }
}

fun createContextFactory(
    contextType: IrType,
    capturedTypeParameters: List<IrTypeParameter>,
    file: IrFile,
    inputTypes: List<IrType>,
    startOffset: Int,
    pluginContext: IrPluginContext,
    module: IrModuleFragment,
    injektSymbols: InjektSymbols,
    isChild: Boolean
) = buildClass {
    name = "${contextType.classOrNull!!.owner.name}${startOffset}Factory"
        .removeIllegalChars().asNameId()
    kind = ClassKind.INTERFACE
    visibility = Visibilities.INTERNAL
}.apply clazz@{
    parent = file
    createImplicitParameterDeclarationWithWrappedDescriptor()
    addMetadataIfNotLocal()

    copyTypeParameters(capturedTypeParameters)

    addFunction {
        this.name = "create".asNameId()
        returnType = contextType
            .remapTypeParametersByName(
                capturedTypeParameters
                    .map { it.descriptor.fqNameSafe }
                    .zip(typeParameters)
                    .toMap()
            )
        modality = Modality.ABSTRACT
    }.apply {
        dispatchReceiverParameter = thisReceiver!!.copyTo(this)
        parent = this@clazz
        addMetadataIfNotLocal()
        val parameterUniqueNameProvider = UniqueNameProvider()
        inputTypes
            .map {
                it.remapTypeParametersByName(
                    capturedTypeParameters
                        .map { it.descriptor.fqNameSafe }
                        .zip(typeParameters)
                        .toMap()
                )
            }
            .forEach {
                addValueParameter(
                    parameterUniqueNameProvider(it.uniqueTypeName().asString()),
                    it
                )
            }
    }

    annotations += DeclarationIrBuilder(pluginContext, symbol).run {
        if (!isChild) {
            irCall(injektSymbols.rootContextFactory.constructors.single()).apply {
                putValueArgument(
                    0,
                    irString(
                        file.fqName.child((name.asString() + "Impl").asNameId()).asString()
                    )
                )
            }
        } else {
            irCall(injektSymbols.childContextFactory.constructors.single())
        }
    }
}
