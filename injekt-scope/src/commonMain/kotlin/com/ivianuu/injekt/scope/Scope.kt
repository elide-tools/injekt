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

package com.ivianuu.injekt.scope

import com.ivianuu.injekt.*
import com.ivianuu.injekt.common.*

interface Scope {
  /**
   * Whether or not this scope is disposed
   */
  val isDisposed: Boolean

  /**
   * Returns the scoped value [T] for [key] or null
   */
  @InternalScopeApi fun <T : Any> get(key: Any): T?

  /**
   * Store's [value] for [key]
   *
   * If [value] is a [ScopeDisposable] [ScopeDisposable.dispose] will be invoked once this scope gets disposed
   */
  @InternalScopeApi fun <T : Any> set(key: Any, value: T)

  /**
   * Removes the scoped value for [key]
   */
  @InternalScopeApi fun remove(key: Any)
}

@RequiresOptIn annotation class InternalScopeApi

/**
 * Runs the [block] with a fresh [Scope] which will be disposed after the execution
 */
inline fun <R> withScope(block: (@Inject Scope) -> R): R {
  @Provide val scope = DisposableScope()
  return try {
    block()
  } finally {
    scope.dispose()
  }
}

/**
 * Returns an existing instance of [T] for key [key] or creates and caches a new instance by calling function [computation]
 */
@OptIn(InternalScopeApi::class)
inline fun <T : Any> scoped(key: Any, @Inject scope: Scope, computation: () -> T): T {
  scope.get<T>(key)?.let { return it }
  scope.withLock {
    scope.get<T>(key)?.let { return it }
    val value = computation()
    scope.set(key, value)
    return value
  }
}

inline fun <T : Any> scoped(@Inject scope: Scope, @Inject key: TypeKey<T>, computation: () -> T): T =
  scoped(key = key.value, computation = computation)

/**
 * Invokes the [action] function once [scope] gets disposed
 * or invokes it synchronously if [scope] is already disposed
 *
 * Returns a [ScopeDisposable] to unregister the [action]
 */
inline fun invokeOnDispose(@Inject scope: Scope, crossinline action: () -> Unit): ScopeDisposable =
  ScopeDisposable { action() }.bind()

inline fun <R> Scope.withLock(block: () -> R): R = synchronized(this, block)

private val NoOpScopeDisposable = ScopeDisposable { }

/**
 * Allows scoped values to be notified when the hosting [Scope] gets disposed
 */
fun interface ScopeDisposable {
  /**
   * Will be called when the hosting [Scope] gets disposed
   */
  fun dispose()
}

/**
 * Disposes this disposable once [scope] gets disposed
 * or synchronously if [scope] is already disposed
 *
 * Returns a [ScopeDisposable] to unregister for disposables
 */
@OptIn(InternalScopeApi::class)
fun ScopeDisposable.bind(@Inject scope: Scope): ScopeDisposable {
  if (scope.isDisposed) {
    dispose()
    return NoOpScopeDisposable
  }
  scope.withLock {
    if (scope.isDisposed) {
      dispose()
      return NoOpScopeDisposable
    }

    class DisposableKey

    val key = DisposableKey()
    var notifyDisposal = true
    scope.set(key, ScopeDisposable {
      if (notifyDisposal) dispose()
    })
    return ScopeDisposable {
      notifyDisposal = false
      scope.remove(key)
    }
  }
}

/**
 * A mutable version of [Scope] which is also a [ScopeDisposable]
 */
interface DisposableScope : Scope, ScopeDisposable

/**
 * Returns a new [DisposableScope]
 */
fun DisposableScope(): DisposableScope = DisposableScopeImpl()

private class DisposableScopeImpl : DisposableScope {
  private var _isDisposed = false
  override val isDisposed: Boolean
    get() {
      if (_isDisposed) return true
      return synchronized(this) { _isDisposed }
    }

  private var values: MutableMap<Any, Any>? = null
  private fun values(): MutableMap<Any, Any> =
    (values ?: hashMapOf<Any, Any>().also { values = it })

  @Suppress("UNCHECKED_CAST")
  @InternalScopeApi
  override fun <T : Any> get(key: Any): T? = values?.get(key) as? T

  @InternalScopeApi
  override fun <T : Any> set(key: Any, value: T) {
    synchronizedWithDisposedCheck {
      removeScopedValueImpl(key)
      values()[key] = value
    } ?: kotlin.run {
      (value as? ScopeDisposable)?.dispose()
    }
  }

  @InternalScopeApi
  override fun remove(key: Any) {
    synchronizedWithDisposedCheck { removeScopedValueImpl(key) }
  }

  override fun dispose() {
    synchronizedWithDisposedCheck {
      _isDisposed = true
      if (values != null && values!!.isNotEmpty()) {
        values!!.keys
          .toList()
          .forEach { removeScopedValueImpl(it) }
      }
    }
  }

  private fun removeScopedValueImpl(key: Any) {
    (values?.remove(key) as? ScopeDisposable)?.dispose()
  }

  private inline fun <R> synchronizedWithDisposedCheck(block: () -> R): R? {
    if (_isDisposed) return null
    synchronized(this) {
      if (_isDisposed) return null
      return block()
    }
  }
}

expect inline fun <T> synchronized(lock: Any, block: () -> T): T
