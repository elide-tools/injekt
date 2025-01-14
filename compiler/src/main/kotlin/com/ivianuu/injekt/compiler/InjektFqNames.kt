/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.injekt.compiler

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.name.FqName

object InjektFqNames {
  val InjektPackage = FqName("com.ivianuu.injekt")
  val Inject = InjektPackage.child("Inject".asNameId())
  val Provide = InjektPackage.child("Provide".asNameId())
  val Tag = InjektPackage.child("Tag".asNameId())
  val Spread = InjektPackage.child("Spread".asNameId())

  val InternalPackage = InjektPackage.child("internal".asNameId())
  val DeclarationInfo = InternalPackage.child("DeclarationInfo".asNameId())
  val TypeParameterInfo = InternalPackage.child("TypeParameterInfo".asNameId())

  val InjectablesPackage = InternalPackage.child("injectables".asNameId())
  val InjectablesLookup = InjectablesPackage.child("\$\$\$\$\$".asNameId())

  val CommonPackage = InjektPackage.child("common".asNameId())
  val SourceKey = CommonPackage.child("SourceKey".asNameId())
  val TypeKey = CommonPackage.child("TypeKey".asNameId())

  val Composable = FqName("androidx.compose.runtime.Composable")

  val Any = StandardNames.FqNames.any.toSafe()
  val Nothing = StandardNames.FqNames.nothing.toSafe()
  val Function = StandardNames.FqNames.functionSupertype.toSafe()
}
