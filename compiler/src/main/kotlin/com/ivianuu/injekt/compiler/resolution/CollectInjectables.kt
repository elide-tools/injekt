/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(UnsafeCastFunction::class)

package com.ivianuu.injekt.compiler.resolution

import com.ivianuu.injekt.compiler.Context
import com.ivianuu.injekt.compiler.DISPATCH_RECEIVER_INDEX
import com.ivianuu.injekt.compiler.InjektFqNames
import com.ivianuu.injekt.compiler.asNameId
import com.ivianuu.injekt.compiler.cached
import com.ivianuu.injekt.compiler.classifierInfo
import com.ivianuu.injekt.compiler.hasAnnotation
import com.ivianuu.injekt.compiler.memberScopeForFqName
import com.ivianuu.injekt.compiler.moduleName
import com.ivianuu.injekt.compiler.packageFragmentsForFqName
import com.ivianuu.injekt.compiler.transform
import com.ivianuu.injekt.compiler.uniqueKey
import org.jetbrains.kotlin.builtins.BuiltInsPackageFragment
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.descriptors.annotations.Annotated
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.containingPackage
import org.jetbrains.kotlin.descriptors.impl.ReceiverParameterDescriptorImpl
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.overriddenTreeAsSequence
import org.jetbrains.kotlin.resolve.descriptorUtil.parents
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.scopes.ResolutionScope
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitClassReceiver
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.utils.addToStdlib.UnsafeCastFunction
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

fun TypeRef.collectInjectables(
  classBodyView: Boolean,
  ctx: Context
): List<CallableRef> = ctx.cached("type_injectables", this to classBodyView) {
  // special case to support @Provide () -> Foo
  if (isProvideFunctionType) {
    val callable = classifier
      .descriptor!!
      .defaultType
      .memberScope
      .getContributedFunctions("invoke".asNameId(), NoLookupLocation.FROM_BACKEND)
      .first()
      .toCallableRef(ctx)
      .let { callable ->
        callable.copy(
          type = arguments.last(),
          parameterTypes = callable.parameterTypes.toMutableMap().apply {
            this[DISPATCH_RECEIVER_INDEX] = this@collectInjectables
          }
        ).substitute(
          classifier.typeParameters
            .zip(
              arguments
                .map { it.copy(isInject = true) }
            )
            .toMap()
        )
      }

    return@cached listOf(callable)
  }

  // do not run any code for types which do not declare any injectables
  if (!classifier.declaresInjectables && !classBodyView)
    // at least include the companion object if it declares injectables
    return@cached listOfNotNull(
      classifier.descriptor
        ?.safeAs<ClassDescriptor>()
        ?.companionObjectDescriptor
        ?.toClassifierRef(ctx)
        ?.takeIf { it.declaresInjectables }
        ?.descriptor
        ?.cast<ClassDescriptor>()
        ?.injectableReceiver(false, ctx)
    )

  buildList {
    classifier
      .descriptor
      ?.defaultType
      ?.memberScope
      ?.collectInjectables(classBodyView = classBodyView, ctx = ctx) { callable ->
        val substitutionMap = if (callable.callable.safeAs<CallableMemberDescriptor>()?.kind ==
          CallableMemberDescriptor.Kind.FAKE_OVERRIDE) {
          val originalClassifier = callable.callable.cast<CallableMemberDescriptor>()
            .overriddenTreeAsSequence(false)
            .last()
            .containingDeclaration
            .cast<ClassDescriptor>()
            .toClassifierRef(ctx)
          classifier.typeParameters.zip(arguments).toMap() + originalClassifier.typeParameters
            .zip(subtypeView(originalClassifier)!!.arguments)
        } else classifier.typeParameters.zip(arguments).toMap()
        val substituted = callable.substitute(substitutionMap)

        add(
          substituted.copy(
            parameterTypes = if (substituted.parameterTypes[DISPATCH_RECEIVER_INDEX] != this@collectInjectables) {
              substituted.parameterTypes.toMutableMap()
                .also { it[DISPATCH_RECEIVER_INDEX] = this@collectInjectables }
            } else substituted.parameterTypes
          )
        )
      }
  }
}

fun ResolutionScope.collectInjectables(
  classBodyView: Boolean,
  onEach: (DeclarationDescriptor) -> Unit = {},
  name: Name? = null,
  ctx: Context,
  includeNonProvideObjectsWithInjectables: Boolean = false,
  consumer: (CallableRef) -> Unit
) {
  for (declaration in getContributedDescriptors()) {
    onEach(declaration)
    if (name != null && declaration.name != name) continue

    when (declaration) {
      is ClassDescriptor -> {
        if (declaration.kind == ClassKind.OBJECT &&
          (!classBodyView || !declaration.isCompanionObject))
            declaration
              .takeIf {
                it.isProvide(ctx) ||
                    (includeNonProvideObjectsWithInjectables &&
                        it.toClassifierRef(ctx).declaresInjectables)
              }
              ?.injectableReceiver(!classBodyView, ctx)
              ?.let(consumer)
        else {
          declaration.injectableConstructors(ctx).forEach(consumer)
          if (!classBodyView && !includeNonProvideObjectsWithInjectables)
            declaration.companionObjectDescriptor
              ?.takeIf { it.classifierInfo(ctx).declaresInjectables }
              ?.injectableReceiver(false, ctx)
              ?.let(consumer)
        }
      }
      is CallableMemberDescriptor -> {
        if (declaration.isProvide(ctx))
          consumer(declaration.toCallableRef(ctx))
      }
      is VariableDescriptor -> {
        if (declaration.isProvide(ctx))
          consumer(declaration.toCallableRef(ctx))
      }
    }
  }
}

fun Annotated.isProvide(ctx: Context): Boolean {
  @Suppress("IMPLICIT_CAST_TO_ANY")
  val key = if (this is KotlinType) System.identityHashCode(this) else this
  return ctx.cached("is_provide", key) {
    var isProvide = hasAnnotation(InjektFqNames.Provide) ||
        hasAnnotation(InjektFqNames.Inject)

    if (!isProvide && this is ParameterDescriptor)
      isProvide = type.isProvide(ctx) ||
          containingDeclaration.safeAs<FunctionDescriptor>()?.isProvide(ctx) == true

    if (!isProvide && this is ClassConstructorDescriptor && isPrimary)
      isProvide = constructedClass.isProvide(ctx)

    isProvide
  }
}

fun Annotated.isInject(ctx: Context): Boolean {
  @Suppress("IMPLICIT_CAST_TO_ANY")
  val key = if (this is KotlinType) System.identityHashCode(this) else this
  return ctx.cached("is_inject", key) {
    var isInject = hasAnnotation(InjektFqNames.Inject)

    if (!isInject && this is ParameterDescriptor)
      isInject = type.isInject(ctx) ||
          containingDeclaration.safeAs<FunctionDescriptor>()?.isProvide(ctx) == true

    if (!isInject && this is ClassConstructorDescriptor && isPrimary)
      isInject = constructedClass.isProvide(ctx)

    isInject
  }
}

fun ClassDescriptor.injectableConstructors(ctx: Context): List<CallableRef> =
  ctx.cached("injectable_constructors", this) {
    constructors
      .transform { constructor ->
        if (constructor.hasAnnotation(InjektFqNames.Provide) ||
          (constructor.isPrimary && hasAnnotation(InjektFqNames.Provide)))
            add(constructor.toCallableRef(ctx))
      }
  }

fun ClassDescriptor.injectableReceiver(tagged: Boolean, ctx: Context): CallableRef {
  val callable = ReceiverParameterDescriptorImpl(
    this,
    ImplicitClassReceiver(this),
    Annotations.EMPTY
  ).toCallableRef(ctx)
  return if (!tagged || callable.type.classifier.tags.isEmpty()) callable
  else {
    val taggedType = callable.type.classifier.tags.wrap(callable.type)
    callable.copy(type = taggedType, originalType = taggedType)
  }
}

fun CallableRef.collectInjectables(
  scope: InjectablesScope,
  addInjectable: (CallableRef) -> Unit,
  addSpreadingInjectable: (CallableRef) -> Unit,
  chainLength: Int = 0,
  seen: MutableSet<InjectablesScope.InjectableKey> = mutableSetOf(),
  ctx: Context
) {
  if (!seen.add(InjectablesScope.InjectableKey(this, ctx))) return

  if (!scope.canSee(this, ctx) || !scope.allScopes.all { it.injectablesPredicate(this) }) return

  if (typeParameters.any { it.isSpread && typeArguments[it] == it.defaultType }) {
    addSpreadingInjectable(this)
    return
  }

  val nextCallable = if (type.isProvideFunctionType) {
    addInjectable(this)
    copy(type = type.copy(frameworkKey = callable.uniqueKey(ctx)))
  } else this
  addInjectable(nextCallable)

  nextCallable
    .type
    .collectInjectables(
      scope.allScopes.any {
        it.ownerDescriptor == nextCallable.type.classifier.descriptor
      },
      ctx
    )
    .forEach { innerCallable ->
      val nextChainLength = chainLength + 1
      innerCallable
        .copy(chainLength = nextChainLength)
        .collectInjectables(
          scope = scope,
          addInjectable = addInjectable,
          addSpreadingInjectable = addSpreadingInjectable,
          chainLength = nextChainLength,
          seen = seen,
          ctx = ctx
        )
    }
}

fun collectGlobalInjectables(ctx: Context): List<CallableRef> = buildList {
  packagesWithInjectables(ctx)
    .forEach {
      collectPackageInjectables(it, ctx).forEach { add(it) }
    }
}

fun collectPackageInjectables(
  packageFqName: FqName,
  ctx: Context
): List<CallableRef> =
  ctx.cached("package_injectables", packageFqName) {
    if (packageFqName !in packagesWithInjectables(ctx)) return@cached emptyList()

    val packageFragments = packageFragmentsForFqName(packageFqName, ctx)
      .filterNot { it is BuiltInsPackageFragment }

    val injectables = mutableListOf<CallableRef>()

    fun collectInjectables(scope: MemberScope) {
      scope.collectInjectables(
        onEach = { declaration ->
          // only collect in nested scopes if the declaration does NOT declare any injectables
          // otherwise they will be included later in the injectables scope itself
          if (declaration is ClassDescriptor &&
            (declaration.kind != ClassKind.OBJECT ||
                !declaration.toClassifierRef(ctx).declaresInjectables))
            collectInjectables(declaration.unsubstitutedInnerClassesScope)
        },
        classBodyView = false,
        includeNonProvideObjectsWithInjectables = true,
        ctx = ctx
      ) {
        injectables += it
      }
    }
    packageFragments.forEach { collectInjectables(it.getMemberScope()) }

    injectables
  }

private fun InjectablesScope.canSee(callable: CallableRef, ctx: Context): Boolean =
  callable.callable.visibility == DescriptorVisibilities.PUBLIC ||
      callable.callable.visibility == DescriptorVisibilities.LOCAL ||
      (callable.callable.visibility == DescriptorVisibilities.INTERNAL &&
          callable.callable.moduleName(ctx) == ctx.module.moduleName(ctx)) ||
      (callable.callable is ClassConstructorDescriptor &&
          callable.type.unwrapTags().classifier.isObject) ||
      callable.callable.parents.any { callableParent ->
        allScopes.any { it.ownerDescriptor == callableParent }
      } || (callable.callable.visibility == DescriptorVisibilities.PRIVATE &&
      callable.callable.containingDeclaration is PackageFragmentDescriptor &&
      run {
        val scopeFile = allScopes.firstNotNullOfOrNull { it.file }
        scopeFile == callable.callable.findPsi()?.containingFile
      })

fun packagesWithInjectables(ctx: Context): Set<FqName> =
  ctx.cached("packages_with_injectables", Unit) {
    memberScopeForFqName(InjektFqNames.InjectablesPackage, NoLookupLocation.FROM_BACKEND, ctx)
      ?.getContributedFunctions(InjektFqNames.InjectablesLookup.shortName(), NoLookupLocation.FROM_BACKEND)
      ?.mapTo(mutableSetOf()) {
        it.valueParameters.first().type.constructor.declarationDescriptor!!.containingPackage()!!
      } ?: emptySet()
  }