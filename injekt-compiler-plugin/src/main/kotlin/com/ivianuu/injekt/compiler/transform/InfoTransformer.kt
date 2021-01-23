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

import com.ivianuu.injekt.compiler.*
import com.ivianuu.injekt.compiler.resolution.toCallableRef
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import java.util.Base64

class InfoTransformer(
    private val declarationStore: DeclarationStore,
    private val pluginContext: IrPluginContext
) : IrElementTransformerVoid() {

    @Suppress("NewApi")
    override fun visitClass(declaration: IrClass): IrStatement {
        if (declaration.hasAnnotation(InjektFqNames.Given)) {
            declaration.annotations += DeclarationIrBuilder(pluginContext, declaration.symbol)
                .run {
                    irCall(
                        pluginContext.referenceClass(InjektFqNames.ClassifierInfo)!!
                            .constructors
                            .single()
                    ).apply {
                        val info = declarationStore.classifierInfoFor(declaration.descriptor)
                        val value = Base64.getEncoder()
                            .encode(declarationStore.moshi.adapter(PersistedClassifierInfo::class.java)
                                .toJson(info).toByteArray())
                            .decodeToString()
                        putValueArgument(0, irString(value))
                    }
                }
        }
        return super.visitClass(declaration)
    }

    @Suppress("NewApi")
    override fun visitFunction(declaration: IrFunction): IrStatement {
        if (declaration.hasAnnotation(InjektFqNames.Given) ||
            declaration.hasAnnotation(InjektFqNames.GivenSetElement) ||
            declaration.hasAnnotation(InjektFqNames.Module) ||
            declaration.hasAnnotation(InjektFqNames.Interceptor) ||
            declaration.hasAnnotation(InjektFqNames.GivenFun) || (
                    declaration is IrConstructor &&
                            (declaration.constructedClass.hasAnnotation(InjektFqNames.Given) ||
                                    declaration.constructedClass.hasAnnotation(InjektFqNames.GivenSetElement) ||
                                    declaration.constructedClass.hasAnnotation(InjektFqNames.Module) ||
                                    declaration.constructedClass.hasAnnotation(InjektFqNames.Interceptor)))) {
                val annotation = DeclarationIrBuilder(pluginContext, declaration.symbol)
                    .run {
                        irCall(
                            pluginContext.referenceClass(InjektFqNames.CallableInfo)!!
                                .constructors
                                .single()
                        ).apply {
                            val info = declaration.descriptor.toCallableRef(declarationStore)
                                .toPersistedCallableInfo(declarationStore)
                            val value = Base64.getEncoder()
                                .encode(declarationStore.moshi.adapter(PersistedCallableInfo::class.java)
                                    .toJson(info).toByteArray())
                                .decodeToString()
                            putValueArgument(0, irString(value))
                        }
                    }

                if (declaration is IrConstructor &&
                        declaration.constructedClass.primaryConstructor == declaration) {
                    declaration.constructedClass.annotations += annotation
                } else {
                    declaration.annotations += annotation
                }
        }
        return super.visitFunction(declaration)
    }

    @Suppress("NewApi")
    override fun visitProperty(declaration: IrProperty): IrStatement {
        if (declaration.hasAnnotation(InjektFqNames.Given) ||
            declaration.hasAnnotation(InjektFqNames.GivenSetElement) ||
            declaration.hasAnnotation(InjektFqNames.Module) ||
            declaration.hasAnnotation(InjektFqNames.Interceptor)) {
            declaration.annotations += DeclarationIrBuilder(pluginContext, declaration.symbol)
                .run {
                    irCall(
                        pluginContext.referenceClass(InjektFqNames.CallableInfo)!!
                            .constructors
                            .single()
                    ).apply {
                        val info = declaration.descriptor.toCallableRef(declarationStore)
                            .toPersistedCallableInfo(declarationStore)
                        val value = Base64.getEncoder()
                            .encode(declarationStore.moshi.adapter(PersistedCallableInfo::class.java)
                                .toJson(info).toByteArray())
                            .decodeToString()
                        putValueArgument(0, irString(value))
                    }
                }
        }
        return super.visitProperty(declaration)
    }

}