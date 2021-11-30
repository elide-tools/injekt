/*
 * Copyright 2021 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.injekt.common

import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.inject
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.reflect.KClass
import kotlin.reflect.KTypeT

class CommonInjectablesTest {
  @Test fun testCanResolveMap() {
    @Provide val elementsA = listOf("a" to "a")
    @Provide val elementB = "b" to "b"
    val map = inject<Map<String, String>>()
    map.size shouldBe 2
    map["a"] shouldBe "a"
    map["b"] shouldBe "b"
  }

  @Test fun testCanResolveLazy() {
    inject<Lazy<Foo>>()
  }

  @Test fun testCanResolveKClass() {
    inject<KClass<Foo>>()
  }

  @Test fun testCanResolveKType() {
    inject<KTypeT<Foo>>()
  }

  @Test fun testCanResolveTypeKey() {
    inject<TypeKey<Foo>>()
  }

  @Provide private class Foo
}
