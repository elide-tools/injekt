/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.injekt.compiler

import com.ivianuu.injekt.compiler.resolution.CallableRef
import com.ivianuu.injekt.compiler.resolution.ClassifierRef
import com.ivianuu.injekt.compiler.resolution.DescriptorWithParentScope
import com.ivianuu.injekt.compiler.resolution.InjectablesScope
import com.ivianuu.injekt.compiler.resolution.InjectablesWithLookups
import com.ivianuu.injekt.compiler.resolution.InjectionResult
import com.ivianuu.injekt.compiler.resolution.TypeRef
import com.ivianuu.injekt.compiler.resolution.TypeRefKey
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.util.slicedMap.BasicWritableSlice
import org.jetbrains.kotlin.util.slicedMap.RewritePolicy

object InjektWritableSlices {
  val INJEKT_CONTEXT = BasicWritableSlice<Unit, Context>(RewritePolicy.DO_NOTHING)
  val INJECTION_RESULT = BasicWritableSlice<SourcePosition, InjectionResult.Success>(RewritePolicy.DO_NOTHING)
  val INJECTIONS_OCCURRED_IN_FILE = BasicWritableSlice<String, Unit>(RewritePolicy.DO_NOTHING)
  val INJECTABLE_CONSTRUCTORS = BasicWritableSlice<ClassDescriptor, List<CallableRef>>(RewritePolicy.DO_NOTHING)
  val IS_PROVIDE = BasicWritableSlice<Any, Boolean>(RewritePolicy.DO_NOTHING)
  val IS_INJECT = BasicWritableSlice<Any, Boolean>(RewritePolicy.DO_NOTHING)
  val BLOCK_SCOPE = BasicWritableSlice<Pair<KtBlockExpression, DeclarationDescriptor>, InjectablesScope>(RewritePolicy.DO_NOTHING)
  val CLASSIFIER_REF = BasicWritableSlice<ClassifierDescriptor, ClassifierRef>(RewritePolicy.DO_NOTHING)
  val CALLABLE_REF = BasicWritableSlice<CallableDescriptor, CallableRef>(RewritePolicy.DO_NOTHING)
  val CALLABLE_INFO = BasicWritableSlice<CallableDescriptor, CallableInfo>(RewritePolicy.DO_NOTHING)
  val CLASSIFIER_INFO = BasicWritableSlice<ClassifierDescriptor, ClassifierInfo>(RewritePolicy.DO_NOTHING)
  val ELEMENT_SCOPE = BasicWritableSlice<KtElement, InjectablesScope>(RewritePolicy.DO_NOTHING)
  val DECLARATION_SCOPE = BasicWritableSlice<DescriptorWithParentScope, InjectablesScope>(RewritePolicy.DO_NOTHING)
  val TYPE_SCOPE_INJECTABLES = BasicWritableSlice<TypeRefKey, InjectablesWithLookups>(RewritePolicy.DO_NOTHING)
  val TYPE_INJECTABLES = BasicWritableSlice<Pair<TypeRef, Boolean>, List<CallableRef>>(RewritePolicy.DO_NOTHING)
  val PACKAGE_INJECTABLES = BasicWritableSlice<FqName, List<CallableRef>>(RewritePolicy.DO_NOTHING)
  val PACKAGE_TYPE_SCOPE_INJECTABLES = BasicWritableSlice<FqName, List<CallableRef>>(RewritePolicy.DO_NOTHING)
  val CLASSIFIER_FOR_KEY = BasicWritableSlice<String, ClassifierDescriptor>(RewritePolicy.DO_NOTHING)
  val UNIQUE_KEY = BasicWritableSlice<DeclarationDescriptor, String>(RewritePolicy.DO_NOTHING)
}

data class SourcePosition(val filePath: String, val startOffset: Int, val endOffset: Int)
