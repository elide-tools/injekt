package com.ivianuu.injekt.compiler

import com.ivianuu.injekt.Binding
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.checkers.DeclarationChecker
import org.jetbrains.kotlin.resolve.checkers.DeclarationCheckerContext

@Binding class GivenChecker : DeclarationChecker {

    override fun check(
        declaration: KtDeclaration,
        descriptor: DeclarationDescriptor,
        context: DeclarationCheckerContext,
    ) {
        if (descriptor is SimpleFunctionDescriptor) {
            if (descriptor.hasAnnotation(InjektFqNames.Given) &&
                descriptor.extensionReceiverParameter != null
            ) {
                context.trace.report(
                    InjektErrors.GIVEN_DECLARATION_WITH_EXTENSION_RECEIVER
                        .on(declaration)
                )
            }
            descriptor.valueParameters
                .checkParameters(declaration, descriptor, context.trace)
        } else if (descriptor is ClassDescriptor) {
            descriptor.constructors
                .forEach {
                    it.valueParameters
                        .checkParameters(it.findPsi() as KtDeclaration, descriptor, context.trace)
                }
        } else if (descriptor is PropertyDescriptor) {
            if (descriptor.hasAnnotation(InjektFqNames.Given) &&
                descriptor.extensionReceiverParameter?.type?.hasAnnotation(InjektFqNames.Given) == true
            ) {
                context.trace.report(
                    InjektErrors.GIVEN_DECLARATION_WITH_EXTENSION_RECEIVER
                        .on(declaration)
                )
            }
        }
    }

    private fun List<ParameterDescriptor>.checkParameters(
        declaration: KtDeclaration,
        descriptor: DeclarationDescriptor,
        trace: BindingTrace,
    ) {
        this
            .filterIsInstance<ValueParameterDescriptor>()
            .filter { it.type.hasAnnotation(InjektFqNames.Given) }
            .filterNot { it.declaresDefaultValue() }
            .forEach {
                trace.report(
                    InjektErrors.GIVEN_PARAMETER_WITHOUT_DEFAULT
                        .on(it.findPsi() ?: declaration)
                )
            }
        if (descriptor.hasAnnotation(InjektFqNames.Given)) {
            this
                .filterNot { it.type.hasAnnotation(InjektFqNames.Given) }
                .forEach {
                    trace.report(
                        InjektErrors.NON_GIVEN_VALUE_PARAMETER_ON_GIVEN_DECLARATION
                            .on(it.findPsi() ?: declaration)
                    )
                }
        }
    }
}
