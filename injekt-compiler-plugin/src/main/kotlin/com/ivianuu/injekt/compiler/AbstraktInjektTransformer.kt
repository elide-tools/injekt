package com.ivianuu.injekt.compiler

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

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.findClassAcrossModuleDependencies
import org.jetbrains.kotlin.descriptors.findTypeAliasAcrossModuleDependencies
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.KotlinType

abstract class AbstractInjektTransformer(
    protected val context: IrPluginContext
) : IrElementTransformerVoid() {

    val symbols = InjektSymbols(context)

    protected val symbolTable = context.symbolTable
    protected val typeTranslator = context.typeTranslator
    protected fun KotlinType.toIrType() = typeTranslator.translateType(this)

    protected val injektPackage =
        context.moduleDescriptor.getPackage(InjektFqNames.InjektPackage)

    protected fun getClass(fqName: FqName) =
        context.moduleDescriptor.findClassAcrossModuleDependencies(ClassId.topLevel(fqName))!!

    protected fun getTypeAlias(fqName: FqName) =
        context.moduleDescriptor.findTypeAliasAcrossModuleDependencies(ClassId.topLevel(fqName))!!

    override fun visitModuleFragment(declaration: IrModuleFragment): IrModuleFragment {
        return super.visitModuleFragment(declaration)
            .also {
                /*it.transformChildrenVoid(object : IrElementTransformerVoid() {
                    override fun visitFile(declaration: IrFile): IrFile {
                        return super.visitFile(declaration)
                            .also {
                                it as IrFileImpl
                                it.metadata = MetadataSource.File(
                                    declaration
                                        .declarations
                                        .map { it.descriptor }
                                )
                            }
                    }
                })*/
                it.patchDeclarationParents()
            }
    }

}
