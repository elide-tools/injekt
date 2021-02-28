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

import com.ivianuu.injekt.compiler.*
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.ClassifierDescriptorWithTypeParameters
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.getAbbreviatedType
import org.jetbrains.kotlin.types.getAbbreviation
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult

data class ClassifierRef(
    val fqName: FqName,
    val typeParameters: List<ClassifierRef> = emptyList(),
    val superTypes: List<TypeRef> = emptyList(),
    val expandedType: TypeRef? = null,
    val isTypeParameter: Boolean = false,
    val isObject: Boolean = false,
    val isTypeAlias: Boolean = false,
    val descriptor: ClassifierDescriptor? = null,
    val qualifiers: List<AnnotationRef> = emptyList()
) {
    override fun equals(other: Any?): Boolean = (other is ClassifierRef) && fqName == other.fqName
    override fun hashCode(): Int = fqName.hashCode()
}

val TypeRef.expandedType: TypeRef?
    get() {
        return classifier.expandedType?.let {
            val substitutionMap = classifier.typeParameters
                .zip(arguments)
                .toMap()
            it.substitute(substitutionMap)
                .copy(isMarkedNullable = isMarkedNullable)
        }
    }

val ClassifierRef.defaultType: TypeRef
    get() = SimpleTypeRef(
        this,
        arguments = typeParameters.map { it.defaultType },
        qualifiers = qualifiers
    )

fun TypeRef.superTypes(substitutionMap: Map<ClassifierRef, TypeRef> = emptyMap()): List<TypeRef> {
    val merged = classifier.typeParameters
        .zip(arguments)
        .toMap() + substitutionMap
    return classifier.superTypes
        .map { it.substitute(merged) }
}

fun KotlinType.toTypeRef(
    declarationStore: DeclarationStore,
    variance: Variance = Variance.INVARIANT,
    isStarProjection: Boolean = false
): TypeRef = KotlinTypeRef(this, variance, isStarProjection, declarationStore)

fun ClassifierDescriptor.toClassifierRef(
    declarationStore: DeclarationStore,
    applyClassifierInfo: Boolean = true
): ClassifierRef {
    val expandedType = (original as? TypeAliasDescriptor)?.underlyingType
        ?.toTypeRef(declarationStore)
    val qualifiers = getAnnotatedAnnotations(InjektFqNames.Qualifier)
        .map { it.toAnnotationRef(declarationStore) }
    return ClassifierRef(
        fqName = original.fqNameSafe,
        typeParameters = (original as? ClassifierDescriptorWithTypeParameters)?.declaredTypeParameters
            ?.map { it.toClassifierRef(declarationStore) } ?: emptyList(),
        superTypes = if (expandedType != null) listOf(expandedType) else typeConstructor.supertypes
            .map { it.toTypeRef(declarationStore) },
        expandedType = expandedType,
        isTypeParameter = this is TypeParameterDescriptor,
        isObject = this is ClassDescriptor && kind == ClassKind.OBJECT,
        isTypeAlias = this is TypeAliasDescriptor,
        descriptor = this,
        qualifiers = qualifiers
    ).let {
        if (applyClassifierInfo && original.isExternalDeclaration()) it.apply(
            declarationStore,
            declarationStore.classifierInfoFor(it)
        ) else it
    }
}

sealed class TypeRef {
    abstract val classifier: ClassifierRef
    abstract val isMarkedNullable: Boolean
    abstract val arguments: List<TypeRef>
    abstract val variance: Variance
    abstract val isComposable: Boolean
    abstract val contributionKind: ContributionKind?
    abstract val isStarProjection: Boolean
    abstract val qualifiers: List<AnnotationRef>
    abstract val macroChain: List<FqName>
    abstract val setKey: SetKey?

    private val typeName by unsafeLazy { uniqueTypeName() }

    override fun toString(): String = typeName

    override fun equals(other: Any?) =
        other is TypeRef && other.typeName == typeName

    private val _hashCode by unsafeLazy {
        var result = classifier.hashCode()
        // todo result = 31 * result + isMarkedNullable.hashCode()
        result = 31 * result + arguments.hashCode()
        // todo result result = 31 * result + variance.hashCode()
        result = 31 * result + isComposable.hashCode()
        result = 31 * result + isStarProjection.hashCode()
        result = 31 * result + qualifiers.hashCode()
        result = 31 * result + macroChain.hashCode()
        result = 31 * result + setKey.hashCode()

        result
    }

    override fun hashCode(): Int = _hashCode

    val thisAndAllSuperTypes: Set<TypeRef> by unsafeLazy {
        val types = mutableSetOf<TypeRef>()
        fun visit(type: TypeRef) {
            if (type in types) return
            types += type
            type.expandedType?.let { visit(it) }
            type.superTypes().forEach { visit(it) }
        }
        visit(this)
        types
    }

    val typeSize: Int by unsafeLazy {
        var typeSize = 0
        val seen = mutableSetOf<TypeRef>()
        fun visit(type: TypeRef) {
            typeSize++
            if (type in seen) return
            seen += type
            type.qualifiers.forEach { visit(it.type) }
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
            type.qualifiers.forEach { visit(it.type) }
            type.arguments.forEach { visit(it) }
        }
        visit(this)
        classifiers
    }
}

class KotlinTypeRef(
    val kotlinType: KotlinType,
    override val variance: Variance = Variance.INVARIANT,
    override val isStarProjection: Boolean = false,
    val declarationStore: DeclarationStore
) : TypeRef() {
    private val finalType by unsafeLazy { kotlinType.getAbbreviation() ?: kotlinType.prepare() }
    override val classifier: ClassifierRef by unsafeLazy {
        finalType.constructor.declarationDescriptor!!.toClassifierRef(declarationStore)
    }
    override val isComposable: Boolean by unsafeLazy {
        kotlinType.hasAnnotation(InjektFqNames.Composable) &&
                kotlinType.getAbbreviatedType()?.expandedType?.hasAnnotation(InjektFqNames.Composable) != true
    }
    override val contributionKind: ContributionKind?
        get() = finalType.contributionKind(declarationStore)
            ?: kotlinType.contributionKind(declarationStore)
    override val isMarkedNullable: Boolean by unsafeLazy {
        kotlinType.isMarkedNullable
    }
    override val arguments: List<TypeRef> by unsafeLazy {
        finalType.arguments
            .take(classifier.typeParameters.size)
            .map { it.type.toTypeRef(declarationStore, it.projectionKind, it.isStarProjection) }
    }
    override val qualifiers: List<AnnotationRef> by unsafeLazy {
        kotlinType.getAnnotatedAnnotations(InjektFqNames.Qualifier)
            .map { it.toAnnotationRef(declarationStore) }
    }
    override val macroChain: List<FqName> get() = emptyList()
    override val setKey: SetKey? get() = null
}

data class SetKey(
    val type: TypeRef,
    val callable: CallableRef
)

class SimpleTypeRef(
    override val classifier: ClassifierRef,
    override val isMarkedNullable: Boolean = false,
    override val arguments: List<TypeRef> = emptyList(),
    override val variance: Variance = Variance.INVARIANT,
    override val isComposable: Boolean = false,
    override val contributionKind: ContributionKind? = null,
    override val isStarProjection: Boolean = false,
    override val qualifiers: List<AnnotationRef> = emptyList(),
    override val macroChain: List<FqName> = emptyList(),
    override val setKey: SetKey? = null
) : TypeRef() {
    init {
        check(arguments.size == classifier.typeParameters.size) {
            "Argument size mismatch ${classifier.fqName} " +
                    "params: ${classifier.typeParameters.map { it.fqName }} " +
                    "args: ${arguments.map { it.render() }}"
        }
    }
}

fun TypeRef.typeWith(arguments: List<TypeRef>): TypeRef = copy(arguments = arguments)

fun TypeRef.copy(
    classifier: ClassifierRef = this.classifier,
    isMarkedNullable: Boolean = this.isMarkedNullable,
    arguments: List<TypeRef> = this.arguments,
    variance: Variance = this.variance,
    isComposable: Boolean = this.isComposable,
    contributionKind: ContributionKind? = this.contributionKind,
    isStarProjection: Boolean = this.isStarProjection,
    qualifiers: List<AnnotationRef> = this.qualifiers,
    macroChain: List<FqName> = this.macroChain,
    setKey: SetKey? = this.setKey
) = SimpleTypeRef(
    classifier,
    isMarkedNullable,
    arguments,
    variance,
    isComposable,
    contributionKind,
    isStarProjection,
    qualifiers,
    macroChain,
    setKey
)

fun TypeRef.substitute(map: Map<ClassifierRef, TypeRef>): TypeRef {
    if (map.isEmpty()) return this
    map[classifier]?.let {
        return it.copy(
            // we copy nullability to support T : Any? -> String
            isMarkedNullable = if (!isStarProjection) isMarkedNullable else it.isMarkedNullable,
            // we copy qualifiers to support @MyQualifier T -> @MyQualifier String
            qualifiers = qualifiers + it.qualifiers,
            // we copy given kind to support @Given C -> @Given String
            // fallback to substitution given kind
            contributionKind = contributionKind ?: it.contributionKind
        )
    }

    if (arguments.isEmpty() && qualifiers.isEmpty() &&
        !classifier.isTypeParameter
    ) return this

    val substituted = copy(arguments = arguments.map { it.substitute(map) })

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
            val annotations = qualifiers.map {
                "@${it.type.render()}(${
                    it.arguments.toList().joinToString { "${it.first}=${it.second}" }
                })"
            } + listOfNotNull(
                if (isComposable) "@${InjektFqNames.Composable}" else null,
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
                    if (typeArgument.variance != Variance.INVARIANT &&
                        !typeArgument.isStarProjection
                    )
                        append("${typeArgument.variance.label} ")
                    append(typeArgument.render(depth = depth + 1))
                    if (index != arguments.lastIndex) append(", ")
                }
                append(">")
            }
            if (isMarkedNullable && !isStarProjection) append("?")
        }
        inner()
    }
}

fun TypeRef.uniqueTypeName(depth: Int = 0): String {
    if (depth > 15) return ""
    return buildString {
        qualifiers.forEach {
            append(it.type.classifier.fqName)
            append(it.arguments.hashCode())
            append("_")
        }
        if (isComposable) append("composable_")
        // if (includeNullability && isMarkedNullable) append("nullable_")
        if (isStarProjection) append("star")
        else append(classifier.fqName.pathSegments().joinToString("_") { it.asString() })
        if (macroChain.isNotEmpty()) {
            append(macroChain.joinToString("_", postfix = "_"))
        }
        if (setKey != null) append("${setKey.hashCode()}_")
        arguments.forEachIndexed { index, typeArgument ->
            if (index == 0) append("_")
            append(typeArgument.uniqueTypeName(depth + 1))
            if (index != arguments.lastIndex) append("_")
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
            if (index != arguments.lastIndex) append("_")
        }
    }
}

fun getSubstitutionMap(
    declarationStore: DeclarationStore,
    pairs: List<Pair<TypeRef, TypeRef>>
): Map<ClassifierRef, TypeRef> {
    val substitutionMap = mutableMapOf<ClassifierRef, TypeRef>()
    val visitedTypes = mutableSetOf<TypeRef>()
    fun visitType(thisType: TypeRef, baseType: TypeRef) {
        if (thisType == baseType) return

        if (thisType in visitedTypes && baseType in visitedTypes) {
            return
        }
        visitedTypes += thisType
        visitedTypes += baseType
        if (!baseType.classifier.isTypeParameter) {
            thisType.arguments.zip(baseType.arguments)
                .forEach { visitType(it.first, it.second) }
            return
        }

        if (thisType.qualifiers.isNotEmpty() &&
            thisType.qualifiers.size == baseType.qualifiers.size &&
            thisType.qualifiers.zip(baseType.qualifiers).all { (a, b) ->
                a.type.classifier == b.type.classifier &&
                        a.arguments == b.arguments
            }) {
            visitType(thisType.copy(qualifiers = emptyList()), baseType.copy(qualifiers = emptyList()))
            thisType.qualifiers.zip(baseType.qualifiers)
                .forEach { visitType(it.first.type, it.second.type) }
            return
        }

        if (baseType.classifier !in substitutionMap) {
            substitutionMap[baseType.classifier] = thisType
        }
    }
    pairs.forEach { visitType(it.first, it.second) }

    substitutionMap.forEach { (baseClassifier, thisType) ->
        baseClassifier.defaultType.superTypes()
            .map { thisType.subtypeView(it.classifier, substitutionMap) to it }
            .forEach { (thisBaseTypeView, baseSuperType) ->
                if (baseSuperType.classifier.isTypeParameter) {
                    val thisTypeToUse = thisBaseTypeView ?: thisType
                    visitType(thisTypeToUse
                        .copy(qualifiers = emptyList(), macroChain = emptyList(), setKey = null), baseSuperType)
                    if (thisTypeToUse.qualifiers.isAssignableTo(declarationStore, baseSuperType.qualifiers)) {
                        thisTypeToUse.qualifiers.zip(baseSuperType.qualifiers)
                            .forEach { visitType(it.first.type, it.second.type) }
                    }
                } else {
                    visitType(thisBaseTypeView ?: thisType, baseSuperType)
                }

                thisBaseTypeView?.arguments?.zip(baseSuperType.arguments)
                    ?.forEach { visitType(it.first, it.second) }

                if (thisType.qualifiers.isAssignableTo(declarationStore, baseSuperType.qualifiers)) {
                    thisType.qualifiers.zip(baseSuperType.qualifiers)
                        .forEach { visitType(it.first.type, it.second.type) }
                }
                if (thisBaseTypeView?.qualifiers?.isAssignableTo(declarationStore, baseSuperType.qualifiers) == true) {
                    thisBaseTypeView.qualifiers.zip(baseSuperType.qualifiers)
                        .forEach { visitType(it.first.type, it.second.type) }
                }
            }
    }

    substitutionMap.forEach { visitType(it.value, it.key.defaultType) }
    return substitutionMap
}

fun TypeRef.isAssignableTo(
    declarationStore: DeclarationStore,
    superType: TypeRef,
    substitutionMap: Map<ClassifierRef, TypeRef> = emptyMap()
): Boolean = declarationStore.memoize(HashKey3(this, superType, substitutionMap)) {
    if (isStarProjection || superType.isStarProjection) return@memoize true
    if (classifier.fqName == superType.classifier.fqName) {
        if (isMarkedNullable && !superType.isMarkedNullable) return@memoize false
        if (!qualifiers.isAssignableTo(declarationStore, superType.qualifiers)) return@memoize false
        if (macroChain != superType.macroChain) return@memoize false
        if (setKey != superType.setKey) return@memoize false
        if (thisAndAllSuperTypes.any { it.isComposable } != superType.thisAndAllSuperTypes.any { it.isComposable }) return@memoize false
        if (arguments.zip(superType.arguments)
                .any { (a, b) -> !a.isAssignableTo(declarationStore, b, substitutionMap) }
        )
            return@memoize false
        return@memoize true
    }
    if (superType.classifier.isTypeParameter) {
        val superTypesAssignable = superType.superTypes(substitutionMap).all { upperBound ->
            isSubTypeOf(declarationStore, upperBound, substitutionMap)
        }
        if (!superTypesAssignable) return@memoize false
        if (superType.qualifiers.isNotEmpty() &&
            !qualifiers.isAssignableTo(declarationStore, superType.qualifiers)
        ) return@memoize false
        if (macroChain != superType.macroChain) return@memoize false
        if (setKey != superType.setKey) return@memoize false
        return@memoize true
    } else if (classifier.isTypeParameter) {
        val superTypesAssignable = superTypes(substitutionMap).all { upperBound ->
            superType.isSubTypeOf(declarationStore, upperBound, substitutionMap)
        }
        if (!superTypesAssignable) return@memoize false
        if (qualifiers.isNotEmpty() &&
            !superType.qualifiers.isAssignableTo(declarationStore, qualifiers)
        ) return@memoize false
        if (macroChain != superType.macroChain) return@memoize false
        if (setKey != superType.setKey) return@memoize false
        return@memoize true
    }
    return@memoize false
}

fun TypeRef.isSubTypeOf(
    declarationStore: DeclarationStore,
    superType: TypeRef,
    substitutionMap: Map<ClassifierRef, TypeRef> = emptyMap()
): Boolean = declarationStore.memoize(HashKey3(this, superType, substitutionMap)) {
    if (isStarProjection) return@memoize true
    if (classifier.fqName == superType.classifier.fqName) {
        if (isMarkedNullable && !superType.isMarkedNullable) return@memoize false
        if (!qualifiers.isAssignableTo(declarationStore, superType.qualifiers)) return@memoize false
        if (macroChain != superType.macroChain) return@memoize false
        if (setKey != superType.setKey) return@memoize false
        if (thisAndAllSuperTypes.any { it.isComposable } != superType.thisAndAllSuperTypes.any { it.isComposable }) return@memoize false
        if (arguments.zip(superType.arguments)
                .any { (a, b) -> !a.isAssignableTo(declarationStore, b, substitutionMap) }
        )
            return@memoize false
        return@memoize true
    }
    if (superType.classifier.fqName == InjektFqNames.Any) {
        if (isMarkedNullable && !superType.isMarkedNullable) return@memoize false
        if (superType.qualifiers.isNotEmpty() &&
            !qualifiers.isAssignableTo(declarationStore, superType.qualifiers)
        ) return@memoize false
        return@memoize true
    }
    val subTypeView = subtypeView(superType.classifier, substitutionMap)
    if (subTypeView != null) {
        if (subTypeView == superType && (!subTypeView.isMarkedNullable || superType.isMarkedNullable) &&
            (superType.qualifiers.isEmpty() || subTypeView.qualifiers.isAssignableTo(declarationStore, superType.qualifiers))
        ) return@memoize true
        if (subTypeView.isMarkedNullable && !superType.isMarkedNullable) return@memoize false
        if (!subTypeView.qualifiers.isAssignableTo(declarationStore, superType.qualifiers)) return@memoize false
        if (subTypeView.macroChain != superType.macroChain) return@memoize false
        if (subTypeView.setKey != superType.setKey) return@memoize false
        if (thisAndAllSuperTypes.any { it.isComposable } != superType.thisAndAllSuperTypes.any { it.isComposable }) return@memoize false
        return@memoize subTypeView.arguments.zip(superType.arguments)
            .all { (subTypeArg, superTypeArg) ->
                superTypeArg.superTypes(substitutionMap).all {
                    subTypeArg.isSubTypeOf(declarationStore, it, substitutionMap)
                }
            }
    } else if ((superType.classifier.isTypeParameter && !classifier.isTypeParameter) || (superType.classifier.isTypeAlias &&
                !classifier.isTypeAlias)) {
        if (superType.qualifiers.isNotEmpty() &&
            !qualifiers.isAssignableTo(declarationStore, superType.qualifiers)
        ) return@memoize false
        if (macroChain != superType.macroChain) return@memoize false
        if (setKey != superType.setKey) return@memoize false
        if (thisAndAllSuperTypes.any { it.isComposable } != superType.thisAndAllSuperTypes.any { it.isComposable }) return@memoize false
        return@memoize superType.superTypes(substitutionMap).all { upperBound ->
            // todo should do this comparison without qualifiers?
            val r = isSubTypeOf(declarationStore, upperBound, substitutionMap) ||
                    (superType.qualifiers.isNotEmpty() &&
                            copy(qualifiers = emptyList()).isSubTypeOf(declarationStore, upperBound, substitutionMap))
            r
        }
    }
    return@memoize false
}

fun List<AnnotationRef>.isAssignableTo(declarationStore: DeclarationStore, superQualifiers: List<AnnotationRef>): Boolean {
    if (size != superQualifiers.size) return false
    return zip(superQualifiers).all { (thisQualifier, superQualifier) ->
        thisQualifier.isAssignableTo(declarationStore, superQualifier)
    }
}

fun AnnotationRef.isAssignableTo(declarationStore: DeclarationStore, superQualifier: AnnotationRef): Boolean {
    if (!type.isAssignableTo(declarationStore, superQualifier.type)) return false
    return arguments == superQualifier.arguments
}

val TypeRef.fullyExpandedType: TypeRef
    get() = expandedType?.fullyExpandedType ?: this

val KotlinType.fullyAbbreviatedType: KotlinType
    get() {
        val abbreviatedType = getAbbreviatedType()
        return if (abbreviatedType != null && abbreviatedType != this) abbreviatedType.fullyAbbreviatedType else this
    }

fun TypeRef.subtypeView(
    classifier: ClassifierRef,
    substitutionMap: Map<ClassifierRef, TypeRef> = emptyMap(),
): TypeRef? {
    if (this.classifier == classifier) return this
    expandedType?.subtypeView(classifier, substitutionMap)?.let { return it }
    for (superType in superTypes(substitutionMap)) {
        superType.subtypeView(classifier, substitutionMap)?.let { return it }
    }
    return null
}
