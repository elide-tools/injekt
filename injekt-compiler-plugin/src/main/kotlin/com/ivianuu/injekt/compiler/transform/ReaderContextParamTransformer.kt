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

import com.ivianuu.injekt.compiler.addMetadataIfNotLocal
import com.ivianuu.injekt.compiler.asNameId
import com.ivianuu.injekt.compiler.canUseReaders
import com.ivianuu.injekt.compiler.copy
import com.ivianuu.injekt.compiler.getFunctionType
import com.ivianuu.injekt.compiler.getReaderConstructor
import com.ivianuu.injekt.compiler.isExternalDeclaration
import com.ivianuu.injekt.compiler.isMarkedAsReader
import com.ivianuu.injekt.compiler.jvmNameAnnotation
import com.ivianuu.injekt.compiler.recordLookup
import com.ivianuu.injekt.compiler.remapTypeParametersByName
import com.ivianuu.injekt.compiler.transformFiles
import com.ivianuu.injekt.compiler.typeWith
import com.ivianuu.injekt.compiler.uniqueKey
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.ir.copyTypeParametersFrom
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.PropertyGetterDescriptor
import org.jetbrains.kotlin.descriptors.PropertySetterDescriptor
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDelegatingConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionReferenceImpl
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTypeAndValueArgumentsFrom
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.findAnnotation
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasDefaultValue
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

class ReaderContextParamTransformer(
    pluginContext: IrPluginContext,
    private val indexer: Indexer
) : AbstractInjektTransformer(pluginContext) {

    private val transformedClasses = mutableSetOf<IrClass>()
    private val transformedFunctions = mutableMapOf<IrFunction, IrFunction>()

    fun getTransformedFunction(function: IrFunction) =
        transformFunctionIfNeeded(function)

    private val transformer = object : IrElementTransformerVoidWithContext() {
        override fun visitClassNew(declaration: IrClass): IrStatement =
            super.visitClassNew(transformClassIfNeeded(declaration))

        override fun visitFunctionNew(declaration: IrFunction): IrStatement =
            super.visitFunctionNew(transformFunctionIfNeeded(declaration))
    }

    override fun lower() {
        module.transformFiles(
            object : IrElementTransformerVoidWithContext() {
                override fun visitCall(expression: IrCall): IrExpression {
                    if (expression.symbol.descriptor.fqNameSafe.asString() ==
                        "com.ivianuu.injekt.runReader"
                    ) {
                        (expression.getValueArgument(0) as IrFunctionExpression)
                            .function.annotations += DeclarationIrBuilder(
                            pluginContext, expression.symbol
                        ).irCall(injektSymbols.reader.constructors.single())
                    }
                    return super.visitCall(expression)
                }
            }
        )
        module.transformFiles(transformer)
        module.rewriteTransformedReferences()
    }

    private fun transformClassIfNeeded(clazz: IrClass): IrClass {
        if (clazz in transformedClasses) return clazz

        val readerConstructor = clazz.getReaderConstructor(pluginContext)

        if (!clazz.isMarkedAsReader(pluginContext) && readerConstructor == null) return clazz

        if (readerConstructor == null) return clazz

        transformedClasses += clazz

        if (clazz.isExternalDeclaration()) {
            val existingSignature = getExternalReaderSignature(clazz)
                ?: error("Lol ${clazz.dump()}")
            readerConstructor.copySignatureFrom(existingSignature)
            return clazz
        }

        lateinit var contextField: IrField
        lateinit var contextParameter: IrValueParameter

        transformReaderFunction(
            owner = clazz,
            ownerFunction = readerConstructor,
            onContextParameterCreated = {
                contextParameter = it
                contextField = clazz.addField(
                    fieldName = "_context",
                    fieldType = it.type
                )
            }
        )

        indexer.index(
            originatingDeclaration = clazz,
            path = listOf(
                DeclarationGraph.SIGNATURE_PATH,
                clazz.uniqueKey()
            )
        ) {
            recordLookup(this, clazz)
            addReaderSignature(clazz, readerConstructor, null)
        }

        readerConstructor.body = DeclarationIrBuilder(pluginContext, clazz.symbol).run {
            irBlockBody {
                readerConstructor.body?.statements?.forEach {
                    +it
                    if (it is IrDelegatingConstructorCall) {
                        +irSetField(
                            irGet(clazz.thisReceiver!!),
                            contextField,
                            irGet(contextParameter)
                        )
                    }
                }
            }
        }

        return clazz
    }

    private fun transformFunctionIfNeeded(function: IrFunction): IrFunction {
        if (function.descriptor.fqNameSafe.asString() == "com.ivianuu.injekt.given" ||
            function.descriptor.fqNameSafe.asString() == "com.ivianuu.injekt.childContext" ||
            function.descriptor.fqNameSafe.asString() == "com.ivianuu.injekt.runReader"
        ) return function

        if (function is IrConstructor) {
            return if (function.canUseReaders(pluginContext)) {
                transformClassIfNeeded(function.constructedClass)
                function
            } else function
        }

        transformedFunctions[function]?.let { return it }
        if (function in transformedFunctions.values) return function

        if (!function.canUseReaders(pluginContext)) return function

        if (function.isExternalDeclaration()) {
            val existingSignature = getExternalReaderSignature(function)
            if (existingSignature == null) {

                error("Wtf ${function.dump()}")
            }
            val transformedFunction = function.copyAsReader()
            transformedFunctions[function] = transformedFunction
            transformedFunction.copySignatureFrom(existingSignature)
            return transformedFunction
        }

        val transformedFunction = function.copyAsReader()
            .also { it.transformChildrenVoid(transformer) }
        transformedFunctions[function] = transformedFunction

        if (function.canUseReaders(pluginContext)) {
            transformReaderFunction(
                owner = transformedFunction,
                ownerFunction = transformedFunction
            )
        }

        indexer.index(
            originatingDeclaration = transformedFunction,
            path = listOf(
                DeclarationGraph.SIGNATURE_PATH,
                transformedFunction.uniqueKey()
            )
        ) {
            recordLookup(this, transformedFunction)
            addReaderSignature(transformedFunction, transformedFunction, null)
        }

        return transformedFunction
    }

    private fun <T> transformReaderFunction(
        owner: T,
        ownerFunction: IrFunction,
        onContextParameterCreated: (IrValueParameter) -> Unit = {}
    ) where T : IrDeclarationWithName, T : IrDeclarationWithVisibility, T : IrDeclarationParent, T : IrTypeParametersContainer {
        val parentFunction =
            if (owner.visibility == Visibilities.LOCAL && owner.parent is IrFunction)
                owner.parent as IrFunction else null

        val context =
            createContext(
                owner, ownerFunction.descriptor.fqNameSafe, parentFunction,
                pluginContext, module, injektSymbols
            )
        val contextParameter = ownerFunction.addContextParameter(context)
        onContextParameterCreated(contextParameter)
    }

    private fun IrFunction.addContextParameter(context: IrClass): IrValueParameter {
        return addValueParameter(
            name = "_context",
            type = context.typeWith(typeParameters.map { it.defaultType })
        )
    }

    private fun transformCall(
        transformedCallee: IrFunction,
        expression: IrFunctionAccessExpression
    ): IrFunctionAccessExpression {
        return when (expression) {
            is IrConstructorCall -> {
                IrConstructorCallImpl(
                    expression.startOffset,
                    expression.endOffset,
                    transformedCallee.returnType,
                    transformedCallee.symbol as IrConstructorSymbol,
                    expression.typeArgumentsCount,
                    transformedCallee.typeParameters.size,
                    transformedCallee.valueParameters.size,
                    expression.origin
                ).apply {
                    copyTypeAndValueArgumentsFrom(expression)
                }
            }
            is IrDelegatingConstructorCall -> {
                IrDelegatingConstructorCallImpl(
                    expression.startOffset,
                    expression.endOffset,
                    expression.type,
                    transformedCallee.symbol as IrConstructorSymbol,
                    expression.typeArgumentsCount,
                    transformedCallee.valueParameters.size
                ).apply {
                    copyTypeAndValueArgumentsFrom(expression)
                }
            }
            else -> {
                expression as IrCall
                IrCallImpl(
                    expression.startOffset,
                    expression.endOffset,
                    transformedCallee.returnType,
                    transformedCallee.symbol,
                    expression.origin,
                    expression.superQualifierSymbol
                ).apply {
                    copyTypeAndValueArgumentsFrom(expression)
                }
            }
        }
    }

    private fun IrFunction.copyAsReader(): IrFunction {
        return copy(
            pluginContext
        ).apply {
            val descriptor = descriptor
            if (descriptor is PropertyGetterDescriptor &&
                annotations.findAnnotation(DescriptorUtils.JVM_NAME) == null
            ) {
                val name = JvmAbi.getterName(descriptor.correspondingProperty.name.identifier)
                annotations += DeclarationIrBuilder(pluginContext, symbol)
                    .jvmNameAnnotation(name, pluginContext)
                correspondingPropertySymbol?.owner?.getter = this
            }

            if (descriptor is PropertySetterDescriptor &&
                annotations.findAnnotation(DescriptorUtils.JVM_NAME) == null
            ) {
                val name = JvmAbi.setterName(descriptor.correspondingProperty.name.identifier)
                annotations += DeclarationIrBuilder(pluginContext, symbol)
                    .jvmNameAnnotation(name, pluginContext)
                correspondingPropertySymbol?.owner?.setter = this
            }

            if (this@copyAsReader is IrOverridableDeclaration<*>) {
                overriddenSymbols = this@copyAsReader.overriddenSymbols.map {
                    val owner = it.owner as IrFunction
                    val newOwner = transformFunctionIfNeeded(owner)
                    newOwner.symbol as IrSimpleFunctionSymbol
                }
            }
        }
    }

    private fun getExternalReaderSignature(owner: IrDeclarationWithName): IrFunction? {
        return indexer.externalClassIndices(
            listOf(
                DeclarationGraph.SIGNATURE_PATH,
                owner.uniqueKey()
            )
        ).firstOrNull()
            ?.functions
            ?.single {
                // we use startsWith because inline class function names get mangled
                // to something like signature-dj39
                it.name.asString().startsWith("signature")
            }
    }

    private fun IrFunction.copySignatureFrom(signature: IrFunction) {
        valueParameters = signature.valueParameters.map {
            it.copyTo(
                this,
                type = it.type,
                varargElementType = it.varargElementType
            )
        }
    }

    private fun IrClass.addReaderSignature(
        owner: IrDeclarationWithName,
        ownerFunction: IrFunction,
        parentFunction: IrFunction?
    ) {
        annotations += DeclarationIrBuilder(pluginContext, symbol)
            .irCall(injektSymbols.signature.constructors.single())

        addFunction {
            name = "signature".asNameId()
            modality = Modality.ABSTRACT
        }.apply {
            dispatchReceiverParameter = thisReceiver!!.copyTo(this)
            addMetadataIfNotLocal()

            copyTypeParametersFrom(owner as IrTypeParametersContainer)
            parentFunction?.let { copyTypeParametersFrom(it) }

            returnType = ownerFunction.returnType
                .remapTypeParametersByName(owner, this)
                .let {
                    if (parentFunction != null) it.remapTypeParametersByName(
                        parentFunction, this
                    ) else it
                }

            valueParameters = ownerFunction.valueParameters.map {
                it.copyTo(
                    this,
                    type = it.type
                        .remapTypeParametersByName(owner, this)
                        .let {
                            if (parentFunction != null) it.remapTypeParametersByName(
                                parentFunction, this
                            ) else it
                        },
                    varargElementType = it.varargElementType
                        ?.remapTypeParametersByName(owner, this)
                        ?.let {
                            if (parentFunction != null) it.remapTypeParametersByName(
                                parentFunction, this
                            ) else it
                        },
                    defaultValue = if (it.hasDefaultValue()) DeclarationIrBuilder(
                        pluginContext,
                        it.symbol
                    ).run {
                        irExprBody(
                            irCall(
                                pluginContext.referenceFunctions(
                                    FqName("com.ivianuu.injekt.internal.injektIntrinsic")
                                )
                                    .single()
                            ).apply {
                                putTypeArgument(0, it.type)
                            }
                        )
                    } else null
                )
            }
        }
    }

    private fun IrModuleFragment.rewriteTransformedReferences() {
        transformFiles(object : IrElementTransformerVoid() {
            override fun visitFunctionExpression(expression: IrFunctionExpression): IrExpression {
                val result = super.visitFunctionExpression(expression) as IrFunctionExpression
                val transformed = transformFunctionIfNeeded(result.function)
                return if (transformed in transformedFunctions.values) IrFunctionExpressionImpl(
                    result.startOffset,
                    result.endOffset,
                    transformed.getFunctionType(pluginContext),
                    transformed as IrSimpleFunction,
                    result.origin
                )
                else result
            }

            override fun visitFunctionReference(expression: IrFunctionReference): IrExpression {
                val result = super.visitFunctionReference(expression) as IrFunctionReference
                val transformed = transformFunctionIfNeeded(result.symbol.owner)
                return if (transformed in transformedFunctions.values) IrFunctionReferenceImpl(
                    result.startOffset,
                    result.endOffset,
                    transformed.getFunctionType(pluginContext),
                    transformed.symbol,
                    transformed.typeParameters.size,
                    transformed.valueParameters.size,
                    result.reflectionTarget,
                    result.origin
                )
                else result
            }

            override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
                val result = super.visitFunctionAccess(expression) as IrFunctionAccessExpression
                if (result !is IrCall &&
                    result !is IrConstructorCall &&
                    result !is IrDelegatingConstructorCall
                ) return result
                val transformed = transformFunctionIfNeeded(result.symbol.owner)
                return if (transformed in transformedFunctions.values) transformCall(
                    transformed,
                    result
                )
                else result
            }
        })
    }

}