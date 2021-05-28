package com.ivianuu.injekt.ide.usages

import com.intellij.find.findUsages.*
import com.intellij.psi.*
import com.intellij.psi.search.searches.*
import com.intellij.usageView.*
import com.intellij.usages.*
import com.intellij.usages.rules.*
import com.intellij.util.*
import com.ivianuu.injekt.compiler.*
import com.ivianuu.injekt.compiler.resolution.*
import org.jetbrains.kotlin.asJava.classes.*
import org.jetbrains.kotlin.idea.caches.resolve.*
import org.jetbrains.kotlin.idea.findUsages.*
import org.jetbrains.kotlin.idea.stubindex.*
import org.jetbrains.kotlin.idea.util.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.jetbrains.kotlin.utils.addToStdlib.*

class InjektUsageSearcher : CustomUsageSearcher() {
  override fun processElementUsages(
    element: PsiElement,
    processor: Processor<in Usage>,
    options: FindUsagesOptions
  ) {
    if (element !is KtDeclaration) return
    val project = element.project

    project.runReadActionInSmartMode {
      if (!element.hasAnnotation(InjektFqNames.Provide) &&
        !element.hasAnnotation(InjektFqNames.Inject) &&
        element.parent.safeAs<KtFunction>()?.hasAnnotation(InjektFqNames.Provide) != true &&
        (element !is KtPrimaryConstructor && element.getParentOfType<KtClassOrObject>(false)
          ?.hasAnnotation(InjektFqNames.Provide) != true))
        return@runReadActionInSmartMode

      val descriptor = element.resolveToDescriptorIfAny() ?: return@runReadActionInSmartMode

      val uniqueKey = descriptor.uniqueKey(InjektContext(descriptor.module))

      val useScope = element.resolveScope

      val injectAnnotation = JavaPsiFacade.getInstance(project)
        .findClass(InjektFqNames.Inject.asString(), useScope) as KtLightClass

      val scope = KotlinSourceFilterScope.sourcesAndLibraries(useScope, project)
      KotlinAnnotationsIndex.getInstance().get(injectAnnotation.name!!, project, scope)
        .mapNotNull { it.getParentOfType<KtFunction>(false) }
        .distinct()
        .forEach { function ->
          try {
            function.processAllUsages(KotlinFunctionFindUsagesOptions(project)) { usage ->
              val call = usage.element?.parent as? KtCallExpression ?: return@processAllUsages
              val bindingContext = call.getResolutionFacade().analyze(call)
              val graph = bindingContext[InjektWritableSlices.INJECTION_GRAPH_FOR_CALL, call]
                ?: return@processAllUsages
              graph.safeAs<InjectionGraph.Success>()?.forEachResultRecursive { request, result ->
                if (uniqueKey == result.candidate.safeAs<CallableInjectable>()
                    ?.callable?.callable?.uniqueKey(result.scope.context)) {
                  processor.process(
                    object : UsageInfo2UsageAdapter(UsageInfo(call)) {
                    }
                  )
                }
              }
            }
          } catch (e: Throwable) {
            e.printStackTrace()
          }
        }
    }
  }
}
