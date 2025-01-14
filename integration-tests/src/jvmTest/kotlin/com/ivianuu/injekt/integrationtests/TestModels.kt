/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.injekt.integrationtests

import com.ivianuu.injekt.Tag

class Foo

class Bar(val foo: Foo)

class Baz(val bar: Bar, val foo: Foo)

interface Command

class CommandA : Command

class CommandB : Command

@Tag annotation class Tag1

@Tag annotation class Tag2

@Tag annotation class TypedTag<T>

object TestScope1

object TestScope2
