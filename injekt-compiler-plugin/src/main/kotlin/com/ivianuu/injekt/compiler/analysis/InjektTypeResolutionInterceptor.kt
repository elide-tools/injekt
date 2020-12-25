package com.ivianuu.injekt.compiler.analysis

import com.ivianuu.injekt.compiler.InjektFqNames
import com.ivianuu.injekt.compiler.findAnnotation
import com.ivianuu.injekt.compiler.hasAnnotation
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.extensions.internal.TypeResolutionInterceptorExtension
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.expressions.ExpressionTypingContext
import org.jetbrains.kotlin.types.typeUtil.replaceAnnotations
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

@Suppress("INVISIBLE_REFERENCE", "EXPERIMENTAL_IS_NOT_ENABLED", "IllegalExperimentalApiUsage")
@OptIn(org.jetbrains.kotlin.extensions.internal.InternalNonStableExtensionPoints::class)
class InjektTypeResolutionInterceptor : TypeResolutionInterceptorExtension {

    override fun interceptType(
        element: KtElement,
        context: ExpressionTypingContext,
        resultType: KotlinType,
    ): KotlinType {
        if (resultType === TypeUtils.NO_EXPECTED_TYPE) return resultType
        if (element !is KtLambdaExpression) return resultType
        return ANNOTATIONS.fold(resultType) { current, annotationFqName ->
            if (current.hasAnnotation(annotationFqName)) return@fold current
            var annotation: KtAnnotationEntry? = null
            annotation = element.safeAs<KtAnnotated>()?.findAnnotation(annotationFqName)
            if (annotation == null) {
                annotation = element.parent.safeAs<KtAnnotated>()?.findAnnotation(annotationFqName)
            }
            if (annotation == null && context.expectedType !== TypeUtils.NO_EXPECTED_TYPE) {
                annotation =
                    context.expectedType.safeAs<KtAnnotated>()?.findAnnotation(annotationFqName)
            }
            if (annotation != null) {
                val annotationDescriptor = context.trace[BindingContext.ANNOTATION, annotation]
                if (annotationDescriptor != null) {
                    return@fold current.replaceAnnotations(
                        Annotations.create(current.annotations + annotationDescriptor))
                }
            }
            current
        }
    }

    private companion object {
        private val ANNOTATIONS = listOf(
            InjektFqNames.Given,
            InjektFqNames.GivenSetElement,
            InjektFqNames.Module,
            InjektFqNames.Interceptor
        )
    }
}
