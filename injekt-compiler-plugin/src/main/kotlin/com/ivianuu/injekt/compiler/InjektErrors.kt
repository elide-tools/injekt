package com.ivianuu.injekt.compiler

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages
import org.jetbrains.kotlin.diagnostics.rendering.DiagnosticFactoryToRendererMap

interface InjektErrors {
    companion object {
        @JvmField
        val FORBIDDEN_MODULE_INVOCATION = error()
        @JvmField
        val CONDITIONAL_NOT_ALLOWED_IN_MODULE_AND_FACTORIES = error()
        @JvmField
        val RETURN_TYPE_NOT_ALLOWED_FOR_MODULE = error()
        @JvmField
        val CREATE_IMPl_WITHOUT_FACTORY = error()
        @JvmField
        val CREATE_INSTANCE_IN_CHILD_FACTORY = error()
        @JvmField
        val UNSUPPORTED_MAP_KEY_TYPE = error()
        @JvmField
        val MAP_KEY_MUST_BE_CONSTANT = error()
        @JvmField
        val NOT_A_SCOPE = error()
        @JvmField
        val NOT_A_CHILD_FACTORY = error()
        @JvmField
        val LAST_STATEMENT_MUST_BE_CREATE = error()
        @JvmField
        val CANNOT_BE_LOCAL = error()
        @JvmField
        val NO_TYPE_PARAMETERS_ON_FACTORY = error()
        @JvmField
        val FACTORY_IMPL_MUST_BE_ABSTRACT = error()
        @JvmField
        val IMPL_SUPER_TYPE_MUST_HAVE_EMPTY_CONSTRUCTOR = error()
        @JvmField
        val IMPL_CANNOT_CONTAIN_VARS = error()
        @JvmField
        val PROVISION_FUNCTION_CANNOT_HAVE_VALUE_PARAMETERS = error()
        @JvmField
        val PROVISION_FUNCTION_CANNOT_HAVE_TYPE_PARAMETERS = error()
        @JvmField
        val PROVISION_FUNCTION_CANNOT_BE_SUSPEND = error()
        @JvmField
        val INJECT_MUST_BE_LATEINIT_VAR = error()
        @JvmField
        val CANNOT_INVOKE_CHILD_FACTORIES = error()
        @JvmField
        val MUST_HAVE_RUNTIME_RETENTION = error()
        @JvmField
        val MISSING_QUALIFIER_TARGETS = error()
        @JvmField
        val ANNOTATED_BINDING_CANNOT_BE_ABSTRACT = error()
        @JvmField
        val EITHER_MODULE_OR_FACTORY = error()
        @JvmField
        val CANNOT_BE_INLINE = error()
        @JvmField
        val CANNOT_BE_SUSPEND = error()
        @JvmField
        val CLASS_OF_OUTSIDE_OF_MODULE = error()

        private fun error() = DiagnosticFactory0.create<PsiElement>(Severity.ERROR)

        init {
            Errors.Initializer.initializeFactoryNamesAndDefaultErrorMessages(
                InjektErrors::class.java,
                InjektDefaultErrorMessages
            )
        }
    }
}

object InjektDefaultErrorMessages : DefaultErrorMessages.Extension {
    private val map = DiagnosticFactoryToRendererMap("Injekt")
    override fun getMap(): DiagnosticFactoryToRendererMap = map

    init {
        map.put(
            InjektErrors.FORBIDDEN_MODULE_INVOCATION,
            "Only @Factory, @ChildFactory or @Module functions can invoke @Module functions"
        )
        map.put(
            InjektErrors.CONDITIONAL_NOT_ALLOWED_IN_MODULE_AND_FACTORIES,
            "Conditional logic is not allowed inside @Factory, @ChildFactory and @Module functions"
        )
        map.put(
            InjektErrors.RETURN_TYPE_NOT_ALLOWED_FOR_MODULE,
            "@Module functions cannot return anything"
        )
        map.put(
            InjektErrors.CREATE_IMPl_WITHOUT_FACTORY,
            "createImpl can only be called from within a @Factory or @ChildFactory function"
        )
        map.put(
            InjektErrors.CREATE_INSTANCE_IN_CHILD_FACTORY,
            "createInstance cannot be called in a @ChildFactory function"
        )
        map.put(
            InjektErrors.UNSUPPORTED_MAP_KEY_TYPE,
            "Unsupported map key type"
        )

        map.put(
            InjektErrors.MAP_KEY_MUST_BE_CONSTANT,
            "Map key must be a compile time constant"
        )
        map.put(
            InjektErrors.NOT_A_SCOPE,
            "Scopes must be itself annotated with @Scope"
        )
        map.put(
            InjektErrors.NOT_A_CHILD_FACTORY,
            "Not a @ChildFactory"
        )
        map.put(
            InjektErrors.LAST_STATEMENT_MUST_BE_CREATE,
            "A factories last statement must be either a createImpl() or a createInstance() call"
        )
        map.put(
            InjektErrors.FACTORY_IMPL_MUST_BE_ABSTRACT,
            "createImpl result types must be a interface or a abstract class"
        )
        map.put(
            InjektErrors.CANNOT_BE_LOCAL,
            "@Factory, @ChildFactory and @Module functions cannot be local functions"
        )
        map.put(
            InjektErrors.NO_TYPE_PARAMETERS_ON_FACTORY,
            "@Factory and @ChildFactory functions cannot have type parameters"
        )
        map.put(
            InjektErrors.IMPL_CANNOT_CONTAIN_VARS,
            "createImpl result types cannot contain mutable properties"
        )
        map.put(
            InjektErrors.IMPL_SUPER_TYPE_MUST_HAVE_EMPTY_CONSTRUCTOR,
            "Implementation super class must have an empty constructor"
        )
        map.put(
            InjektErrors.PROVISION_FUNCTION_CANNOT_HAVE_VALUE_PARAMETERS,
            "Provision functions cannot have value parameters"
        )
        map.put(
            InjektErrors.PROVISION_FUNCTION_CANNOT_HAVE_TYPE_PARAMETERS,
            "Provision functions cannot have type parameters"
        )
        map.put(
            InjektErrors.PROVISION_FUNCTION_CANNOT_BE_SUSPEND,
            "Provision functions cannot be suspend"
        )
        map.put(
            InjektErrors.INJECT_MUST_BE_LATEINIT_VAR,
            "@Inject property must be lateinit var"
        )
        map.put(
            InjektErrors.CANNOT_INVOKE_CHILD_FACTORIES,
            "Cannot invoke @ChildFactory functions"
        )
        map.put(
            InjektErrors.MUST_HAVE_RUNTIME_RETENTION,
            "Annotated class must have runtime retention"
        )
        map.put(
            InjektErrors.MISSING_QUALIFIER_TARGETS,
            "@Qualifier must be annotated with @Target(AnnotationTarget.TYPE, AnnotationTarget.EXPRESSION)"
        )
        map.put(
            InjektErrors.ANNOTATED_BINDING_CANNOT_BE_ABSTRACT,
            "Annotated bindings cannot be an interface or abstract"
        )
        map.put(
            InjektErrors.EITHER_MODULE_OR_FACTORY,
            "A function can only be annotated with one of @Factory, @ChildFactory or @Module"
        )
        map.put(
            InjektErrors.CANNOT_BE_INLINE,
            "@Factory, @ChildFactory or @Module cannot be inlined"
        )
        map.put(
            InjektErrors.CANNOT_BE_SUSPEND,
            "@Factory, @ChildFactory or @Module cannot be suspend"
        )
        map.put(
            InjektErrors.CLASS_OF_OUTSIDE_OF_MODULE,
            "classOf() can only be called from inside @Factory, @ChildFactory or @Module functions"
        )
    }
}
