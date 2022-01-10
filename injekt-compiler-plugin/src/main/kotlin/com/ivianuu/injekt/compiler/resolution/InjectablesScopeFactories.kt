/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.injekt.compiler.resolution

import com.ivianuu.injekt.compiler.*
import com.ivianuu.shaded_injekt.*
import org.jetbrains.kotlin.backend.common.descriptors.*
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.*
import org.jetbrains.kotlin.js.resolve.diagnostics.*
import org.jetbrains.kotlin.lexer.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.jetbrains.kotlin.utils.addToStdlib.*

fun ElementInjectablesScope(
  element: KtElement,
  position: KtElement = element,
  @Inject ctx: Context
): InjectablesScope {
  val scopeOwner = element.parentsWithSelf
    .first { (it as KtElement).isScopeOwner(position) }
    .cast<KtElement>()

  if (scopeOwner !is KtBlockExpression)
    trace()!!.bindingContext.get(InjektWritableSlices.ELEMENT_SCOPE, scopeOwner)
      ?.let { return it }

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
    trace()!!.record(InjektWritableSlices.ELEMENT_SCOPE, scopeOwner, scope)

  return scope
}

private fun KtElement.isScopeOwner(position: KtElement, @Inject ctx: Context): Boolean {
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

  if (this is KtObjectDeclaration &&
    position.getParentOfType<KtSuperTypeList>(false)
      ?.getParentOfType<KtClassOrObject>(false) != this)
    return true

  if (this is KtAnnotatedExpression && hasAnnotation(injektFqNames().providers))
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

private fun FileInjectablesScope(file: KtFile, @Inject ctx: Context): InjectablesScope =
  trace()!!.getOrPut(InjektWritableSlices.ELEMENT_SCOPE, file) {
    ImportInjectablesScopes(
      file = file,
      imports = file.getProviderImports() + ProviderImport(null, "${file.packageFqName.asString()}.*"),
      namePrefix = "FILE ${file.name}",
      parent = null
    )
  }

private fun FileInitInjectablesScope(position: KtElement, @Inject ctx: Context): InjectablesScope {
  val file = position.containingKtFile

  val visibleInjectableDeclarations = file
    .declarations
    .transform { declaration ->
      if (declaration.endOffset < position.startOffset &&
          declaration is KtNamedDeclaration) {
        declaration.descriptor<DeclarationDescriptor>()
          ?.takeIf { it.isProvide() }
          ?.let { add(it) }
      }
    }

  val injectableDeclaration = visibleInjectableDeclarations.lastOrNull()

  return ImportInjectablesScopes(
    file = file,
    imports = file.getProviderImports() + ProviderImport(null, "${file.packageFqName.asString()}.*"),
    namePrefix = "FILE INIT ${file.name} at ${injectableDeclaration?.name}",
    injectablesPredicate = {
      val psiProperty = it.callable.findPsi().safeAs<KtProperty>() ?: return@ImportInjectablesScopes true
      psiProperty.containingFile != file ||
          psiProperty.delegateExpressionOrInitializer == null ||
          it.callable in visibleInjectableDeclarations
    },
    parent = null
  )
}

private fun ClassCompanionInjectablesScope(
  clazz: ClassDescriptor,
  parent: InjectablesScope,
  @Inject ctx: Context
): InjectablesScope = clazz.companionObjectDescriptor
  ?.let { ClassInjectablesScope(it, parent) } ?: parent

private fun ClassImportsInjectablesScope(
  clazz: ClassDescriptor,
  parent: InjectablesScope,
  @Inject ctx: Context
): InjectablesScope {
  val finalParent = ClassCompanionInjectablesScope(clazz, parent)
  return (clazz
    .findPsi()
    .safeAs<KtClassOrObject>()
    ?.getProviderImports()
    ?.takeIf { it.isNotEmpty() }
    ?.let { ImportInjectablesScopes(null, it, "CLASS ${clazz.fqNameSafe}", finalParent) }
    ?: finalParent)
}

private fun ClassInjectablesScope(
  clazz: ClassDescriptor,
  parent: InjectablesScope,
  @Inject ctx: Context
): InjectablesScope = trace()!!.getOrPut(
  InjektWritableSlices.DECLARATION_SCOPE,
  DescriptorWithParentScope(clazz, parent.name)
) {
  val finalParent = ClassImportsInjectablesScope(clazz, parent)
  val name = if (clazz.isCompanionObject)
    "COMPANION ${clazz.containingDeclaration.fqNameSafe}"
  else "CLASS ${clazz.fqNameSafe}"
  InjectablesScope(
    name = name,
    parent = finalParent,
    ownerDescriptor = clazz,
    initialInjectables = listOf(clazz.injectableReceiver(false)),
    typeParameters = clazz.declaredTypeParameters.map { it.toClassifierRef() }
  )
}

private fun ClassInitInjectablesScope(
  clazz: ClassDescriptor,
  parent: InjectablesScope,
  position: KtElement,
  @Inject ctx: Context
): InjectablesScope {
  val psiClass = clazz.findPsi()!!
  val visibleInjectableDeclarations = psiClass
    .cast<KtClassOrObject>()
    .declarations
    .transform { declaration ->
      if (declaration.endOffset < position.startOffset &&
        declaration is KtNamedDeclaration) {
        declaration.descriptor<DeclarationDescriptor>()
          ?.takeIf { it.isProvide() }
          ?.let { add(it) }
      }
    }
  val finalParent = ClassImportsInjectablesScope(clazz, parent)

  val injectableDeclaration = visibleInjectableDeclarations.lastOrNull()

  val name = if (clazz.isCompanionObject)
    "COMPANION INIT ${clazz.containingDeclaration.fqNameSafe} at ${injectableDeclaration?.name}"
  else "CLASS INIT ${clazz.fqNameSafe} at ${injectableDeclaration?.name}"

  val thisInjectable = clazz.injectableReceiver(false)

  val classInitScope = InjectablesScope(
    name = name,
    parent = finalParent,
    ownerDescriptor = clazz,
    initialInjectables = listOf(thisInjectable),
    injectablesPredicate = {
      val psiProperty = it.callable.findPsi().safeAs<KtProperty>() ?: return@InjectablesScope true
      psiProperty.getParentOfType<KtClass>(false) != psiClass ||
          psiProperty.delegateExpressionOrInitializer == null ||
          it.callable in visibleInjectableDeclarations
    },
    typeParameters = clazz.declaredTypeParameters.map { it.toClassifierRef() }
  )

  val primaryConstructor = clazz.unsubstitutedPrimaryConstructor

  return if (primaryConstructor == null) classInitScope
  else FunctionInjectablesScope(primaryConstructor, classInitScope)
}

private fun ConstructorPreInitInjectablesScope(
  constructor: ConstructorDescriptor,
  parent: InjectablesScope,
  @Inject ctx: Context
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
    ownerDescriptor = constructor,
    typeParameters = typeParameters,
    nesting = parameterScopes.nesting
  )
}

private fun FunctionImportsInjectablesScope(
  function: FunctionDescriptor,
  parent: InjectablesScope,
  @Inject ctx: Context
): InjectablesScope = function
  .findPsi()
  .safeAs<KtFunction>()
  ?.getProviderImports()
  ?.takeIf { it.isNotEmpty() }
  ?.let {
    val baseName = if (function is ConstructorDescriptor) "CONSTRUCTOR" else "FUNCTION"
    ImportInjectablesScopes(null, it, "$baseName ${function.fqNameSafe}", parent)
  }
  ?: parent

private fun ValueParameterDefaultValueInjectablesScope(
  valueParameter: ValueParameterDescriptor,
  parent: InjectablesScope,
  @Inject ctx: Context
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
    typeParameters = function.typeParameters.map { it.toClassifierRef() }
  )
}

private fun FunctionInjectablesScope(
  function: FunctionDescriptor,
  parent: InjectablesScope,
  @Inject ctx: Context
): InjectablesScope = trace()!!.getOrPut(
  InjektWritableSlices.DECLARATION_SCOPE,
  DescriptorWithParentScope(function, parent.name)
) {
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
    typeParameters = typeParameters,
    nesting = parameterScopes.nesting
  )
}

private fun FunctionParameterInjectablesScopes(
  parent: InjectablesScope,
  function: FunctionDescriptor,
  until: ValueParameterDescriptor? = null,
  @Inject ctx: Context
): InjectablesScope {
  val maxIndex = until?.injektIndex()

  return function.allParameters
    .transform {
      if ((maxIndex == null || it.injektIndex() < maxIndex) &&
        (it === function.extensionReceiverParameter || it.isProvide()))
          add(it.toCallableRef())
    }
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
  @Inject ctx: Context
): InjectablesScope {
  parameter.callable as ParameterDescriptor
  return InjectablesScope(
    name = "FUNCTION PARAMETER ${parameter.callable.fqNameSafe.parent()}.${parameter.callable.injektName()}",
    parent = parent,
    ownerDescriptor = function,
    initialInjectables = listOf(parameter),
    nesting = if (parent.name.startsWith("FUNCTION PARAMETER")) parent.nesting
    else parent.nesting + 1
  )
}

private fun PropertyInjectablesScope(
  property: PropertyDescriptor,
  parent: InjectablesScope,
  @Inject ctx: Context
): InjectablesScope = trace()!!.getOrPut(
  InjektWritableSlices.DECLARATION_SCOPE,
  DescriptorWithParentScope(property, parent.name)
) {
  val finalParent = property
    .findPsi()
    .safeAs<KtProperty>()
    ?.getProviderImports()
    ?.takeIf { it.isNotEmpty() }
    ?.let { ImportInjectablesScopes(null, it, "PROPERTY ${property.fqNameSafe}", parent) }
    ?: parent

  InjectablesScope(
    name = "PROPERTY ${property.fqNameSafe}",
    callContext = property.callContext(),
    parent = finalParent,
    ownerDescriptor = property,
    initialInjectables = listOfNotNull(property.extensionReceiverParameter?.toCallableRef()),
    typeParameters = property.typeParameters.map { it.toClassifierRef() }
  )
}

private fun PropertyInitInjectablesScope(
  property: PropertyDescriptor,
  parent: InjectablesScope,
  position: KtElement,
  @Inject ctx: Context
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
      ImportInjectablesScopes(
        file = null,
        imports = it,
        namePrefix = "PROPERTY ${property.fqNameSafe}",
        parent = containingDeclarationScope
      )
    }
    ?: containingDeclarationScope

  return InjectablesScope(
    name = "PROPERTY INIT ${property.fqNameSafe}",
    parent = finalParent,
    ownerDescriptor = property,
    typeParameters = property.typeParameters.map { it.toClassifierRef() }
  )
}

private fun LocalVariableInjectablesScope(
  variable: LocalVariableDescriptor,
  parent: InjectablesScope,
  @Inject ctx: Context
): InjectablesScope = trace()!!.getOrPut(
  InjektWritableSlices.DECLARATION_SCOPE,
  DescriptorWithParentScope(variable, parent.name)
) {
  val finalParent = variable
    .findPsi()
    .safeAs<KtProperty>()
    ?.getProviderImports()
    ?.takeIf { it.isNotEmpty() }
    ?.let { ImportInjectablesScopes(null, it, "LOCAL VARIABLE ${variable.fqNameSafe}", parent) }
    ?: parent

  InjectablesScope(
    name = "LOCAL VARIABLE ${variable.fqNameSafe}",
    callContext = parent.callContext,
    parent = finalParent,
    ownerDescriptor = variable,
    nesting = finalParent.nesting
  )
}

private fun ExpressionInjectablesScope(
  expression: KtAnnotatedExpression,
  parent: InjectablesScope,
  @Inject ctx: Context
): InjectablesScope = trace()!!.getOrPut(InjektWritableSlices.ELEMENT_SCOPE, expression) {
  val finalParent = expression
    .getProviderImports()
    .takeIf { it.isNotEmpty() }
    ?.let { ImportInjectablesScopes(null, it, "EXPRESSION ${expression.startOffset}", parent) }
    ?: parent

  InjectablesScope(
    name = "EXPRESSION ${expression.startOffset}",
    callContext = finalParent.callContext,
    parent = finalParent,
    nesting = finalParent.nesting
  )
}

private fun BlockExpressionInjectablesScope(
  block: KtBlockExpression,
  position: KtElement,
  parent: InjectablesScope,
  @Inject ctx: Context
): InjectablesScope {
  val visibleInjectableDeclarations = block.statements
    .transform { declaration ->
      if (declaration.endOffset < position.startOffset &&
        declaration is KtNamedDeclaration) {
        declaration.descriptor<DeclarationDescriptor>()
          ?.takeIf { it.isProvide() }
          ?.let { add(it) }
      }
    }
  if (visibleInjectableDeclarations.isEmpty()) return parent
  val injectableDeclaration = visibleInjectableDeclarations.last()
  val key = block to injectableDeclaration
  return trace()!!.getOrPut(InjektWritableSlices.BLOCK_SCOPE, key) {
    val finalParent = if (visibleInjectableDeclarations.size > 1)
      BlockExpressionInjectablesScope(block, injectableDeclaration.findPsi().cast(), parent)
    else parent

    InjectablesScope(
      name = "BLOCK AT ${injectableDeclaration.name}",
      callContext = finalParent.callContext,
      parent = finalParent,
      initialInjectables = when (injectableDeclaration) {
        is ClassDescriptor -> injectableDeclaration.injectableConstructors()
        is CallableDescriptor -> listOf(injectableDeclaration.toCallableRef())
        else -> throw AssertionError("Unexpected injectable $injectableDeclaration")
      },
      nesting = if (visibleInjectableDeclarations.size > 1) finalParent.nesting
      else finalParent.nesting + 1
    )
  }
}

fun ImportSuggestionInjectablesScope(
  parent: InjectablesScope,
  candidate: CallableRef,
  @Inject ctx: Context
) = InjectablesScope(
  name = "IMPORT SUGGESTION ${candidate.callable.fqNameSafe}",
  parent = parent,
  callContext = parent.callContext,
  isDeclarationContainer = false,
  initialInjectables = listOf(candidate)
)

fun TypeInjectablesScopeOrNull(
  type: TypeRef,
  parent: InjectablesScope,
  @Inject ctx: Context
): InjectablesScope {
  val finalParent = parent.scopeToUse
  return finalParent.typeScopes.getOrPut(type.key) {
    val injectablesWithLookups = type.collectTypeScopeInjectables()

    val newInjectables = injectablesWithLookups.injectables
      .filterNotExistingIn(finalParent)

    val imports = injectablesWithLookups.lookedUpPackages
      .map { ResolvedProviderImport(null, "$it.*", it) }

    if (newInjectables.isEmpty()) {
      return@getOrPut InjectablesScope(
        name = "EMPTY TYPE ${type.renderToString()}",
        parent = finalParent,
        imports = imports,
        isEmpty = true,
        isDeclarationContainer = false
      )
    }

    val externalInjectables = mutableListOf<CallableRef>()
    val typeInjectables = mutableListOf<CallableRef>()
    val internalInjectables = mutableListOf<CallableRef>()

    val thisModuleName = module().moduleName()
    val typeModuleName = type.classifier.descriptor!!.moduleName()
    for (callable in newInjectables) {
      when (callable.callable.moduleName()) {
        thisModuleName -> internalInjectables += callable
        typeModuleName -> typeInjectables += callable
        else -> externalInjectables += callable
      }
    }

    var result = finalParent

    if (externalInjectables.isNotEmpty()) {
      result = InjectablesScope(
        name = "EXTERNAL TYPE ${type.renderToString()}",
        parent = result,
        initialInjectables = externalInjectables,
        callContext = result.callContext,
        typeScopeType = type,
        isDeclarationContainer = false,
        imports = imports
      )
    }
    if (typeInjectables.isNotEmpty()) {
      result = InjectablesScope(
        name = "TYPE TYPE ${type.renderToString()}",
        parent = result,
        initialInjectables = typeInjectables,
        callContext = result.callContext,
        typeScopeType = type,
        isDeclarationContainer = false,
        imports = imports
      )
    }
    if (internalInjectables.isNotEmpty()) {
      result = InjectablesScope(
        name = "INTERNAL TYPE ${type.renderToString()}",
        parent = result,
        initialInjectables = internalInjectables,
        callContext = result.callContext,
        typeScopeType = type,
        isDeclarationContainer = false,
        imports = imports
      )
    }

    result
  }
}

private fun ImportInjectablesScopes(
  file: KtFile?,
  imports: List<ProviderImport>,
  namePrefix: String,
  parent: InjectablesScope?,
  injectablesPredicate: (CallableRef) -> Boolean = { true },
  @Inject ctx: Context
): InjectablesScope {
  val externalStarInjectables = mutableListOf<CallableRef>()
  val externalByNameInjectables = mutableListOf<CallableRef>()
  val internalStarInjectables = mutableListOf<CallableRef>()
  val internalByNameInjectables = mutableListOf<CallableRef>()

  imports.collectImportedInjectables { callable ->
    if (callable.callable.isExternalDeclaration()) {
      if (callable.import!!.importPath!!.endsWith("*")) {
        externalStarInjectables += callable
      } else {
        externalByNameInjectables += callable
      }
    } else {
      if (callable.import!!.importPath!!.endsWith("*")) {
        internalStarInjectables += callable
      } else {
        internalByNameInjectables += callable
      }
    }
  }

  val resolvedImports = imports.mapNotNull { it.resolve() }
  if (externalStarInjectables.isEmpty() &&
      internalStarInjectables.isEmpty() &&
      externalByNameInjectables.isEmpty() &&
      internalByNameInjectables.isEmpty()) {
    return InjectablesScope(
      name = "$namePrefix EMPTY IMPORTS",
      parent = parent,
      file = file,
      isDeclarationContainer = false
    )
  }

  var current = parent
  if (externalStarInjectables.isNotEmpty()) {
    current = InjectablesScope(
      name = "$namePrefix EXTERNAL STAR IMPORTS",
      parent = current,
      file = file,
      imports = resolvedImports,
      initialInjectables = externalStarInjectables,
      isDeclarationContainer = false,
      injectablesPredicate = injectablesPredicate
    )
  }
  if (internalStarInjectables.isNotEmpty()) {
    current = InjectablesScope(
      name = "$namePrefix INTERNAL STAR IMPORTS",
      parent = current,
      file = file,
      imports = resolvedImports,
      initialInjectables = internalStarInjectables,
      isDeclarationContainer = false,
      injectablesPredicate = injectablesPredicate
    )
  }
  if (externalByNameInjectables.isNotEmpty()) {
    current = InjectablesScope(
      name = "$namePrefix EXTERNAL BY NAME IMPORTS",
      parent = current,
      initialInjectables = externalByNameInjectables,
      file = file,
      imports = resolvedImports,
      isDeclarationContainer = false,
      injectablesPredicate = injectablesPredicate
    )
  }
  if (internalByNameInjectables.isNotEmpty()) {
    current = InjectablesScope(
      name = "$namePrefix INTERNAL BY NAME IMPORTS",
      parent = current,
      file = file,
      imports = resolvedImports,
      initialInjectables = internalByNameInjectables,
      isDeclarationContainer = false,
      injectablesPredicate = injectablesPredicate
    )
  }

  return current!!
}

data class DescriptorWithParentScope(
  val declaration: DeclarationDescriptor,
  val parentName: String?
)
