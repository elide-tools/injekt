/*
 * Copyright 2021 Manuel Wrage
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

@file:Providers("com.ivianuu.injekt.compiler.bindingContext")

package com.ivianuu.injekt.compiler.resolution

import com.ivianuu.injekt.Inject
import com.ivianuu.injekt.Providers
import com.ivianuu.injekt.compiler.InjektFqNames
import com.ivianuu.injekt.compiler.analysis.AnalysisContext
import com.ivianuu.injekt.compiler.descriptor
import com.ivianuu.injekt.compiler.hasAnnotation
import com.ivianuu.injekt.compiler.injektIndex
import com.ivianuu.injekt.compiler.injektName
import com.ivianuu.injekt.compiler.isExternalDeclaration
import org.jetbrains.kotlin.backend.common.descriptors.allParameters
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotatedExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSuperTypeList
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.isObjectLiteral
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

fun ElementInjectablesScope(
  element: KtElement,
  position: KtElement = element,
  @Inject context: AnalysisContext
): InjectablesScope {
  val scopeOwner = element.parentsWithSelf
    .first { (it as KtElement).isScopeOwner(position) }
    .cast<KtElement>()

  if (scopeOwner !is KtBlockExpression)
    context.injektContext.elementScopes[scopeOwner]?.let { return it }

  val parentScope = scopeOwner.parents
    .firstOrNull { (it as KtElement).isScopeOwner(position) }
    ?.let { ElementInjectablesScope(it.cast(), position) }

  val scope = when (scopeOwner) {
    is KtFile -> FileInjectablesScope(scopeOwner)
    is KtClassOrObject -> ClassInjectablesScope(
      scopeOwner.descriptor()!!,
      parentScope!!
    )
    is KtConstructor<*> -> {
      if (scopeOwner.bodyExpression.let { it == null || it !in position.parents }) {
        ConstructorPreInitInjectablesScope(
          scopeOwner.descriptor()!!,
          parentScope!!
        )
      } else FunctionInjectablesScope(
        scopeOwner.descriptor()!!,
        parentScope!!
      )
    }
    is KtFunction -> FunctionInjectablesScope(
      scopeOwner.descriptor()!!,
      parentScope!!
    )
    is KtParameter -> ValueParameterDefaultValueInjectablesScope(
      scopeOwner.descriptor()!!,
      parentScope!!
    )
    is KtProperty -> {
      when (val descriptor = scopeOwner.descriptor<VariableDescriptor>()!!) {
        is PropertyDescriptor -> {
          if (scopeOwner.delegateExpressionOrInitializer != null &&
            scopeOwner.delegateExpressionOrInitializer!! in element.parentsWithSelf)
            PropertyInitInjectablesScope(descriptor, parentScope!!, position)
          else
            PropertyInjectablesScope(descriptor, parentScope!!)
        }
        is LocalVariableDescriptor -> LocalVariableInjectablesScope(descriptor, parentScope!!)
        else -> throw AssertionError("Unexpected variable descriptor $descriptor")
      }
    }
    is KtSuperTypeList -> scopeOwner.getParentOfType<KtClassOrObject>(false)
      ?.descriptor<ClassDescriptor>()
      ?.unsubstitutedPrimaryConstructor
      ?.let { ConstructorPreInitInjectablesScope(it, parentScope!!) }
      ?: parentScope!!
    is KtClassInitializer -> ClassInitInjectablesScope(
      clazz = scopeOwner.getParentOfType<KtClassOrObject>(false)!!.descriptor()!!,
      parent = parentScope!!,
      position = position
    )
    is KtClassBody -> scopeOwner.getParentOfType<KtClassOrObject>(false)
      ?.descriptor<ClassDescriptor>()
      ?.unsubstitutedPrimaryConstructor
      ?.let { FunctionInjectablesScope(it, parentScope!!) }
      ?: parentScope!!
    is KtBlockExpression -> BlockExpressionInjectablesScope(scopeOwner, position, parentScope!!)
    is KtAnnotatedExpression -> ExpressionInjectablesScope(scopeOwner, parentScope!!)
    else -> throw AssertionError("Unexpected scope owner $scopeOwner")
  }

  if (scopeOwner !is KtBlockExpression)
    context.injektContext.elementScopes[scopeOwner] = scope

  return scope
}

private fun KtElement.isScopeOwner(position: KtElement): Boolean {
  if (this is KtFile ||
    this is KtClassInitializer ||
    this is KtProperty ||
    this is KtParameter ||
    this is KtSuperTypeList ||
    this is KtBlockExpression)
    return true

  if (this is KtFunction && position.parents.none { it in valueParameters })
    return true

  if (this is KtClassOrObject) {
    val propertyInitializerOrDelegateExpression = position.getParentOfType<KtProperty>(false)
      ?.takeUnless { it.isLocal }
      ?.delegateExpressionOrInitializer

    if (propertyInitializerOrDelegateExpression != null) {
      val parentsBetweenInitializerAndPosition = position.parentsWithSelf
        .takeWhile { it != propertyInitializerOrDelegateExpression }
        .toList()
      if (parentsBetweenInitializerAndPosition.none {
          it is KtNamedDeclaration || it is KtClassOrObject || it is KtFunctionLiteral
        })
          return false
    }

    val classInitializer = position.getParentOfType<KtClassInitializer>(false)

    if (classInitializer != null) {
      val parentsBetweenInitializerAndPosition = position.parents
        .takeWhile { it != classInitializer }
        .toList()
      if (parentsBetweenInitializerAndPosition.none {
          it is KtNamedDeclaration || it is KtClassOrObject || it is KtFunctionLiteral
      })
        return false
    }
  }

  if (this is KtObjectDeclaration)
    return true

  if (this is KtAnnotatedExpression && hasAnnotation(InjektFqNames.Providers))
    return true

  if (this is KtClass && position.getParentOfType<KtSuperTypeList>(false) == null) {
    val constructor = position.getParentOfType<KtConstructor<*>>(false)
    if (constructor != null &&
      constructor.bodyExpression.let { it == null || it !in position.parents })
      return false

    val allClassesBetween = position.parentsWithSelf
      .filterIsInstance<KtClassOrObject>()
      .takeWhile { it != this }
      .toList()
    val nearestClassFromPosition = allClassesBetween.firstOrNull()
      ?: return true

    return (nearestClassFromPosition !is KtObjectDeclaration ||
        nearestClassFromPosition.isObjectLiteral()) &&
        allClassesBetween.all {
          it.hasModifier(KtTokens.INNER_KEYWORD) ||
              it.isObjectLiteral()
        }
  }

  if (this is KtClassBody && position.parents
      .takeWhile { it != this }
      .none { parent ->
        parent is KtClassInitializer ||
            (parent is KtProperty && parent.delegateExpressionOrInitializer != null &&
                parent.delegateExpressionOrInitializer!! in position.parents
              .takeWhile { it !is KtFunctionLiteral }) ||
            parent is KtClass ||
            (parent is KtNamedFunction && parent.parent == this) ||
            (parent is KtPropertyAccessor && parent.property.parent == this)
      })
        return true

  return false
}

private fun FileInjectablesScope(
  file: KtFile,
  @Inject context: AnalysisContext
): InjectablesScope = context.injektContext.elementScopes.getOrPut(file) {
  ImportInjectablesScope(
    file = file,
    imports = file.getProviderImports() + ProviderImport(null, "${file.packageFqName.asString()}.*"),
    namePrefix = "FILE ${file.name}",
    parent = null
  )
}

private fun FileInitInjectablesScope(
  position: KtElement,
  @Inject context: AnalysisContext
): InjectablesScope {
  val file = position.containingKtFile

  val visibleInjectableDeclarations = file
    .declarations
    .filter { it.endOffset < position.startOffset }
    .filterIsInstance<KtNamedDeclaration>()
    .mapNotNull { it.descriptor() }
    .filter { it.isProvide() }

  return ImportInjectablesScope(
    file = file,
    imports = file.getProviderImports() + ProviderImport(null, "${file.packageFqName.asString()}.*"),
    namePrefix = "FILE INIT ${file.name} at ",
    injectablesPredicate = {
      val psiProperty = it.callable.findPsi().safeAs<KtProperty>() ?: return@ImportInjectablesScope true
      psiProperty.delegateExpressionOrInitializer == null || it.callable in visibleInjectableDeclarations
    },
    parent = null
  )
}

private fun ClassCompanionInjectablesScope(
  clazz: ClassDescriptor,
  parent: InjectablesScope,
  @Inject context: AnalysisContext
): InjectablesScope = clazz.companionObjectDescriptor
  ?.let { ClassInjectablesScope(it, parent) } ?: parent

private fun ClassImportsInjectablesScope(
  clazz: ClassDescriptor,
  parent: InjectablesScope,
  @Inject context: AnalysisContext
): InjectablesScope {
  val finalParent = ClassCompanionInjectablesScope(clazz, parent)
  return (clazz
    .findPsi()
    .safeAs<KtClassOrObject>()
    ?.getProviderImports()
    ?.takeIf { it.isNotEmpty() }
    ?.let { ImportInjectablesScope(null, it, "CLASS ${clazz.fqNameSafe}", finalParent) }
    ?: finalParent)
}

private fun ClassInjectablesScope(
  clazz: ClassDescriptor,
  parent: InjectablesScope,
  @Inject context: AnalysisContext
): InjectablesScope = context.injektContext.declarationScopes.getOrPut(clazz) {
  val finalParent = ClassImportsInjectablesScope(clazz, parent)
  val name = if (clazz.isCompanionObject)
    "COMPANION ${clazz.containingDeclaration.fqNameSafe}"
  else "CLASS ${clazz.fqNameSafe}"
  InjectablesScope(
    name = name,
    callContext = CallContext.DEFAULT,
    parent = finalParent,
    ownerDescriptor = clazz,
    file = null,
    initialInjectables = listOf(clazz.injectableReceiver(false)),
    imports = emptyList(),
    typeParameters = clazz.declaredTypeParameters.map { it.toClassifierRef() },
    nesting = finalParent.nesting + 1
  )
}

private fun ClassInitInjectablesScope(
  clazz: ClassDescriptor,
  parent: InjectablesScope,
  position: KtElement,
  @Inject context: AnalysisContext
): InjectablesScope {
  val visibleInjectableDeclarations = clazz.findPsi()!!
    .cast<KtClassOrObject>()
    .declarations
    .filter { it.endOffset < position.startOffset }
    .filterIsInstance<KtNamedDeclaration>()
    .mapNotNull { it.descriptor() }
    .filter { it.isProvide() }
  val finalParent = ClassImportsInjectablesScope(clazz, parent)

  val injectableDeclaration = visibleInjectableDeclarations.lastOrNull()

  val name = if (clazz.isCompanionObject)
    "COMPANION INIT ${clazz.containingDeclaration.fqNameSafe} at ${injectableDeclaration?.name}"
  else "CLASS INIT ${clazz.fqNameSafe} at ${injectableDeclaration?.name}"

  val thisInjectable = clazz.injectableReceiver(false)

  val classInitScope = InjectablesScope(
    name = name,
    callContext = CallContext.DEFAULT,
    parent = finalParent,
    ownerDescriptor = clazz,
    file = null,
    initialInjectables = listOf(thisInjectable),
    injectablesPredicate = {
      println()
      val psiProperty = it.callable.findPsi().safeAs<KtProperty>() ?: return@InjectablesScope true
      psiProperty.delegateExpressionOrInitializer == null || it.callable in visibleInjectableDeclarations
    },
    imports = emptyList(),
    typeParameters = clazz.declaredTypeParameters.map { it.toClassifierRef() },
    nesting = finalParent.nesting + 1
  )

  val primaryConstructor = clazz.unsubstitutedPrimaryConstructor

  return if (primaryConstructor == null) classInitScope
  else FunctionInjectablesScope(primaryConstructor, classInitScope)
}

private fun ConstructorPreInitInjectablesScope(
  constructor: ConstructorDescriptor,
  parent: InjectablesScope,
  @Inject context: AnalysisContext
): InjectablesScope {
  val finalParent = ClassImportsInjectablesScope(
    constructor.constructedClass,
    FunctionImportsInjectablesScope(constructor, parent)
  )
  val parameterScopes = FunctionParameterInjectablesScopes(finalParent, constructor, null)
  val typeParameters = constructor.constructedClass.declaredTypeParameters.map {
    it.toClassifierRef()
  }
  return InjectablesScope(
    name = "CONSTRUCTOR PRE INIT ${constructor.fqNameSafe}",
    parent = parameterScopes,
    callContext = CallContext.DEFAULT,
    ownerDescriptor = constructor,
    file = null,
    initialInjectables = emptyList(),
    imports = emptyList(),
    typeParameters = typeParameters,
    nesting = parameterScopes.nesting
  )
}

private fun FunctionImportsInjectablesScope(
  function: FunctionDescriptor,
  parent: InjectablesScope,
  @Inject context: AnalysisContext
): InjectablesScope = function
  .findPsi()
  .safeAs<KtFunction>()
  ?.getProviderImports()
  ?.takeIf { it.isNotEmpty() }
  ?.let {
    val baseName = if (function is ConstructorDescriptor) "CONSTRUCTOR" else "FUNCTION"
    ImportInjectablesScope(null, it, "$baseName ${function.fqNameSafe}", parent)
  }
  ?: parent

private fun ValueParameterDefaultValueInjectablesScope(
  valueParameter: ValueParameterDescriptor,
  parent: InjectablesScope,
  @Inject context: AnalysisContext
): InjectablesScope {
  val function = valueParameter.containingDeclaration.cast<FunctionDescriptor>()
  val finalParent = FunctionImportsInjectablesScope(function, parent)
  val parameterScopes = FunctionParameterInjectablesScopes(finalParent, function, valueParameter)
  return InjectablesScope(
    name = "DEFAULT VALUE ${valueParameter.fqNameSafe}",
    parent = parameterScopes,
    callContext = function.callContext()
      // suspend functions cannot be called from a default value context
      .takeIf { it != CallContext.SUSPEND } ?: CallContext.DEFAULT,
    ownerDescriptor = function,
    file = null,
    initialInjectables = emptyList(),
    imports = emptyList(),
    typeParameters = function.typeParameters.map { it.toClassifierRef() },
    nesting = finalParent.nesting + 1
  )
}

private fun FunctionInjectablesScope(
  function: FunctionDescriptor,
  parent: InjectablesScope,
  @Inject context: AnalysisContext
): InjectablesScope = context.injektContext.declarationScopes.getOrPut(function) {
  val finalParent = FunctionImportsInjectablesScope(function, parent)
  val parameterScopes = FunctionParameterInjectablesScopes(finalParent, function, null)
  val baseName = if (function is ConstructorDescriptor) "CONSTRUCTOR" else "FUNCTION"
  val typeParameters = (if (function is ConstructorDescriptor)
    function.constructedClass.declaredTypeParameters
  else function.typeParameters)
    .map { it.toClassifierRef() }
  InjectablesScope(
    name = "$baseName ${function.fqNameSafe}",
    parent = parameterScopes,
    callContext = function.callContext(),
    ownerDescriptor = function,
    file = null,
    initialInjectables = emptyList(),
    imports = emptyList(),
    typeParameters = typeParameters,
    nesting = parameterScopes.nesting
  )
}

private fun FunctionParameterInjectablesScopes(
  parent: InjectablesScope,
  function: FunctionDescriptor,
  until: ValueParameterDescriptor? = null,
  @Inject context: AnalysisContext
): InjectablesScope {
  val maxIndex = until?.injektIndex()
  return function.allParameters
    .asSequence()
    .filter {
      (maxIndex == null || it.injektIndex() < maxIndex) &&
          (it.isProvide() || it === function.extensionReceiverParameter)
    }
    .map { it.toCallableRef().makeProvide() }
    .fold(parent) { acc, nextParameter ->
      FunctionParameterInjectablesScope(
        parent = acc,
        function = function,
        parameter = nextParameter
      )
    }
}

private fun FunctionParameterInjectablesScope(
  parent: InjectablesScope,
  function: FunctionDescriptor,
  parameter: CallableRef,
  @Inject context: AnalysisContext
): InjectablesScope {
  parameter.callable as ParameterDescriptor
  return InjectablesScope(
    name = "FUNCTION PARAMETER ${parameter.callable.fqNameSafe.parent()}.${parameter.callable.injektName()}",
    callContext = CallContext.DEFAULT,
    parent = parent,
    ownerDescriptor = function,
    file = null,
    initialInjectables = listOf(parameter),
    imports = emptyList(),
    typeParameters = emptyList(),
    nesting = if (parent.name.startsWith("FUNCTION_PARAMETER")) parent.nesting
    else parent.nesting + 1
  )
}

private fun PropertyInjectablesScope(
  property: PropertyDescriptor,
  parent: InjectablesScope,
  @Inject context: AnalysisContext
): InjectablesScope = context.injektContext.declarationScopes.getOrPut(property) {
  val finalParent = property
    .findPsi()
    .safeAs<KtProperty>()
    ?.getProviderImports()
    ?.takeIf { it.isNotEmpty() }
    ?.let { ImportInjectablesScope(null, it, "PROPERTY ${property.fqNameSafe}", parent) }
    ?: parent

  InjectablesScope(
    name = "PROPERTY ${property.fqNameSafe}",
    callContext = property.callContext(),
    parent = finalParent,
    ownerDescriptor = property,
    file = null,
    initialInjectables = listOfNotNull(
      property.extensionReceiverParameter
        ?.toCallableRef()
        ?.makeProvide()
    ),
    imports = emptyList(),
    typeParameters = property.typeParameters.map { it.toClassifierRef() },
    nesting = finalParent.nesting + 1
  )
}

private fun PropertyInitInjectablesScope(
  property: PropertyDescriptor,
  parent: InjectablesScope,
  position: KtElement,
  @Inject context: AnalysisContext
): InjectablesScope {
  val containingDeclarationScope = if (property.containingDeclaration is ClassDescriptor) {
    ClassInitInjectablesScope(
      clazz = property.containingDeclaration.cast(),
      parent = parent,
      position = position
    )
  } else {
    FileInitInjectablesScope(position = position)
  }

  val finalParent = property
    .findPsi()
    .safeAs<KtProperty>()
    ?.getProviderImports()
    ?.takeIf { it.isNotEmpty() }
    ?.let {
      ImportInjectablesScope(
        file = null,
        imports = it,
        namePrefix = "PROPERTY ${property.fqNameSafe}",
        parent = containingDeclarationScope
      )
    }
    ?: containingDeclarationScope

  return InjectablesScope(
    name = "PROPERTY INIT ${property.fqNameSafe}",
    callContext = CallContext.DEFAULT,
    parent = finalParent,
    ownerDescriptor = property,
    file = null,
    initialInjectables = emptyList(),
    imports = emptyList(),
    typeParameters = property.typeParameters.map { it.toClassifierRef() },
    nesting = finalParent.nesting + 1
  )
}

private fun LocalVariableInjectablesScope(
  variable: LocalVariableDescriptor,
  parent: InjectablesScope,
  @Inject context: AnalysisContext
): InjectablesScope = context.injektContext.declarationScopes.getOrPut(variable) {
  val finalParent = variable
    .findPsi()
    .safeAs<KtProperty>()
    ?.getProviderImports()
    ?.takeIf { it.isNotEmpty() }
    ?.let { ImportInjectablesScope(null, it, "LOCAL VARIABLE ${variable.fqNameSafe}", parent) }
    ?: parent

  InjectablesScope(
    name = "LOCAL VARIABLE ${variable.fqNameSafe}",
    callContext = parent.callContext,
    parent = finalParent,
    ownerDescriptor = variable,
    file = null,
    initialInjectables = emptyList(),
    imports = emptyList(),
    typeParameters = emptyList(),
    nesting = finalParent.nesting
  )
}

private fun ExpressionInjectablesScope(
  expression: KtAnnotatedExpression,
  parent: InjectablesScope,
  @Inject context: AnalysisContext
): InjectablesScope = context.injektContext.elementScopes.getOrPut(expression) {
  val finalParent = expression
    .getProviderImports()
    .takeIf { it.isNotEmpty() }
    ?.let { ImportInjectablesScope(null, it, "EXPRESSION ${expression.startOffset}", parent) }
    ?: parent

  InjectablesScope(
    name = "EXPRESSION ${expression.startOffset}",
    callContext = finalParent.callContext,
    parent = finalParent,
    ownerDescriptor = null,
    file = null,
    initialInjectables = emptyList(),
    imports = emptyList(),
    typeParameters = emptyList(),
    nesting = finalParent.nesting
  )
}

private fun BlockExpressionInjectablesScope(
  block: KtBlockExpression,
  position: KtElement,
  parent: InjectablesScope,
  @Inject context: AnalysisContext
): InjectablesScope {
  val visibleInjectableDeclarations = block.statements
    .filter { it.endOffset < position.startOffset }
    .filterIsInstance<KtNamedDeclaration>()
    .mapNotNull { it.descriptor() }
    .filter { it.isProvide() }
  if (visibleInjectableDeclarations.isEmpty()) return parent
  val injectableDeclaration = visibleInjectableDeclarations.last()
  val key = block to injectableDeclaration
  return context.injektContext.blockScopes.getOrPut(key) {
    val finalParent = if (visibleInjectableDeclarations.size > 1)
      BlockExpressionInjectablesScope(block, injectableDeclaration.findPsi().cast(), parent)
    else parent

    InjectablesScope(
      name = "BLOCK AT ${injectableDeclaration.name}",
      callContext = finalParent.callContext,
      parent = finalParent,
      ownerDescriptor = null,
      file = null,
      initialInjectables = when (injectableDeclaration) {
        is ClassDescriptor -> injectableDeclaration.injectableConstructors()
        is CallableDescriptor -> listOf(injectableDeclaration.toCallableRef())
        else -> throw AssertionError("Unexpected injectable $injectableDeclaration")
      },
      imports = emptyList(),
      typeParameters = emptyList(),
      nesting = if (visibleInjectableDeclarations.size > 1) finalParent.nesting
      else finalParent.nesting + 1
    )
  }
}

fun TypeInjectablesScope(
  type: TypeRef,
  parent: InjectablesScope,
  @Inject context: AnalysisContext
): InjectablesScope {
  val finalType = type.withNullability(false)
  return parent.typeScopes.getOrPut(finalType) {
    val injectablesWithLookups = finalType.collectTypeScopeInjectables()
    InjectablesScope(
      name = "TYPE ${finalType.renderToString()}",
      parent = parent,
      callContext = CallContext.DEFAULT,
      ownerDescriptor = finalType.classifier.descriptor,
      file = null,
      initialInjectables = injectablesWithLookups.injectables,
      imports = injectablesWithLookups.lookedUpPackages
        .map { ResolvedProviderImport(null, "$it.*", it) },
      typeParameters = emptyList(),
      nesting = parent.nesting
    )
  }
}

private fun ImportInjectablesScope(
  file: KtFile?,
  imports: List<ProviderImport>,
  namePrefix: String,
  parent: InjectablesScope?,
  injectablesPredicate: (CallableRef) -> Boolean = { true },
  @Inject context: AnalysisContext
): InjectablesScope {
  val resolvedImports = imports.collectImportedInjectables()
  return InjectablesScope(
    name = "$namePrefix INTERNAL IMPORTS",
    callContext = CallContext.DEFAULT,
    parent = InjectablesScope(
      name = "$namePrefix EXTERNAL IMPORTS",
      callContext = CallContext.DEFAULT,
      parent = parent,
      ownerDescriptor = null,
      file = file,
      initialInjectables = resolvedImports
        .filter { it.callable.isExternalDeclaration() },
      injectablesPredicate = injectablesPredicate,
      imports = emptyList(),
      typeParameters = emptyList(),
      nesting = parent?.nesting?.inc() ?: 0
    ),
    ownerDescriptor = null,
    file = file,
    initialInjectables = resolvedImports
      .filterNot { it.callable.isExternalDeclaration() },
    injectablesPredicate = injectablesPredicate,
    imports = imports.mapNotNull { it.resolve() },
    typeParameters = emptyList(),
    nesting = parent?.nesting?.inc()?.inc() ?: 1
  )
}
