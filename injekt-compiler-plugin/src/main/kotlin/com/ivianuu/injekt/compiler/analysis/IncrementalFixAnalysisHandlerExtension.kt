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

package com.ivianuu.injekt.compiler.analysis

import com.ivianuu.injekt.compiler.Context
import com.ivianuu.injekt.compiler.InjektFqNames
import com.ivianuu.injekt.compiler.hasAnnotation
import com.ivianuu.injekt.compiler.injectablesLookupName
import com.ivianuu.injekt.compiler.moduleName
import com.ivianuu.injekt.compiler.updatePrivateFinalField
import com.ivianuu.shaded_injekt.Inject
import com.ivianuu.shaded_injekt.Provide
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentImpl
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.SingleRootFileViewProvider
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.namedDeclarationRecursiveVisitor
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.toVisibility
import org.jetbrains.kotlin.psi.psiUtil.visibilityModifier
import org.jetbrains.kotlin.psi.psiUtil.visibilityModifierTypeOrDefault
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.extensions.AnalysisHandlerExtension
import java.util.Base64

class IncrementalFixAnalysisHandlerExtension(
  @Inject private val injektFqNames: InjektFqNames
) : AnalysisHandlerExtension {
  private var appliedFix = false

  override fun doAnalysis(
    project: Project,
    module: ModuleDescriptor,
    projectContext: ProjectContext,
    files: Collection<KtFile>,
    bindingTrace: BindingTrace,
    componentProvider: ComponentProvider
  ): AnalysisResult? {
    if (!appliedFix) {
      appliedFix = true

      val newFiles = files.map { processFile(project, module, it) }
      files as MutableList<KtFile>
      files.clear()
      files += newFiles

      return AnalysisResult.RetryWithAdditionalRoots(
        BindingContext.EMPTY,
        module,
        emptyList(),
        emptyList(), emptyList(),
        true
      )
    }

    return null
  }

  private fun processFile(
    project: Project,
    module: ModuleDescriptor,
    file: KtFile
  ): KtFile {
    if (file.text.contains(FILE_MARKER_COMMENT))
      return file

    val injectables = mutableListOf<KtNamedDeclaration>()

    file.accept(
      namedDeclarationRecursiveVisitor { declaration ->
        if (declaration.fqName == null) return@namedDeclarationRecursiveVisitor

        val visibility =
          declaration.visibilityModifierTypeOrDefault().toVisibility()

        if (visibility == DescriptorVisibilities.PUBLIC ||
            visibility == DescriptorVisibilities.INTERNAL ||
            visibility == DescriptorVisibilities.PROTECTED) {
          when (declaration) {
            is KtClassOrObject -> {
              if (!declaration.isLocal && declaration.hasAnnotation(injektFqNames.provide) ||
                declaration.primaryConstructor?.hasAnnotation(injektFqNames.provide) == true ||
                declaration.secondaryConstructors.any { it.hasAnnotation(injektFqNames.provide) } ||
                declaration.hasAnnotation(injektFqNames.component) ||
                declaration.hasAnnotation(injektFqNames.entryPoint))
                  injectables += declaration
            }
            is KtNamedFunction -> {
              if (!declaration.isLocal && (declaration.hasAnnotation(injektFqNames.provide) ||
                    declaration.getParentOfType<KtClass>(false)?.hasAnnotation(injektFqNames.component) == true ||
                    declaration.getParentOfType<KtClass>(false)?.hasAnnotation(injektFqNames.entryPoint) == true))
                injectables += declaration
            }
            is KtProperty -> {
              if (!declaration.isLocal && (declaration.hasAnnotation(injektFqNames.provide) ||
                    declaration.getParentOfType<KtClass>(false)?.hasAnnotation(injektFqNames.component) == true ||
                    declaration.getParentOfType<KtClass>(false)?.hasAnnotation(injektFqNames.entryPoint) == true))
                injectables += declaration
            }
          }
        }
      }
    )

    if (injectables.isEmpty()) return file

    val code = buildString {
      appendLine(file.text)

      appendLine()
      appendLine(FILE_MARKER_COMMENT)
      appendLine()

      @Provide val ctx = Context(module, injektFqNames, null)
      val markerName = "_${
        module.moduleName()
          .filter { it.isLetterOrDigit() }
      }_${
        file.name.removeSuffix(".kt")
          .substringAfterLast(".")
          .substringAfterLast("/")
      }_ProvidersMarker"

      appendLine("object $markerName")

      appendLine()

      for ((i, injectable) in injectables.withIndex()) {
        val functionName = injectablesLookupName(
          injectable.fqName ?: error("Wtf ${injectable.text}"),
          file.packageFqName
        )

        appendLine("fun $functionName(")
        appendLine("  marker: $markerName,")
        repeat(i + 1) {
          appendLine("  index$it: Byte,")
        }

        val hash = when (injectable) {
          is KtClassOrObject ->
            "class" +
                injectable.name.orEmpty() +
                injectable.visibilityModifier()?.text.orEmpty() +
                injectable.annotationEntries.joinToString { it.text } +
                injectable.primaryConstructor
                  ?.let {
                    "primary constructor" +
                        it.valueParameters
                          .joinToString { it.text }
                  } + injectable.secondaryConstructors
              .mapIndexed { index, it ->
                "secondary_constructor_$index" +
                    it.valueParameters
                      .joinToString(it.text)
              } + injectable.superTypeListEntries
              .joinToString { it.text }
          is KtFunction ->
            "function" +
                injectable.name.orEmpty() +
                injectable.visibilityModifier()?.text.orEmpty() +
                injectable.hasModifier(KtTokens.SUSPEND_KEYWORD).toString() +
                injectable.modifierList +
                injectable.annotationEntries.joinToString { it.text } +
                injectable.receiverTypeReference?.text.orEmpty() +
                injectable.valueParameters
                  .joinToString { it.text } +
                injectable.typeReference?.text.orEmpty()
          is KtProperty ->
            "property" +
                injectable.name.orEmpty() +
                injectable.visibilityModifier()?.text.orEmpty() +
                injectable.annotationEntries.joinToString { it.text } +
                injectable.getter?.annotationEntries?.joinToString { it.text }.orEmpty() +
                injectable.receiverTypeReference?.text.orEmpty() +
                injectable.typeReference?.text.orEmpty()
          else -> throw AssertionError()
        }

        val finalHash = String(Base64.getEncoder().encode(hash.toByteArray()))

        finalHash
          .filter { it.isLetterOrDigit() }
          .chunked(256)
          .forEachIndexed { index, value ->
            appendLine("  hash_${index}_$value: Int,")
          }

        appendLine(") {")
        appendLine("}")
        appendLine()
      }
    }

    return KtFile(
      viewProvider = object : SingleRootFileViewProvider(
        PsiManager.getInstance(project),
        file.virtualFile
      ) {
        override fun getDocument(): Document? = super.getDocument()
          ?.also {
            if (!it.text.contains(FILE_MARKER_COMMENT)) {
              it.updatePrivateFinalField<Boolean>(
                DocumentImpl::class,
                "myAssertThreading"
              ) { false }
              it.setText(code)
            }
          }
      }.also { it.document },
      isCompiled = false
    )
  }
}

private const val FILE_MARKER_COMMENT = "// injekt incremental " + "supporting declarations"
