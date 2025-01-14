/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.injekt.common

import com.ivianuu.injekt.Provide

@Provide object CollectionModule {
  /**
   * Provides a [Map] of [K] [V] for each [List] of [Pair] of [K] [V]
   */
  @Provide inline fun <K, V> mapOfPairs(pairs: List<Pair<K, V>>): Map<K, V> = pairs.toMap()
}
