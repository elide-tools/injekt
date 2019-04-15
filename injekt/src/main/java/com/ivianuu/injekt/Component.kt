package com.ivianuu.injekt

import kotlin.collections.set
import kotlin.reflect.KClass

/**
 * The actual dependency container which provides bindings
 */
class Component @PublishedApi internal constructor() {

    private val dependencies = linkedSetOf<Component>()
    private val scopes = hashSetOf<Scope>()
    private val bindings = linkedMapOf<Key, Binding<*>>()
    private val instances = hashMapOf<Key, Instance<*>>()

    /**
     * Returns a instance of [T] matching the [type], [qualifier] and [parameters]
     */
    fun <T> get(
        type: KClass<*>,
        qualifier: Qualifier? = null,
        parameters: ParametersDefinition? = null
    ): T {
        val key = Key(type, qualifier)

        val instance = findInstance<T>(key)
            ?: throw BindingNotFoundException("${componentName()} Couldn't find a binding for $key")

        return instance.get(this, parameters)
    }

    /**
     * Adds all binding of the [module]
     */
    fun addModule(module: Module) {
        InjektPlugins.logger?.info("${componentName()} load module ${module.bindings.size}")
        module.bindings.forEach { addBinding(it.value) }
    }

    /**
     * Adds the [dependency] as a dependency
     */
    fun addDependency(dependency: Component) {
        if (!dependencies.add(dependency)) {
            error("Already added ${dependency.componentName()} to ${componentName()}")
        }
        InjektPlugins.logger?.info("${componentName()} Add dependency $dependency")
    }

    /**
     * Returns all direct dependencies of this component
     */
    fun getDependencies(): Set<Component> = dependencies

    /**
     * Adds the [scope]
     */
    fun addScope(scope: Scope) {
        if (!scopes.add(scope)) {
            error("Scope name $scope was already added")
        }
        InjektPlugins.logger?.info("${componentName()} Add scope name $scope")
    }

    /**
     * Returns all scope names of this component
     */
    fun getScopes(): Set<Scope> = scopes

    /**
     * Whether or not this component contains the [scope]
     */
    fun containsScope(scope: Scope): Boolean = scopes.contains(scope)

    /**
     * Returns all [Binding]s added to this component
     */
    fun getBindings(): Set<Binding<*>> = bindings.values.toSet()

    /**
     * Saves the [binding]
     */
    fun addBinding(binding: Binding<*>) {
        val isOverride = bindings.remove(binding.key) != null

        if (isOverride && !binding.override) {
            throw OverrideException("Try to override binding $binding but was already declared ${binding.key}")
        }

        check(binding.scope == null || scopes.contains(binding.scope)) {
            "Component scope ${componentName()} does not match binding scope ${binding.scope}"
        }

        bindings[binding.key] = binding

        val instance = binding.kind.createInstance(binding, this)

        instances[binding.key] = instance

        InjektPlugins.logger?.let { logger ->
            val msg = if (isOverride) {
                "${componentName()} Override $binding"
            } else {
                "${componentName()} Declare $binding"
            }
            logger.debug(msg)
        }

    }

    /**
     * Whether or not contains the [binding]
     */
    fun containsBinding(binding: Binding<*>): Boolean = bindings.containsKey(binding.key)

    /**
     * Returns all [Instance]s of this component
     */
    fun getInstances(): Set<Instance<*>> = instances.values.toSet()

    /**
     * Creates all eager instances of this component
     */
    fun createEagerInstances() {
        instances
            .filter { it.value.binding.eager }
            .forEach {
                InjektPlugins.logger?.info("${componentName()} Create eager instance for ${it.value.binding}")
                it.value.get(this, null)
            }
    }

    private fun <T> findInstance(key: Key): Instance<T>? {
        var instance = instances[key]

        if (instance != null) return instance as Instance<T>

        for (dependency in dependencies) {
            instance = dependency.findInstance<T>(key)
            if (instance != null) return instance
        }

        return null
    }

}

/**
 * Returns a new [Component] and applies the [definition]
 */
inline fun component(
    createEagerInstances: Boolean = true,
    definition: Component.() -> Unit = {}
): Component {
    return Component()
        .apply {
            definition.invoke(this)
            if (createEagerInstances) {
                createEagerInstances()
            }
        }
}

/**
 * Adds all [modules]
 */
fun Component.modules(modules: Iterable<Module>) {
    modules.forEach { addModule(it) }
}

/**
 * Adds all [modules]
 */
fun Component.modules(vararg modules: Module) {
    modules.forEach { addModule(it) }
}

/**
 * Adds the [module]
 */
fun Component.modules(module: Module) {
    addModule(module)
}

/**
 * Adds all [dependencies]
 */
fun Component.dependencies(dependencies: Iterable<Component>) {
    dependencies.forEach { addDependency(it) }
}

/**
 * Adds all [dependencies]
 */
fun Component.dependencies(vararg dependencies: Component) {
    dependencies.forEach { addDependency(it) }
}

/**
 * Adds the [dependency]
 */
fun Component.dependencies(dependency: Component) {
    addDependency(dependency)
}

/**
 * Adds all of [scopes]
 */
fun Component.scopes(scopes: Iterable<Scope>) {
    scopes.forEach { addScope(it) }
}

/**
 * Adds all [scopes]
 */
fun Component.scopes(vararg scopes: Scope) {
    scopes.forEach { addScope(it) }
}

/**
 * Adds the [scope]
 */
fun Component.scopes(scope: Scope) {
    addScope(scope)
}

/**
 * Returns a instance of [T] matching the [qualifier] and [parameters]
 */
inline fun <reified T> Component.get(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null
): T = get(T::class, qualifier, parameters)

/**
 * Lazily returns a instance of [T] matching the [qualifier] and [parameters]
 */
inline fun <reified T> Component.inject(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null
): Lazy<T> = lazy(LazyThreadSafetyMode.NONE) { get<T>(qualifier, parameters) }

/**
 * Returns a [Provider] for [T] and [qualifier]
 * Each [Provider.get] call results in a potentially new value
 */
inline fun <reified T> Component.getProvider(
    qualifier: Qualifier? = null,
    noinline defaultParameters: ParametersDefinition? = null
): Provider<T> = provider { parameters: ParametersDefinition? ->
    get<T>(qualifier, parameters ?: defaultParameters)
}

/**
 * Returns a [Provider] for [T] and [qualifier]
 * Each [Provider.get] call results in a potentially new value
 */
inline fun <reified T> Component.injectProvider(
    qualifier: Qualifier? = null,
    noinline defaultParameters: ParametersDefinition? = null
): Lazy<Provider<T>> = lazy(LazyThreadSafetyMode.NONE) {
    provider { parameters: ParametersDefinition? ->
        get<T>(qualifier, parameters ?: defaultParameters)
    }
}

fun Component.componentName(): String {
    return if (getScopes().isNotEmpty()) {
        "Component[${getScopes().joinToString(",")}]"
    } else {
        "Component[Unscoped]"
    }
}