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

package com.ivianuu.injekt.compiler

import com.google.auto.service.AutoService
import com.ivianuu.injekt.compiler.analysis.IncrementalFixAnalysisHandlerExtension
import com.ivianuu.injekt.compiler.analysis.InjectSyntheticScopeProviderExtension
import com.ivianuu.injekt.compiler.analysis.InjektDiagnosticSuppressor
import com.ivianuu.injekt.compiler.analysis.InjektStorageComponentContainerContributor
import com.ivianuu.injekt.compiler.transform.InjektIrGenerationExtension
import com.ivianuu.shaded_injekt.Provide
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions
import org.jetbrains.kotlin.com.intellij.openapi.extensions.LoadingOrder
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.extensions.StorageComponentContainerContributor
import org.jetbrains.kotlin.resolve.diagnostics.DiagnosticSuppressor
import org.jetbrains.kotlin.resolve.extensions.AnalysisHandlerExtension
import org.jetbrains.kotlin.synthetic.SyntheticScopeProviderExtension
import java.io.File

@AutoService(ComponentRegistrar::class)
class InjektComponentRegistrar : ComponentRegistrar {
  override fun registerProjectComponents(
    project: MockProject,
    configuration: CompilerConfiguration,
  ) {
    if (configuration.isKaptCompilation() ||
      configuration.get(DumpDirKey) == null) return

    configuration.put(CLIConfigurationKeys.ALLOW_KOTLIN_PACKAGE, true)

    project.registerExtensions(configuration)
  }
}

private fun MockProject.registerExtensions(configuration: CompilerConfiguration) {
  @Provide val injektFqNames = InjektFqNames(configuration.getNotNull(RootPackageKey))
  AnalysisHandlerExtension.registerExtension(
    this,
    IncrementalFixAnalysisHandlerExtension()
  )

  StorageComponentContainerContributor.registerExtension(
    this,
    InjektStorageComponentContainerContributor { injektFqNames }
  )
  IrGenerationExtension.registerExtensionWithLoadingOrder(
    this,
    LoadingOrder.FIRST,
    InjektIrGenerationExtension(dumpDir(configuration))
  )

  // extension point does not exist CLI for some reason
  // but it's still queried later
  SyntheticScopeProviderExtension.registerExtensionPoint(this)
  SyntheticScopeProviderExtension.registerExtension(
    this,
    InjectSyntheticScopeProviderExtension({ injektFqNames })
  )

  @Suppress("DEPRECATION")
  Extensions.getRootArea().getExtensionPoint(DiagnosticSuppressor.EP_NAME)
    .registerExtension(InjektDiagnosticSuppressor(), this)
}

private fun CompilerConfiguration.isKaptCompilation(): Boolean {
  val outputDir = this[JVMConfigurationKeys.OUTPUT_DIRECTORY]
  val kaptOutputDirs = listOf(
    listOf("tmp", "kapt3", "stubs"),
    listOf("tmp", "kapt3", "incrementalData"),
    listOf("tmp", "kapt3", "incApCache")
  ).map { File(it.joinToString(File.separator)) }
  return kaptOutputDirs.any { outputDir?.parentFile?.endsWith(it) == true }
}

fun IrGenerationExtension.Companion.registerExtensionWithLoadingOrder(
  project: MockProject,
  loadingOrder: LoadingOrder,
  extension: IrGenerationExtension,
) {
  project.extensionArea
    .getExtensionPoint(IrGenerationExtension.extensionPointName)
    .registerExtension(extension, loadingOrder, project)
}
