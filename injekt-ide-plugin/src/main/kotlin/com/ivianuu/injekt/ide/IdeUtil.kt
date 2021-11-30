/*
 * Copyright 2021 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.injekt.ide

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.ivianuu.injekt.compiler.InjektFqNames
import com.ivianuu.injekt.compiler.RootPackageOption
import com.ivianuu.injekt.compiler.analysis.InjectFunctionDescriptor
import com.ivianuu.injekt.compiler.hasAnnotation
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.analyzer.moduleInfo
import org.jetbrains.kotlin.asJava.elements.KtLightDeclaration
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DeserializedDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.impl.LazyClassReceiverParameterDescriptor
import org.jetbrains.kotlin.idea.caches.project.getModuleInfo
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.core.unwrapModuleSourceInfo
import org.jetbrains.kotlin.idea.facet.KotlinFacet
import org.jetbrains.kotlin.idea.util.module
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.resolve.jvm.KotlinJavaPsiFacade
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

fun PsiElement.injektFqNames() = module
  ?.getOptionValueInFacet(RootPackageOption)
  ?.let { InjektFqNames(FqName(it)) }
  ?: InjektFqNames.Default

fun ModuleDescriptor.injektFqNames(): InjektFqNames = moduleInfo?.unwrapModuleSourceInfo()?.module
  ?.getOptionValueInFacet(RootPackageOption)
  ?.let { InjektFqNames(FqName(it)) }
  ?: InjektFqNames.Default

fun Module.getOptionValueInFacet(option: AbstractCliOption): String? {
  val kotlinFacet = KotlinFacet.get(this) ?: return null
  val commonArgs = kotlinFacet.configuration.settings.compilerArguments ?: return null

  val prefix = "plugin:com.ivianuu.injekt:${option.optionName}="

  val optionValue = commonArgs.pluginOptions
    ?.firstOrNull { it.startsWith(prefix) }
    ?.substring(prefix.length)

  return optionValue
}

fun PsiElement.ktElementOrNull() = safeAs<KtDeclaration>()
  ?: safeAs<KtLightDeclaration<*, *>>()?.kotlinOrigin

fun KtAnnotated.isProvideOrInjectDeclaration(): Boolean = hasAnnotation(injektFqNames().provide) ||
    (this is KtParameter && hasAnnotation(injektFqNames().inject)) ||
    safeAs<KtParameter>()?.getParentOfType<KtFunction>(false)
      ?.isProvideOrInjectDeclaration() == true ||
    safeAs<KtConstructor<*>>()?.getContainingClassOrObject()
      ?.isProvideOrInjectDeclaration() == true

fun ModuleDescriptor.isInjektEnabled(): Boolean = getCapability(ModuleInfo.Capability)
  ?.isInjektEnabled() ?: false

fun PsiElement.isInjektEnabled(): Boolean = try {
  getModuleInfo().isInjektEnabled()
} catch (e: Throwable) {
  false
}

fun ModuleInfo.isInjektEnabled(): Boolean {
  val module = unwrapModuleSourceInfo()?.module ?: return false
  val facet = KotlinFacet.get(module) ?: return false
  val pluginClasspath = facet.configuration.settings.compilerArguments?.pluginClasspaths ?: return false
  return pluginClasspath.any {
    it.contains("injekt-compiler-plugin")
  }
}

fun DeclarationDescriptor.findPsiDeclarations(project: Project, resolveScope: GlobalSearchScope): Collection<PsiElement> {
  if (this is PackageViewDescriptor)
    return listOf(
      KotlinJavaPsiFacade.getInstance(project)
        .findPackage(fqName.asString(), resolveScope)
    )

  if (this is InjectFunctionDescriptor)
    return underlyingDescriptor.findPsiDeclarations(project, resolveScope)

  if (this is ConstructorDescriptor &&
    constructedClass.kind == ClassKind.OBJECT)
    return constructedClass.findPsiDeclarations(project, resolveScope)

  if (this is ValueParameterDescriptor &&
    (containingDeclaration is DeserializedDescriptor ||
        containingDeclaration is InjectFunctionDescriptor)) {
    return listOfNotNull(
      containingDeclaration.findPsiDeclarations(project, resolveScope)
        .firstOrNull()
        .safeAs<KtFunction>()
        ?.valueParameters
        ?.get(index)
    )
  }

  if (this is LazyClassReceiverParameterDescriptor)
    return containingDeclaration.findPsiDeclarations(project, resolveScope)

  return DescriptorToSourceUtilsIde.getAllDeclarations(project, this, resolveScope)
}
