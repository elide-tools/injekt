package com.ivianuu.injekt.compiler.transform.factory

import com.ivianuu.injekt.compiler.InjektSymbols
import com.ivianuu.injekt.compiler.transform.InjektDeclarationStore
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.defaultType

abstract class AbstractFactoryProduct(
    val moduleClass: IrClass,
    val context: IrPluginContext,
    val symbols: InjektSymbols,
    val factoryTransformer: TopLevelFactoryTransformer,
    val declarationStore: InjektDeclarationStore
) {

    lateinit var graph: Graph
        private set

    abstract val factoryMembers: FactoryMembers

    lateinit var factoryExpressions: FactoryExpressions
        private set

    protected fun init(
        parent: AbstractFactoryProduct?,
        dependencyRequests: List<DependencyRequest>,
        moduleAccessor: InitializerAccessor
    ) {
        factoryExpressions = FactoryExpressions(
            context = context,
            symbols = symbols,
            members = factoryMembers,
            parent = parent?.factoryExpressions,
            factoryProduct = this
        )
        graph = Graph(
            parent = parent?.graph,
            factoryProduct = this,
            factoryTransformer = factoryTransformer,
            context = context,
            factoryImplementationModule = ModuleNode(
                key = moduleClass.defaultType.asKey(context),
                module = moduleClass,
                initializerAccessor = moduleAccessor,
                typeParametersMap = emptyMap()
            ),
            declarationStore = declarationStore,
            symbols = symbols,
            factoryMembers = factoryMembers
        ).also { factoryExpressions.graph = it }

        graph.validate(dependencyRequests)
    }

}