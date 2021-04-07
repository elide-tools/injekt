/*
 * Copyright 2020 Manuel Wrage
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

package com.ivianuu.injekt.compiler.resolution

import com.ivianuu.injekt.compiler.InjektContext
import com.ivianuu.injekt.compiler.InjektFqNames
import com.ivianuu.injekt.compiler.InjektWritableSlices
import com.ivianuu.injekt.compiler.asNameId
import com.ivianuu.injekt.compiler.forEachWith
import com.ivianuu.injekt.compiler.getAnnotatedAnnotations
import com.ivianuu.injekt.compiler.hasAnnotation
import com.ivianuu.injekt.compiler.isExternalDeclaration
import com.ivianuu.injekt.compiler.isForTypeKey
import com.ivianuu.injekt.compiler.isGivenConstraint
import com.ivianuu.injekt.compiler.isOptimizableModule
import com.ivianuu.injekt.compiler.toMap
import com.ivianuu.injekt.compiler.toTypeRef
import com.ivianuu.injekt.compiler.unsafeLazy
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.ClassifierDescriptorWithTypeParameters
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.isPropertyParameter
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.getAbbreviation
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

data class ClassifierRef(
    val fqName: FqName,
    val typeParameters: List<ClassifierRef> = emptyList(),
    val superTypes: List<TypeRef> = emptyList(),
    val isTypeParameter: Boolean = false,
    val isObject: Boolean = false,
    val isTypeAlias: Boolean = false,
    val descriptor: ClassifierDescriptor? = null,
    val qualifiers: List<TypeRef> = emptyList(),
    val isGivenConstraint: Boolean = false,
    val isForTypeKey: Boolean = false,
    val primaryConstructorPropertyParameters: List<Name> = emptyList()
) {
    override fun equals(other: Any?): Boolean = (other is ClassifierRef) && fqName == other.fqName
    override fun hashCode(): Int = fqName.hashCode()

    val defaultType: TypeRef by unsafeLazy {
        SimpleTypeRef(
            this,
            arguments = typeParameters.map { it.defaultType },
            qualifiers = qualifiers
        )
    }
}

val ClassifierRef.givenConstraintTypeParameters: List<Name>
    get() = typeParameters
        .asSequence()
        .filter { it.isGivenConstraint }
        .map { it.fqName.shortName() }
        .toList()

val ClassifierRef.forTypeKeyTypeParameters: List<Name>
    get() = typeParameters
        .asSequence()
        .filter { it.isForTypeKey }
        .map { it.fqName.shortName() }
        .toList()

fun ClassifierDescriptor.toClassifierRef(
    context: InjektContext,
    trace: BindingTrace?
): ClassifierRef {
    trace?.get(InjektWritableSlices.CLASSIFIER_REF_FOR_CLASSIFIER, this)?.let { return it }
    val info = if (original.isExternalDeclaration()) context.classifierInfoFor(this, trace)
    else null
    val expandedType = if (info == null) (original as? TypeAliasDescriptor)?.underlyingType
        ?.toTypeRef(context, trace) else null
    val qualifiers = info?.qualifiers?.map { it.toTypeRef(context, trace) }
        ?: getAnnotatedAnnotations(InjektFqNames.Qualifier)
            .map { it.type.toTypeRef(context, trace) }
            .sortedQualifiers()

    return ClassifierRef(
        fqName = original.fqNameSafe,
        typeParameters = (original as? ClassifierDescriptorWithTypeParameters)?.declaredTypeParameters
            ?.map { it.toClassifierRef(context, trace) } ?: emptyList(),
        superTypes = when {
            expandedType != null -> listOf(expandedType)
            info != null -> info.superTypes.map { it.toTypeRef(context, trace) }
            else -> typeConstructor.supertypes.map { it.toTypeRef(context, trace) }
        },
        isTypeParameter = this is TypeParameterDescriptor,
        isObject = this is ClassDescriptor && kind == ClassKind.OBJECT,
        isTypeAlias = this is TypeAliasDescriptor,
        descriptor = this,
        qualifiers = qualifiers,
        isGivenConstraint = this is TypeParameterDescriptor && isGivenConstraint(context, trace),
        isForTypeKey = this is TypeParameterDescriptor && isForTypeKey(context, trace),
        primaryConstructorPropertyParameters = info
            ?.primaryConstructorPropertyParameters
            ?.map { it.asNameId() } ?: this
            .safeAs<ClassDescriptor>()
            ?.unsubstitutedPrimaryConstructor
            ?.valueParameters
            ?.asSequence()
            ?.filter { it.findPsi()?.safeAs<KtParameter>()?.isPropertyParameter() == true }
            ?.map { it.name }
            ?.toList()
            ?: emptyList()
    ).also {
        trace?.record(InjektWritableSlices.CLASSIFIER_REF_FOR_CLASSIFIER, this, it)
    }
}

fun KotlinType.toTypeRef(
    context: InjektContext,
    trace: BindingTrace?,
    isStarProjection: Boolean = false
): TypeRef = if (isStarProjection) STAR_PROJECTION_TYPE else {
    val key = System.identityHashCode(this)
    trace?.get(InjektWritableSlices.TYPE_REF_FOR_TYPE, key)?.let { return it }
    KotlinTypeRef(this, isStarProjection, context, trace)
        .also { trace?.record(InjektWritableSlices.TYPE_REF_FOR_TYPE, key, it) }
}

sealed class TypeRef {
    abstract val classifier: ClassifierRef
    abstract val isMarkedNullable: Boolean
    abstract val arguments: List<TypeRef>
    abstract val isMarkedComposable: Boolean
    abstract val isGiven: Boolean
    abstract val isStarProjection: Boolean
    abstract val qualifiers: List<TypeRef>
    abstract val frameworkKey: String?

    private val typeName by unsafeLazy { uniqueTypeName() }

    override fun toString(): String = typeName

    override fun equals(other: Any?) =
        other is TypeRef && other.typeName == typeName

    private val _hashCode by unsafeLazy {
        var result = classifier.hashCode()
        // todo result = 31 * result + isMarkedNullable.hashCode()
        result = 31 * result + arguments.hashCode()
        // todo result result = 31 * result + variance.hashCode()
        result = 31 * result + isMarkedComposable.hashCode()
        result = 31 * result + isStarProjection.hashCode()
        result = 31 * result + qualifiers.hashCode()
        result = 31 * result + frameworkKey.hashCode()
        result
    }

    override fun hashCode(): Int = _hashCode

    val typeSize: Int by unsafeLazy {
        var typeSize = 0
        val seen = mutableSetOf<TypeRef>()
        fun visit(type: TypeRef) {
            typeSize++
            if (type in seen) return
            seen += type
            type.qualifiers.forEach { visit(it) }
            type.arguments.forEach { visit(it) }
        }
        visit(this)
        typeSize
    }

    val coveringSet: Set<ClassifierRef> by unsafeLazy {
        val classifiers = mutableSetOf<ClassifierRef>()
        val seen = mutableSetOf<TypeRef>()
        fun visit(type: TypeRef) {
            if (type in seen) return
            seen += type
            classifiers += type.classifier
            type.qualifiers.forEach { visit(it) }
            type.arguments.forEach { visit(it) }
        }
        visit(this)
        classifiers
    }

    val isNullableType: Boolean by unsafeLazy {
        if (isMarkedNullable) return@unsafeLazy true
        for (superType in superTypes)
            if (superType.isNullableType) return@unsafeLazy true
        return@unsafeLazy false
    }

    val isComposableType: Boolean by unsafeLazy {
        if (isMarkedComposable) return@unsafeLazy true
        for (superType in superTypes)
            if (superType.isComposableType) return@unsafeLazy true
        return@unsafeLazy false
    }

    val superTypes: List<TypeRef> by unsafeLazy {
        val substitutionMap = classifier.typeParameters
            .toMap(arguments)
        classifier.superTypes
            .map { superType ->
                superType.substitute(substitutionMap)
                    .let {
                        if (qualifiers.isNotEmpty()) it.copy(
                            qualifiers = (qualifiers + it.qualifiers)
                                .distinctBy { it.classifier.fqName.asString() }
                                .sortedQualifiers()
                        )
                        else it
                    }
            }
    }
}

fun TypeRef.forEachType(action: (TypeRef) -> Unit) {
    action(this)
    arguments.forEach { it.forEachType(action) }
    qualifiers.forEach { it.forEachType(action) }
}

fun TypeRef.anyType(action: (TypeRef) -> Boolean): Boolean =
    action(this) || arguments.any { it.anyType(action) } || qualifiers.any { it.anyType(action) }

class KotlinTypeRef(
    private val kotlinType: KotlinType,
    override val isStarProjection: Boolean = false,
    val context: InjektContext,
    val trace: BindingTrace?
) : TypeRef() {
    override val classifier: ClassifierRef by unsafeLazy {
        (kotlinType.getAbbreviation() ?: kotlinType)
            .constructor.declarationDescriptor!!.toClassifierRef(context, trace)
    }
    override val isMarkedComposable: Boolean by unsafeLazy {
        (kotlinType.getAbbreviation() ?: kotlinType)
            .hasAnnotation(InjektFqNames.Composable)
    }
    override val isGiven: Boolean
        get() = kotlinType.isGiven(context, trace)
    override val isMarkedNullable: Boolean get() = kotlinType.isMarkedNullable
    override val arguments: List<TypeRef> by unsafeLazy {
        (kotlinType.getAbbreviation() ?: kotlinType).arguments
            // we use the take here because an inner class also contains the type parameters
            // of it's parent class which is irrelevant for us
            .asSequence()
            .take(classifier.typeParameters.size)
            .map { it.type.toTypeRef(context, trace, it.isStarProjection) }
            .toList()
    }
    override val qualifiers: List<TypeRef> by unsafeLazy {
        kotlinType.getAnnotatedAnnotations(InjektFqNames.Qualifier)
            .map { it.type.toTypeRef(context, trace) }
            .sortedQualifiers()
    }
    override val frameworkKey: String?
        get() = null
}

class SimpleTypeRef(
    override val classifier: ClassifierRef,
    override val isMarkedNullable: Boolean = false,
    override val arguments: List<TypeRef> = emptyList(),
    override val isMarkedComposable: Boolean = false,
    override val isGiven: Boolean = false,
    override val isStarProjection: Boolean = false,
    override val qualifiers: List<TypeRef> = emptyList(),
    override val frameworkKey: String? = null
) : TypeRef() {
    init {
        check(arguments.size == classifier.typeParameters.size) {
            "Argument size mismatch ${classifier.fqName} " +
                    "params: ${classifier.typeParameters.map { it.fqName }} " +
                    "args: ${arguments.map { it.render() }}"
        }
        check(qualifiers == qualifiers.sortedQualifiers()) {
            "Qualifiers must be sorted"
        }
    }
}

fun TypeRef.typeWith(arguments: List<TypeRef>): TypeRef = copy(arguments = arguments)

fun TypeRef.copy(
    classifier: ClassifierRef = this.classifier,
    isMarkedNullable: Boolean = this.isMarkedNullable,
    arguments: List<TypeRef> = this.arguments,
    isMarkedComposable: Boolean = this.isMarkedComposable,
    isGiven: Boolean = this.isGiven,
    isStarProjection: Boolean = this.isStarProjection,
    qualifiers: List<TypeRef> = this.qualifiers,
    frameworkKey: String? = this.frameworkKey
): SimpleTypeRef = SimpleTypeRef(
    classifier,
    isMarkedNullable,
    arguments,
    isMarkedComposable,
    isGiven,
    isStarProjection,
    qualifiers,
    frameworkKey
)

fun TypeRef.substitute(map: Map<ClassifierRef, TypeRef>): TypeRef {
    if (map.isEmpty()) return this
    map[classifier]?.let { substitution ->
        val newQualifiers = ((qualifiers
            .map { it.substitute(map) } + substitution.qualifiers)
            .distinctBy { it.classifier })
            .sortedQualifiers()
        val newNullability = if (!isStarProjection) isMarkedNullable else substitution.isMarkedNullable
        val newGiven = isGiven || substitution.isGiven
        return if (newQualifiers != substitution.qualifiers ||
                newNullability != substitution.isMarkedNullable ||
                newGiven != substitution.isGiven) {
            substitution.copy(
                // we copy nullability to support T : Any? -> String
                isMarkedNullable = newNullability,
                // we prefer the existing qualifier to support @MyQualifier T -> @MyQualifier String
                // but we fallback to the substitution qualifier to also support T -> @MyQualifier String
                // in case of an overlap we merge the original qualifiers with substitution qualifiers
                qualifiers = newQualifiers,
                // we copy given kind to support @Given C -> @Given String
                // fallback to substitution given
                isGiven = newGiven
            )
        } else substitution
    }

    if (arguments.isEmpty() && qualifiers.isEmpty() && !classifier.isTypeParameter) return this

    val substituted = copy(
        arguments = arguments.map { it.substitute(map) },
        qualifiers = qualifiers.map { it.substitute(map) }
    )

    if (classifier.isTypeParameter && substituted == this) {
        classifier
            .superTypes
            .firstNotNullResult {
                val substitutedSuperType = it.substitute(map)
                if (substitutedSuperType != it) substitutedSuperType
                else null
            }
            ?.let { return it }
    }

    return substituted
}

val STAR_PROJECTION_TYPE = SimpleTypeRef(
    classifier = ClassifierRef(StandardNames.FqNames.any.toSafe()),
    isStarProjection = true
)

fun TypeRef.render(depth: Int = 0): String {
    if (depth > 15) return ""
    return buildString {
        fun TypeRef.inner() {
            val annotations = qualifiers.map { "@${it.render()}" } + listOfNotNull(
                if (isGiven) "@Given" else null,
                if (isMarkedComposable) "@Composable" else null,
            )

            if (annotations.isNotEmpty()) {
                annotations.forEach { annotation ->
                    append(annotation)
                    append(" ")
                }
            }
            when {
                classifier.isTypeParameter -> append(classifier.fqName.shortName())
                isStarProjection -> append("*")
                else -> append(classifier.fqName)
            }
            if (arguments.isNotEmpty()) {
                append("<")
                arguments.forEachIndexed { index, typeArgument ->
                    append(typeArgument.render(depth = depth + 1))
                    if (index != arguments.lastIndex) append(", ")
                }
                append(">")
            }
            if (isMarkedNullable && !isStarProjection) append("?")
            frameworkKey?.let { append("[$it]") }
        }
        inner()
    }
}

fun TypeRef.uniqueTypeName(depth: Int = 0): String {
    if (depth > 15) return ""
    return buildString {
        qualifiers.forEach {
            append(it.uniqueTypeName())
            append("_")
        }
        if (isMarkedComposable) append("composable_")
        if (isStarProjection) append("star")
        else append(classifier.fqName.pathSegments().joinToString("_") { it.asString() })
        if (frameworkKey != null) {
            append("_")
            append(frameworkKey)
        }
        arguments.forEachIndexed { index, typeArgument ->
            if (index == 0) append("_")
            append(typeArgument.uniqueTypeName(depth + 1))
            if (index != arguments.lastIndex) append("_/_")
        }
    }
}

fun KotlinType.uniqueTypeName(depth: Int = 0): String {
    if (depth > 15) return ""
    return buildString {
        append(constructor.declarationDescriptor!!.fqNameSafe.pathSegments().joinToString("_") { it.asString() })
        arguments.forEachIndexed { index, typeArgument ->
            if (index == 0) append("_")
            append(typeArgument.type.uniqueTypeName(depth + 1))
            if (index != arguments.lastIndex) append("_/_")
        }
    }
}

fun getSubstitutionMap(
    context: InjektContext,
    pairs: List<Pair<TypeRef, TypeRef>>
): Map<ClassifierRef, TypeRef> {
    if (pairs.isEmpty()) return emptyMap()
    if (pairs.all { it.first == it.second }) return emptyMap()
    val substitutionMap = mutableMapOf<ClassifierRef, TypeRef>()

    fun visitType(
        thisType: TypeRef,
        baseType: TypeRef,
        fromInput: Boolean
    ) {
        if (thisType == baseType) return

        if (baseType.classifier.isTypeParameter &&
            (baseType.classifier !in substitutionMap || fromInput)) {
            val finalSubstitutionType = if ((thisType.qualifiers.isEmpty() &&
                        baseType.qualifiers.isEmpty()) ||
                (baseType.qualifiers.isEmpty() && thisType.qualifiers.isNotEmpty())) thisType
            else thisType.copy(qualifiers = thisType.qualifiers
                .filter { thisQualifier ->
                    baseType.qualifiers.none {
                        it.classifier == thisQualifier.classifier
                    }
                })
            substitutionMap[baseType.classifier] = finalSubstitutionType
        }

        if (thisType.classifier == baseType.classifier) {
            thisType.arguments.forEachWith(baseType.arguments) { a, b -> visitType(a, b, fromInput) }
        } else if (!baseType.classifier.isTypeParameter) {
            val subType = thisType.subtypeView(baseType.classifier)
            if (subType != null) {
                subType.arguments.forEachWith(baseType.arguments) { a, b -> visitType(a, b, false) }
                if (subType.qualifiers.isNotEmpty() &&
                    baseType.qualifiers.isNotEmpty() &&
                    subType.qualifiers.areSubQualifiersOf(context, baseType.qualifiers)) {
                    for (baseQualifier in baseType.qualifiers) {
                        val subTypeQualifier = subType.qualifiers.first {
                            it.classifier == baseQualifier.classifier
                        }
                        visitType(subTypeQualifier, baseQualifier, false)
                    }
                }
            }
        }

        if (thisType.qualifiers.isNotEmpty() &&
            baseType.qualifiers.isNotEmpty() &&
            thisType.qualifiers.areSubQualifiersOf(context, baseType.qualifiers)) {
            for (baseQualifier in baseType.qualifiers) {
                val thisTypeQualifier = thisType.qualifiers.first {
                    it.classifier == baseQualifier.classifier
                }
                visitType(thisTypeQualifier, baseQualifier, false)
            }
        }

        baseType.superTypes.forEach { visitType(thisType, it, false) }
    }

    pairs.forEach { visitType(it.first, it.second, true) }

    return substitutionMap
}

fun TypeRef.isAssignableTo(
    context: InjektContext,
    superType: TypeRef
): Boolean {
    if (isStarProjection || superType.isStarProjection) return true
    if (superType.classifier.isTypeParameter)
        return isSubTypeOfTypeParameter(context, superType)
    if (classifier.isTypeParameter)
        return superType.isSubTypeOfTypeParameter(context, this)
    if (!qualifiers.areQualifiersAssignable(context, superType.qualifiers))
        return false
    return isSubTypeOf(context, superType)
}

private fun TypeRef.isSubTypeOfTypeParameter(
    context: InjektContext,
    typeParameter: TypeRef
): Boolean {
    if (typeParameter.qualifiers.isNotEmpty() &&
        (qualifiers.isEmpty() || !qualifiers.areQualifiersAssignable(context, typeParameter.qualifiers))
    ) return false
    return typeParameter.superTypes.all { upperBound ->
        isSubTypeOf(context, upperBound)
    }
}

private fun TypeRef.isSubTypeOfSameClassifier(
    context: InjektContext,
    superType: TypeRef
): Boolean {
    if (this == superType) return true
    if (!qualifiers.areSubQualifiersOf(context, superType.qualifiers))
        return false
    if (isMarkedComposable != superType.isMarkedComposable) return false
    arguments.forEachWith(superType.arguments) { a, b ->
        if (!a.isAssignableTo(context, b))
            return false
    }
    return true
}

fun TypeRef.isSubTypeOf(
    context: InjektContext,
    superType: TypeRef
): Boolean {
    if (isStarProjection) return true
    if (isNullableType && !superType.isNullableType) return false
    if (superType.classifier.fqName == InjektFqNames.Any)
        return superType.qualifiers.isEmpty() ||
                (qualifiers.isNotEmpty() && qualifiers.areSubQualifiersOf(context, superType.qualifiers))
    if (classifier == superType.classifier)
        return isSubTypeOfSameClassifier(context, superType)

    if (superType.classifier.isTypeParameter) {
        if (superType.qualifiers.isNotEmpty() &&
            (qualifiers.isEmpty() || !qualifiers.areSubQualifiersOf(context, superType.qualifiers))
        ) return false
        return superType.superTypes.all { isSubTypeOf(context, it) }
    }

    val subTypeView = subtypeView(superType.classifier)
    if (subTypeView != null)
        return subTypeView.isSubTypeOfSameClassifier(context, superType)

    return false
}

fun TypeRef.subtypeView(classifier: ClassifierRef): TypeRef? {
    if (this.classifier == classifier) return this
    return superTypes
        .firstNotNullResult { it.subtypeView(classifier) }
        ?.let { return it }
}

fun List<TypeRef>.areQualifiersAssignable(
    context: InjektContext,
    superQualifiers: List<TypeRef>,
): Boolean {
    if (size != superQualifiers.size) return false
    forEachWith(superQualifiers) { thisQualifier, superQualifier ->
        if (!thisQualifier.isSubTypeOf(context, superQualifier))
            return false
    }
    return true
}

fun List<TypeRef>.areSubQualifiersOf(
    context: InjektContext,
    superQualifiers: List<TypeRef>,
): Boolean {
    for (superQualifier in superQualifiers) {
        val thisQualifier = firstOrNull { it.classifier == superQualifier.classifier }
        if (thisQualifier?.isSubTypeOf(context, superQualifier) != true)
            return false
    }
    return true
}

val TypeRef.isFunctionType: Boolean get() =
    classifier.fqName.asString().startsWith("kotlin.Function") ||
            classifier.fqName.asString().startsWith("kotlin.coroutines.SuspendFunction")

val TypeRef.isSuspendFunctionType: Boolean get() =
    classifier.fqName.asString().startsWith("kotlin.coroutines.SuspendFunction")

val TypeRef.fullyExpandedType: TypeRef
    get() = if (classifier.isTypeAlias) superTypes.single().fullyExpandedType else this

val TypeRef.isFunctionTypeWithOnlyGivenParameters: Boolean
    get() {
        if (!isFunctionType) return false
        for (i in arguments.indices) {
            if (i < arguments.lastIndex && !arguments[i].isGiven)
                return false
        }

        return true
    }

fun List<TypeRef>.sortedQualifiers(): List<TypeRef> =
    sortedBy { it.classifier.fqName.asString() }
