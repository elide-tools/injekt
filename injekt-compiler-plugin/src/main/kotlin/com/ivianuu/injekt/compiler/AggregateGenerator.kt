package com.ivianuu.injekt.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.addChild
import org.jetbrains.kotlin.backend.common.ir.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalVirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.SingleRootFileViewProvider
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.impl.ClassDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.PackageFragmentDescriptorImpl
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.MetadataSource
import org.jetbrains.kotlin.ir.declarations.impl.IrClassImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDelegatingConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi2ir.PsiSourceManager
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.scopes.MemberScopeImpl
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.utils.Printer
import java.io.File

class AggregateGenerator(
    pluginContext: IrPluginContext,
    private val project: Project
) : AbstractInjektTransformer(pluginContext) {

    override fun visitModuleFragment(declaration: IrModuleFragment): IrModuleFragment {
        val thisModuleProperties = mutableListOf<IrProperty>()

        declaration.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitProperty(declaration: IrProperty): IrStatement {
                if (declaration.descriptor.annotations.hasAnnotation(InjektClassNames.ModuleMarker)) {
                    thisModuleProperties += declaration
                }

                return super.visitProperty(declaration)
            }
        })

        thisModuleProperties.forEach { moduleProperty ->
            declaration.files += aggregatedModule(moduleProperty)
        }

        return super.visitModuleFragment(declaration)
    }

    private fun aggregatedModule(moduleProperty: IrProperty): IrFile {
        val psiSourceManager = pluginContext.psiSourceManager as PsiSourceManager

        val className =
            Name.identifier(moduleProperty.descriptor.fqNameSafe.asString().replace(".", "_"))

        val sourceFile = File("$className.kt")

        val virtualFile = CoreLocalVirtualFile(CoreLocalFileSystem(), sourceFile)

        val ktFile = KtFile(
            SingleRootFileViewProvider(
                PsiManager.getInstance(project),
                virtualFile
            ),
            false
        )

        val memberScope = AggregateMemberScope()

        val packageFragmentDescriptor =
            object : PackageFragmentDescriptorImpl(
                pluginContext.moduleDescriptor,
                FqName("com.ivianuu.injekt.aggregate")
            ) {
                override fun getMemberScope(): MemberScope = memberScope
            }

        val fileEntry = psiSourceManager.getOrCreateFileEntry(ktFile)
        val file = IrFileImpl(
            fileEntry,
            packageFragmentDescriptor
        )
        psiSourceManager.putFileEntry(file, fileEntry)

        val classDescriptor = ClassDescriptorImpl(
            packageFragmentDescriptor,
            className,
            Modality.FINAL,
            ClassKind.CLASS,
            emptyList(),
            SourceElement.NO_SOURCE,
            false,
            LockBasedStorageManager.NO_LOCKS
        ).apply {
            initialize(
                MemberScope.Empty,
                emptySet(),
                null
            )
        }

        memberScope.classDescriptors += classDescriptor

        file.addChild(
            IrClassImpl(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                InjektOrigin,
                IrClassSymbolImpl(classDescriptor)
            ).apply clazz@{
                createImplicitParameterDeclarationWithWrappedDescriptor()

                metadata = MetadataSource.Class(classDescriptor)

                addConstructor {
                    origin = InjektOrigin
                    isPrimary = true
                    visibility = Visibilities.PUBLIC
                }.apply {
                    body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                        +IrDelegatingConstructorCallImpl(
                            startOffset, endOffset,
                            context.irBuiltIns.unitType,
                            pluginContext.symbolTable.referenceConstructor(
                                context.builtIns.any.unsubstitutedPrimaryConstructor!!
                            )
                        )
                        +IrInstanceInitializerCallImpl(
                            startOffset,
                            endOffset,
                            this@clazz.symbol,
                            context.irBuiltIns.unitType
                        )
                    }
                }
            }
        )

        return file
    }

}

private class AggregateMemberScope : MemberScopeImpl() {
    val classDescriptors = mutableListOf<ClassDescriptor>()

    override fun getContributedClassifier(
        name: Name,
        location: LookupLocation
    ): ClassifierDescriptor? {
        return classDescriptors.firstOrNull { it.name == name }
    }

    override fun getClassifierNames(): Set<Name>? {
        return classDescriptors.mapTo(mutableSetOf()) { it.name }
    }

    override fun printScopeStructure(p: Printer) {
    }
}