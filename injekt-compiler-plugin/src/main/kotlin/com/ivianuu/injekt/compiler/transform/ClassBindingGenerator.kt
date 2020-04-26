package com.ivianuu.injekt.compiler.transform

import com.ivianuu.injekt.compiler.InjektFqNames
import com.ivianuu.injekt.compiler.getAnnotatedAnnotations
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.addChild
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.ir.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDelegatingConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.referenceFunction
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi2ir.findFirstFunction
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.utils.addToStdlib.cast

class ClassBindingGenerator(
    pluginContext: IrPluginContext,
    private val project: Project
) : AbstractInjektTransformer(pluginContext) {

    override fun visitModuleFragment(declaration: IrModuleFragment): IrModuleFragment {
        val classes = mutableMapOf<IrClass, IrConstructor>()

        declaration.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitClass(declaration: IrClass): IrStatement {
                if (declaration.descriptor.getAnnotatedAnnotations(InjektFqNames.Scope)
                        .singleOrNull() != null
                ) {
                    classes[declaration] = declaration.primaryConstructor!!
                }
                return super.visitClass(declaration)
            }

            override fun visitConstructor(declaration: IrConstructor): IrStatement {
                if (declaration.descriptor.getAnnotatedAnnotations(InjektFqNames.Scope)
                        .singleOrNull() != null
                ) {
                    classes[declaration.constructedClass] = declaration
                }
                return super.visitConstructor(declaration)
            }
        })

        classes.forEach { (clazz, constructor) ->
            DeclarationIrBuilder(context, clazz.symbol).run {
                val bindingClassName = Name.identifier("${clazz.name}\$Binding")
                val bindingClass = if (constructor.valueParameters.isNotEmpty()) {
                    val linked = linkedBinding(
                        constructor,
                        Name.identifier("Linked"),
                        Visibilities.PRIVATE
                    )
                    val unlinked =
                        unlinkedBinding(constructor, linked.constructors.single(), bindingClassName)
                            .also { it.addScope(clazz) }
                    unlinked.addChild(linked)
                    unlinked
                } else {
                    linkedBinding(constructor, bindingClassName, Visibilities.PUBLIC)
                        .also { it.addScope(clazz) }
                }

                clazz.addChild(bindingClass)
                declaration.addClass(
                    this@ClassBindingGenerator.context.psiSourceManager.cast(),
                    project,
                    aggregateClass(clazz, bindingClass),
                    symbols.aggregatePackage.fqName
                )
            }
        }

        return super.visitModuleFragment(declaration)
    }

    private fun IrBuilderWithScope.aggregateClass(
        clazz: IrClass,
        provider: IrClass
    ) = buildClass {
        this.name = Name.identifier(
            clazz.descriptor.fqNameSafe
                .asString().replace(".", "_")
        )
    }.apply clazz@{
        createImplicitParameterDeclarationWithWrappedDescriptor()

        annotations += DeclarationIrBuilder(context, clazz.symbol)
            .irCall(symbols.jitBindingMetadata.constructors.single()).apply {
                putValueArgument(
                    0,
                    IrClassReferenceImpl(
                        startOffset,
                        endOffset,
                        this@ClassBindingGenerator.context.irBuiltIns.kClassClass.typeWith(
                            clazz.defaultType
                        ),
                        clazz.defaultType.classifierOrFail,
                        clazz.defaultType
                    )
                )
                putValueArgument(
                    1,
                    IrClassReferenceImpl(
                        startOffset,
                        endOffset,
                        this@ClassBindingGenerator.context.irBuiltIns.kClassClass.typeWith(
                            provider.defaultType
                        ),
                        provider.defaultType.classifierOrFail,
                        provider.defaultType
                    )
                )
            }

        addConstructor {
            returnType = defaultType
            isPrimary = true
        }.apply {
            body = DeclarationIrBuilder(context, symbol).irBlockBody {
                +IrDelegatingConstructorCallImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    context.irBuiltIns.unitType,
                    context.irBuiltIns.anyClass
                        .constructors.single()
                )
                +IrInstanceInitializerCallImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    this@clazz.symbol,
                    context.irBuiltIns.unitType
                )
            }
        }
    }

    private fun IrBuilderWithScope.unlinkedBinding(
        constructor: IrConstructor,
        linkedConstructor: IrConstructor,
        name: Name
    ) = buildClass {
        kind = ClassKind.OBJECT
        this.name = name
    }.apply clazz@{
        createImplicitParameterDeclarationWithWrappedDescriptor()

        superTypes += symbols.unlinkedBinding
            .typeWith(constructor.returnType)

        addConstructor {
            returnType = defaultType
            isPrimary = true
        }.apply {
            body = irBlockBody {
                +IrDelegatingConstructorCallImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    context.irBuiltIns.unitType,
                    symbols.unlinkedBinding
                        .constructors.single()
                )
                +IrInstanceInitializerCallImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    this@clazz.symbol,
                    context.irBuiltIns.unitType
                )
            }
        }

        addFunction {
            this.name = Name.identifier("link")
            returnType = symbols.linkedBinding
                .typeWith(constructor.returnType)
        }.apply {
            dispatchReceiverParameter = thisReceiver?.copyTo(this, type = defaultType)

            overriddenSymbols += symbols.unlinkedBinding
                .functions
                .single { it.owner.name.asString() == "link" }

            val linkerParameter = addValueParameter(
                "linker",
                symbols.linker.defaultType
            )

            body = irExprBody(
                irCall(linkedConstructor).apply {
                    constructor.valueParameters
                        .filterNot { it.hasAnnotation(InjektFqNames.Param) }
                        .forEachIndexed { index, constructorParam ->
                            val linkerGet = symbols.linker.functions
                                .single { function ->
                                    function.descriptor.name.asString() == "get" &&
                                            function.descriptor.valueParameters.first().name.asString() == "qualifier"
                                }

                            putValueArgument(
                                index,
                                irCall(linkerGet).apply {
                                dispatchReceiver = irGet(linkerParameter)
                                putTypeArgument(0, constructorParam.type)

                                val qualifier = constructorParam.descriptor.getAnnotatedAnnotations(
                                        InjektFqNames.Qualifier
                                    )
                                    .singleOrNull()?.fqName
                                    ?.let { symbols.getTopLevelClass(it) }

                                if (qualifier != null) {
                                    putValueArgument(
                                        0,
                                        IrClassReferenceImpl(
                                            startOffset,
                                            endOffset,
                                            this@ClassBindingGenerator.context.irBuiltIns.kClassClass.typeWith(
                                                qualifier.defaultType
                                            ),
                                            qualifier.defaultType.classifierOrFail,
                                            qualifier.defaultType
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
            )
        }
    }

    private fun IrBuilderWithScope.linkedBinding(
        constructor: IrConstructor,
        name: Name,
        visibility: Visibility
    ) = buildClass {
        kind = if (constructor.valueParameters
                .filterNot { it.hasAnnotation(InjektFqNames.Param) }
                .isNotEmpty()
        ) ClassKind.CLASS else ClassKind.OBJECT
        this.name = name
        this.visibility = visibility
    }.apply clazz@{
        createImplicitParameterDeclarationWithWrappedDescriptor()

        superTypes += symbols.linkedBinding
            .typeWith(constructor.returnType)

        var fieldIndex = 0
        val providerFieldsByDependencies = constructor.valueParameters
            .filterNot { it.hasAnnotation(InjektFqNames.Param) }
            .associateWith {
                addField(
                    "p${fieldIndex++}",
                    symbols.provider.typeWith(it.type)
                )
            }

        addConstructor {
            returnType = defaultType
            isPrimary = true
        }.apply {
            val valueParametersByFields = providerFieldsByDependencies.values.associateWith {
                addValueParameter(
                    it.name.asString(),
                    it.type
                )
            }

            body = irBlockBody {
                +IrDelegatingConstructorCallImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    context.irBuiltIns.unitType,
                    symbols.linkedBinding
                        .constructors.single()
                )
                +IrInstanceInitializerCallImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    this@clazz.symbol,
                    context.irBuiltIns.unitType
                )

                valueParametersByFields.forEach { (field, valueParameter) ->
                    +irSetField(
                        irGet(thisReceiver!!),
                        field,
                        irGet(valueParameter)
                    )
                }
            }
        }

        addFunction {
            this.name = Name.identifier("invoke")
            returnType = constructor.returnType
        }.apply {
            dispatchReceiverParameter = thisReceiver?.copyTo(this, type = defaultType)

            overriddenSymbols += symbols.provider
                .functions
                .single {
                    it.descriptor.valueParameters.size == 1 &&
                            it.owner.name.asString() == "invoke"
                }

            val parametersParameter = addValueParameter(
                "parameters",
                symbols.parameters.defaultType
            )

            val parametersGet = symbols.parameters.functions
                .single { it.owner.name.asString() == "get" }

            body = irExprBody(
                irCall(constructor).apply {
                    var constructorIndex = 0
                    var paramIndex = 0
                    constructor.valueParameters.forEach { constructorParameter ->
                        if (constructorParameter.hasAnnotation(InjektFqNames.Param)) {
                            putValueArgument(
                                constructorIndex++,
                                irCall(parametersGet).apply {
                                    putTypeArgument(0, constructorParameter.type)
                                    putValueArgument(0, irInt(paramIndex++))
                                }
                            )
                        } else {
                            val field = providerFieldsByDependencies.getValue(constructorParameter)
                            putValueArgument(
                                constructorIndex++,
                                irCall(
                                    symbolTable.referenceFunction(
                                        symbols.provider.descriptor.unsubstitutedMemberScope.findFirstFunction(
                                            "invoke"
                                        ) {
                                            it.valueParameters.size == 0
                                        }
                                    ),
                                    (field.type as IrSimpleType).arguments.single().typeOrNull!!
                                ).apply {
                                    dispatchReceiver = irGetField(
                                        irGet(dispatchReceiverParameter!!),
                                        field
                                    )
                                }
                            )
                        }
                    }
                }
            )
        }
    }

    private fun IrClass.addScope(clazz: IrClass) {
        val scope = clazz.descriptor.getAnnotatedAnnotations(InjektFqNames.Scope)
            .single()
            .fqName!!
            .let { symbols.getTopLevelClass(it) }

        if (scope == symbols.factory) return

        superTypes += symbols.hasScope.defaultType

        addProperty {
            this.name = Name.identifier("scope")
        }.apply {
            backingField = buildField {
                type = context.irBuiltIns.kClassClass.starProjectedType
            }.apply {
                initializer = DeclarationIrBuilder(context, symbol).irExprBody(
                    IrClassReferenceImpl(
                        startOffset,
                        endOffset,
                        this@ClassBindingGenerator.context.irBuiltIns.kClassClass.typeWith(
                            scope.defaultType
                        ),
                        scope.defaultType.classifierOrFail,
                        scope.defaultType
                    )
                )
            }
        }
    }

}
