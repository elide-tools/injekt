/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(UnsafeCastFunction::class)

package com.ivianuu.injekt.compiler.transform

import com.ivianuu.injekt.compiler.Context
import com.ivianuu.injekt.compiler.DISPATCH_RECEIVER_INDEX
import com.ivianuu.injekt.compiler.EXTENSION_RECEIVER_INDEX
import com.ivianuu.injekt.compiler.INJECTION_RESULT_KEY
import com.ivianuu.injekt.compiler.InjektFqNames
import com.ivianuu.injekt.compiler.SourcePosition
import com.ivianuu.injekt.compiler.allParametersWithContext
import com.ivianuu.injekt.compiler.asNameId
import com.ivianuu.injekt.compiler.cachedOrNull
import com.ivianuu.injekt.compiler.injektIndex
import com.ivianuu.injekt.compiler.resolution.CallableInjectable
import com.ivianuu.injekt.compiler.resolution.CallableRef
import com.ivianuu.injekt.compiler.resolution.InjectableRequest
import com.ivianuu.injekt.compiler.resolution.InjectablesScope
import com.ivianuu.injekt.compiler.resolution.InjectionResult
import com.ivianuu.injekt.compiler.resolution.ListInjectable
import com.ivianuu.injekt.compiler.resolution.ProviderInjectable
import com.ivianuu.injekt.compiler.resolution.ResolutionResult
import com.ivianuu.injekt.compiler.resolution.SourceKeyInjectable
import com.ivianuu.injekt.compiler.resolution.TypeKeyInjectable
import com.ivianuu.injekt.compiler.resolution.TypeRef
import com.ivianuu.injekt.compiler.resolution.isSubTypeOf
import com.ivianuu.injekt.compiler.resolution.render
import com.ivianuu.injekt.compiler.resolution.unwrapTags
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.FirIncompatiblePluginAPI
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.builtins.isFunctionOrSuspendFunctionType
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.descriptors.impl.LocalVariableAccessorDescriptor
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.builders.Scope
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.declarations.isPropertyAccessor
import org.jetbrains.kotlin.ir.declarations.name
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.utils.addToStdlib.UnsafeCastFunction
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set

@OptIn(FirIncompatiblePluginAPI::class, ObsoleteDescriptorBasedAPI::class)
class InjectCallTransformer(
  private val localDeclarations: LocalDeclarations,
  private val irCtx: IrPluginContext,
  private val ctx: Context
) : IrElementTransformerVoidWithContext() {
  private inner class RootContext(val result: InjectionResult.Success, val startOffset: Int) {
    val statements = mutableListOf<IrStatement>()

    var declarationIndex = 0

    fun mapScopeIfNeeded(scope: InjectablesScope) =
      if (scope in result.scope.allScopes) result.scope else scope
  }

  private inner class ScopeContext(
    val parent: ScopeContext?,
    val rootContext: RootContext,
    val scope: InjectablesScope,
    val irScope: Scope
  ) {
    val symbol = irScope.scopeOwnerSymbol
    val functionWrappedExpressions = mutableMapOf<TypeRef, ScopeContext.() -> IrExpression>()
    val statements =
      if (scope == rootContext.result.scope) rootContext.statements else mutableListOf()
    val parameterMap: MutableMap<ParameterDescriptor, IrValueParameter> =
      parent?.parameterMap ?: mutableMapOf()

    fun findScopeContext(scopeToFind: InjectablesScope): ScopeContext {
      val finalScope = rootContext.mapScopeIfNeeded(scopeToFind)
      if (finalScope == scope) return this@ScopeContext
      return parent!!.findScopeContext(finalScope)
    }

    fun expressionFor(result: ResolutionResult.Success.Value): IrExpression {
      val scopeContext = findScopeContext(result.scope)
      return scopeContext.expressionForImpl(result)
    }

    private fun expressionForImpl(result: ResolutionResult.Success.Value): IrExpression =
      wrapExpressionInFunctionIfNeeded(result) {
        when (val candidate = result.candidate) {
          is CallableInjectable -> callableExpression(result, candidate)
          is ProviderInjectable -> providerExpression(result, candidate)
          is ListInjectable -> listExpression(result, candidate)
          is SourceKeyInjectable -> sourceKeyExpression()
          is TypeKeyInjectable -> typeKeyExpression(result, candidate)
        }
      }
  }

  private fun IrFunctionAccessExpression.inject(
    ctx: ScopeContext,
    results: Map<InjectableRequest, ResolutionResult.Success>
  ) {
    for ((request, result) in results) {
      if (result !is ResolutionResult.Success.Value) continue
      val expression = ctx.expressionFor(result)
      when (request.parameterIndex) {
        DISPATCH_RECEIVER_INDEX -> dispatchReceiver = expression
        EXTENSION_RECEIVER_INDEX -> extensionReceiver = expression
        else -> putValueArgument(
          symbol.owner
            .valueParameters
            .first { it.descriptor.injektIndex(this@InjectCallTransformer.ctx) == request.parameterIndex }
            .index,
          expression
        )
      }
    }
  }

  private fun ResolutionResult.Success.Value.shouldWrap(
    ctx: RootContext
  ): Boolean = dependencyResults.isNotEmpty() && ctx.result.usages[usageKey]!!.size > 1

  private fun ScopeContext.wrapExpressionInFunctionIfNeeded(
    result: ResolutionResult.Success.Value,
    unwrappedExpression: () -> IrExpression
  ): IrExpression = if (!result.shouldWrap(rootContext)) unwrappedExpression()
  else with(result.safeAs<ResolutionResult.Success.Value>()
    ?.highestScope?.let { findScopeContext(it) } ?: this) {
    functionWrappedExpressions.getOrPut(result.candidate.type) expression@ {
      val function = IrFactoryImpl.buildFun {
        origin = IrDeclarationOrigin.DEFINED
        name = "function${rootContext.declarationIndex++}".asNameId()
        returnType = result.candidate.type.toIrType(irCtx, localDeclarations, ctx)
          .typeOrNull!!
        visibility = DescriptorVisibilities.LOCAL
      }.apply {
        parent = irScope.getLocalDeclarationParent()

        body = DeclarationIrBuilder(irCtx, symbol).run {
          irBlockBody {
            +irReturn(unwrappedExpression())
          }
        }

        statements += this
      }

      return@expression {
        DeclarationIrBuilder(irCtx, symbol)
          .irCall(
            function.symbol,
            result.candidate.type.toIrType(irCtx, localDeclarations, ctx).typeOrNull!!
          )
      }
    }
  }.invoke(this)

  private fun ScopeContext.providerExpression(
    result: ResolutionResult.Success.Value,
    injectable: ProviderInjectable
  ): IrExpression = DeclarationIrBuilder(irCtx, symbol)
    .irLambda(
      injectable.type.toIrType(irCtx, localDeclarations, ctx).typeOrNull!!,
      parameterNameProvider = { "p${rootContext.declarationIndex++}" }
    ) { function ->
      val dependencyResult = result.dependencyResults.values.single()
      val dependencyScopeContext = if (injectable.dependencyScope == this@providerExpression.scope) null
      else ScopeContext(
        this@providerExpression, rootContext,
        injectable.dependencyScope, scope
      )

      fun ScopeContext.createExpression(): IrExpression {
        for ((index, parameter) in injectable.parameterDescriptors.withIndex())
          parameterMap[parameter] = function.valueParameters[index]
        return expressionFor(dependencyResult.cast())
          .also {
            injectable.parameterDescriptors.forEach {
              parameterMap -= it
            }
          }
      }

      val expression = dependencyScopeContext?.run { createExpression() } ?: createExpression()

      if (dependencyScopeContext == null || dependencyScopeContext.statements.isEmpty()) expression
      else irBlock {
        dependencyScopeContext.statements.forEach { +it }
        +expression
      }
    }

  private val mutableListOf = irCtx.referenceFunctions(
    FqName("kotlin.collections.mutableListOf")
  ).single { it.owner.valueParameters.isEmpty() }
  private val listAdd = mutableListOf.owner.returnType
    .classOrNull!!
    .owner
    .functions
    .single { it.name.asString() == "add" && it.valueParameters.size == 1 }
  private val listAddAll = mutableListOf.owner.returnType
    .classOrNull!!
    .owner
    .functions
    .single { it.name.asString() == "addAll" && it.valueParameters.size == 1 }

  private fun ScopeContext.listExpression(
    result: ResolutionResult.Success.Value,
    injectable: ListInjectable
  ): IrExpression = DeclarationIrBuilder(irCtx, symbol).irBlock {
    val tmpList = irTemporary(
      irCall(mutableListOf)
        .apply {
          putTypeArgument(
            0,
            injectable.singleElementType.toIrType(irCtx, localDeclarations, ctx).typeOrNull
          )
        },
      nameHint = "${rootContext.declarationIndex++}"
    )

    result.dependencyResults
      .forEach { (_, dependency) ->
        if (dependency !is ResolutionResult.Success.Value)
          return@forEach
        if (dependency.candidate.type.isSubTypeOf(injectable.collectionElementType, ctx)) {
          +irCall(listAddAll).apply {
            dispatchReceiver = irGet(tmpList)
            putValueArgument(0, expressionFor(dependency))
          }
        } else {
          +irCall(listAdd).apply {
            dispatchReceiver = irGet(tmpList)
            putValueArgument(0, expressionFor(dependency))
          }
        }
      }

    +irGet(tmpList)
  }

  private val sourceKeyConstructor = irCtx.referenceClass(InjektFqNames.SourceKey)
    ?.constructors?.single()

  private fun ScopeContext.sourceKeyExpression(): IrExpression =
    DeclarationIrBuilder(irCtx, symbol).run {
      irCall(sourceKeyConstructor!!).apply {
        putValueArgument(
          0,
          irString(
            buildString {
              append(currentFile.name)
              append(":")
              append(currentFile.fileEntry.getLineNumber(rootContext.startOffset) + 1)
              append(":")
              append(currentFile.fileEntry.getColumnNumber(rootContext.startOffset))
            }
          )
        )
      }
    }

  private val typeKey = irCtx.referenceClass(InjektFqNames.TypeKey)
  private val typeKeyValue = typeKey?.owner?.properties
    ?.single { it.name.asString() == "value" }
  private val typeKeyConstructor = typeKey?.constructors?.single()
  private val stringPlus = irCtx.irBuiltIns.stringClass
    .functions
    .map { it.owner }
    .first { it.name.asString() == "plus" }

  private fun ScopeContext.typeKeyExpression(
    result: ResolutionResult.Success.Value,
    injectable: TypeKeyInjectable
  ): IrExpression = DeclarationIrBuilder(irCtx, symbol).run {
    val expressions = mutableListOf<IrExpression>()
    var currentString = ""
    fun commitCurrentString() {
      if (currentString.isNotEmpty()) {
        expressions += irString(currentString)
        currentString = ""
      }
    }

    fun appendToCurrentString(value: String) {
      currentString += value
    }

    fun appendTypeParameterExpression(expression: IrExpression) {
      commitCurrentString()
      expressions += expression
    }

    injectable.type.arguments.single().render(
      renderType = { typeToRender ->
        if (!typeToRender.classifier.isTypeParameter) true else {
          appendTypeParameterExpression(
            irCall(typeKeyValue!!.getter!!).apply {
              dispatchReceiver = expressionFor(
                result.dependencyResults.values.single {
                  it is ResolutionResult.Success.Value &&
                      it.candidate.type.arguments.single().classifier == typeToRender.classifier
                }.cast()
              )
            }
          )
          false
        }
      },
      append = { appendToCurrentString(it) }
    )

    commitCurrentString()

    val stringExpression = if (expressions.size == 1) {
      expressions.single()
    } else {
      expressions.reduce { acc, expression ->
        irCall(stringPlus).apply {
          dispatchReceiver = acc
          putValueArgument(0, expression)
        }
      }
    }

    irCall(typeKeyConstructor!!).apply {
      putTypeArgument(0, injectable.type.arguments.single().toIrType(irCtx, localDeclarations, ctx).cast())
      putValueArgument(0, stringExpression)
    }
  }

  private fun ScopeContext.callableExpression(
    result: ResolutionResult.Success.Value,
    injectable: CallableInjectable
  ): IrExpression = when (injectable.callable.callable) {
    is ClassConstructorDescriptor -> classExpression(
      result,
      injectable,
      injectable.callable.callable
    )
    is PropertyDescriptor -> propertyExpression(
      result,
      injectable,
      injectable.callable.callable
    )
    is FunctionDescriptor -> functionExpression(
      result,
      injectable,
      injectable.callable.callable
    )
    is ReceiverParameterDescriptor -> if (injectable.callable.type.unwrapTags().classifier.isObject)
      objectExpression(injectable.callable.type.unwrapTags())
    else parameterExpression(injectable.callable.callable, injectable)
    is ValueParameterDescriptor -> parameterExpression(injectable.callable.callable, injectable)
    is VariableDescriptor -> variableExpression(injectable.callable.callable, injectable)
    else -> error("Unsupported callable $injectable")
  }

  private fun ScopeContext.classExpression(
    result: ResolutionResult.Success.Value,
    injectable: CallableInjectable,
    descriptor: ClassConstructorDescriptor
  ): IrExpression = if (descriptor.constructedClass.kind == ClassKind.OBJECT) {
    val clazz = descriptor.constructedClass.irClass(ctx, irCtx, localDeclarations)
    DeclarationIrBuilder(irCtx, symbol)
      .irGetObject(clazz.symbol)
  } else {
    val constructor = descriptor.irConstructor(ctx, irCtx, localDeclarations)
    DeclarationIrBuilder(irCtx, symbol)
      .irCall(
        constructor.symbol,
        injectable.type.toIrType(irCtx, localDeclarations, ctx).typeOrNull!!
      )
      .apply {
        fillTypeParameters(injectable.callable)
        inject(this@classExpression, result.dependencyResults)
      }
  }

  private fun ScopeContext.objectExpression(type: TypeRef): IrExpression =
    DeclarationIrBuilder(irCtx, symbol)
      .irGetObject(irCtx.referenceClass(type.classifier.fqName)!!)

  private fun ScopeContext.propertyExpression(
    result: ResolutionResult.Success.Value,
    injectable: CallableInjectable,
    descriptor: PropertyDescriptor
  ): IrExpression {
    val property = descriptor.irProperty(ctx, irCtx, localDeclarations)
    val getter = property.getter!!
    return DeclarationIrBuilder(irCtx, symbol)
      .irCall(
        getter.symbol,
        injectable.type.toIrType(irCtx, localDeclarations, ctx).typeOrNull!!
      )
      .apply {
        fillTypeParameters(injectable.callable)
        inject(this@propertyExpression, result.dependencyResults)
      }
  }

  private fun ScopeContext.functionExpression(
    result: ResolutionResult.Success.Value,
    injectable: CallableInjectable,
    descriptor: FunctionDescriptor
  ): IrExpression {
    val function = descriptor.irFunction(ctx, irCtx, localDeclarations)
    return DeclarationIrBuilder(irCtx, symbol)
      .irCall(function.symbol, injectable.type.toIrType(irCtx, localDeclarations, ctx).typeOrNull!!)
      .apply {
        fillTypeParameters(injectable.callable)
        inject(this@functionExpression, result.dependencyResults)
      }
  }

  private fun receiverExpression(
    descriptor: ParameterDescriptor
  ): IrExpression = receiverAccessors.lastOrNull {
    descriptor.type.constructor.declarationDescriptor == it.first.descriptor
  }?.second?.invoke() ?: throw AssertionError("unexpected receiver $descriptor")

  private fun ScopeContext.parameterExpression(
    descriptor: ParameterDescriptor,
    injectable: CallableInjectable
  ): IrExpression =
    when (val containingDeclaration = descriptor.containingDeclaration) {
      is ClassDescriptor -> receiverExpression(descriptor)
      is ClassConstructorDescriptor -> DeclarationIrBuilder(irCtx, symbol)
        .irGet(
          injectable.type.toIrType(irCtx, localDeclarations, ctx).typeOrNull!!,
          containingDeclaration.irConstructor(ctx, irCtx, localDeclarations)
            .allParametersWithContext
            .single { it.descriptor.injektIndex(this@InjectCallTransformer.ctx) == descriptor.injektIndex(this@InjectCallTransformer.ctx) }
            .symbol
        )
      is FunctionDescriptor -> DeclarationIrBuilder(irCtx, symbol)
        .irGet(
          injectable.type.toIrType(irCtx, localDeclarations, ctx).typeOrNull!!,
          (parameterMap[descriptor] ?: containingDeclaration.irFunction(ctx, irCtx, localDeclarations)
            .allParametersWithContext
            .single { it.descriptor.injektIndex(this@InjectCallTransformer.ctx) == descriptor.injektIndex(this@InjectCallTransformer.ctx) })
            .symbol
        )
      is PropertyDescriptor -> DeclarationIrBuilder(irCtx, symbol)
        .irGet(
          injectable.type.toIrType(irCtx, localDeclarations, ctx).typeOrNull!!,
          parameterMap[descriptor]?.symbol ?:
          if (descriptor.injektIndex(this@InjectCallTransformer.ctx) == EXTENSION_RECEIVER_INDEX)
            containingDeclaration.irProperty(ctx, irCtx, localDeclarations)
              .getter!!.extensionReceiverParameter!!.symbol
          else
            containingDeclaration.irProperty(ctx, irCtx, localDeclarations)
              .getter!!.valueParameters[descriptor.injektIndex(this@InjectCallTransformer.ctx)].symbol
        )
      else -> error("Unexpected parent $descriptor $containingDeclaration")
    }

  private fun IrFunctionAccessExpression.fillTypeParameters(callable: CallableRef) {
    callable
      .typeArguments
      .values
      .forEachIndexed { index, typeArgument ->
        putTypeArgument(index, typeArgument.toIrType(irCtx, localDeclarations, ctx).typeOrNull)
      }
  }

  private fun ScopeContext.variableExpression(
    descriptor: VariableDescriptor,
    injectable: CallableInjectable
  ): IrExpression = if (descriptor is LocalVariableDescriptor && descriptor.isDelegated) {
    val localFunction = localDeclarations.localFunctions.single { candidateFunction ->
      candidateFunction.descriptor
        .safeAs<LocalVariableAccessorDescriptor.Getter>()
        ?.correspondingVariable == descriptor
    }
    DeclarationIrBuilder(irCtx, symbol)
      .irCall(
        localFunction.symbol,
        injectable.type.toIrType(irCtx, localDeclarations, ctx).typeOrNull!!
      )
  } else {
    DeclarationIrBuilder(irCtx, symbol)
      .irGet(
        injectable.type.toIrType(irCtx, localDeclarations, ctx).typeOrNull!!,
        localDeclarations.localVariables.single { it.descriptor == descriptor }.symbol
      )
  }

  private val receiverAccessors = mutableListOf<Pair<IrClass, () -> IrExpression>>()

  override fun visitClassNew(declaration: IrClass): IrStatement {
    receiverAccessors.push(
      declaration to {
        DeclarationIrBuilder(irCtx, declaration.symbol)
          .irGet(declaration.thisReceiver!!)
      }
    )
    val result = super.visitClassNew(declaration)
    receiverAccessors.pop()
    return result
  }

  override fun visitFunctionNew(declaration: IrFunction): IrStatement {
    val dispatchReceiver = declaration.dispatchReceiverParameter?.type?.classOrNull?.owner
    if (dispatchReceiver != null) {
      receiverAccessors.push(
        dispatchReceiver to {
          DeclarationIrBuilder(irCtx, declaration.symbol)
            .irGet(declaration.dispatchReceiverParameter!!)
        }
      )
    }
    val extensionReceiver = declaration.extensionReceiverParameter?.type?.classOrNull?.owner
    if (extensionReceiver != null) {
      receiverAccessors.push(
        extensionReceiver to {
          DeclarationIrBuilder(irCtx, declaration.symbol)
            .irGet(declaration.extensionReceiverParameter!!)
        }
      )
    }
    val result = super.visitFunctionNew(declaration)
    if (dispatchReceiver != null) receiverAccessors.pop()
    if (extensionReceiver != null) receiverAccessors.pop()
    return result
  }

  override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
    val result = super.visitFunctionAccess(expression) as IrFunctionAccessExpression

    val injectionResult = ctx.cachedOrNull<_, InjectionResult.Success?>(
      INJECTION_RESULT_KEY,
      SourcePosition(currentFile.fileEntry.name, result.startOffset, result.endOffset)
    ) ?: return result

    // some ir transformations reuse the start and end offsets
    // we ensure that were not transforming wrong calls
    if (!expression.symbol.owner.isPropertyAccessor &&
      expression.symbol.owner.descriptor.containingDeclaration
        .safeAs<ClassDescriptor>()
        ?.defaultType
        ?.isFunctionOrSuspendFunctionType != true &&
      injectionResult.callee.callable.fqNameSafe != result.symbol.owner.descriptor.fqNameSafe)
      return result

    return DeclarationIrBuilder(irCtx, result.symbol)
      .irBlock {
        val rootContext = RootContext(injectionResult, result.startOffset)
        try {
          ScopeContext(
            parent = null,
            rootContext = rootContext,
            scope = injectionResult.scope,
            irScope = scope
          ).run { result.inject(this, injectionResult.results) }
        } catch (e: Throwable) {
          throw RuntimeException("Wtf ${expression.dump()}", e)
        }
        rootContext.statements.forEach { +it }
        +result
      }
  }
}