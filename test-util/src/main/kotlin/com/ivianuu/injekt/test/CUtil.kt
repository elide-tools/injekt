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

package com.ivianuu.injekt.test

import androidx.compose.compiler.plugins.kotlin.ComposeCommandLineProcessor
import androidx.compose.compiler.plugins.kotlin.ComposeComponentRegistrar
import com.ivianuu.injekt.compiler.InjektCommandLineProcessor
import com.ivianuu.injekt.compiler.InjektComponentRegistrar
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.name.FqName
import java.net.URLClassLoader
import kotlin.reflect.KClass

var fileIndex = 0

fun source(
    @Language("kotlin") source: String,
    name: String = "File${fileIndex++}.kt",
    injektImports: Boolean = true,
    packageFqName: FqName = FqName("com.ivianuu.injekt.integrationtests"),
) = SourceFile.kotlin(
    name = name,
    contents = buildString {
        if (injektImports) {
            appendLine("package $packageFqName")
            appendLine()
            appendLine("import androidx.compose.runtime.*")
            appendLine("import com.ivianuu.injekt.*")
            appendLine("import com.ivianuu.injekt.common.*")
            appendLine("import com.ivianuu.injekt.component.*")
            appendLine("import com.ivianuu.injekt.integrationtests.*")
            appendLine("import com.ivianuu.injekt.internal.*")
            appendLine("import com.ivianuu.injekt.test.*")
            appendLine("import kotlin.reflect.*")
            appendLine("import kotlinx.coroutines.*")
            appendLine()
        }

        append(source)
    }
)

fun singleSource(
    @Language("kotlin") source: String,
    name: String = "File.kt",
    injektImports: Boolean = true,
) = source(source, name, injektImports)

fun codegen(
    @Language("kotlin") source: String,
    config: KotlinCompilation.() -> Unit = {},
    assertions: KotlinCompilation.Result.() -> Unit = { assertOk() },
) = codegen(
    singleSource(source),
    config = config,
    assertions = assertions
)

fun codegen(
    vararg sources: SourceFile,
    config: KotlinCompilation.() -> Unit = {},
    assertions: KotlinCompilation.Result.() -> Unit = { assertOk() },
) {
    val result = compile {
        this.sources = sources.toList()
        config()
    }
    println("Result: ${result.exitCode} m: ${result.messages}")
    assertions(result)
}

fun multiCodegen(
    vararg sources: List<SourceFile>,
    config: KotlinCompilation.(Int) -> Unit = {},
    assertions: (List<KotlinCompilation.Result>) -> Unit = { it.forEach { it.assertOk() } },
) {
    val prevCompilations = mutableListOf<KotlinCompilation>()
    val results = sources.mapIndexed { index, sourceFiles ->
        compile {
            this.sources = sourceFiles
            this.classpaths += prevCompilations.map { it.classesDir }
            config(index)
            prevCompilations += this
        }
    }
    assertions(results)
}

fun compilation(block: KotlinCompilation.() -> Unit = {}) = KotlinCompilation().apply {
    compilerPlugins = listOf(InjektComponentRegistrar(), ComposeComponentRegistrar())
    commandLineProcessors = listOf(InjektCommandLineProcessor(), ComposeCommandLineProcessor())
    inheritClassPath = true
    useIR = true
    jvmTarget = "1.8"
    verbose = false
    kotlincArguments += "-XXLanguage:+NewInference"
    pluginOptions += PluginOption(
        "com.ivianuu.injekt",
        "srcDir",
        workingDir.resolve("injekt/generated/src/main").absolutePath
    )
    pluginOptions += PluginOption(
        "com.ivianuu.injekt",
        "cacheDir",
        workingDir.resolve("injekt/cache").absolutePath
    )
    pluginOptions += PluginOption(
        "com.ivianuu.injekt",
        "dumpDir",
        workingDir.resolve("injekt/dump").absolutePath
    )
    block()
}

fun compile(block: KotlinCompilation.() -> Unit = {}) = compilation(
    block
).compile()

fun KotlinCompilation.Result.assertOk() {
    assertEquals(KotlinCompilation.ExitCode.OK, exitCode)
}

@JvmName("invokeSingleFileTypeless")
fun List<KotlinCompilation.Result>.invokeSingleFile(vararg args: Any?): Any? =
    invokeSingleFile<Any?>(*args)

fun <T> List<KotlinCompilation.Result>.invokeSingleFile(vararg args: Any?): T {
    val classLoader = URLClassLoader(
        flatMap { it.classLoader.urLs.toList() }
            .toTypedArray()
    )
    val generatedClass = classLoader.getSingleClass().java
    return generatedClass.declaredMethods
        .single { it.name == "invoke" && it.parameterTypes.size == args.size }
        .invoke(null, *args) as T
}

@JvmName("invokeSingleFileTypeless")
fun KotlinCompilation.Result.invokeSingleFile(vararg args: Any?): Any? =
    invokeSingleFile<Any?>(*args)

fun <T> KotlinCompilation.Result.invokeSingleFile(vararg args: Any?): T {
    val generatedClass = classLoader.getSingleClass().java
    return generatedClass.declaredMethods
        .single { it.name == "invoke" && it.parameterTypes.size == args.size }
        .invoke(null, *args) as T
}

private fun ClassLoader.getSingleClass(): KClass<*> =
    loadClass("com.ivianuu.injekt.integrationtests.FileKt").kotlin

fun KotlinCompilation.Result.assertCompileError(
    message: String? = null,
) {
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, exitCode)
    message?.let { assertTrue(messages.toLowerCase().contains(it.toLowerCase())) }
}

fun KotlinCompilation.Result.assertMessage(
    message: String,
) {
    assertTrue(message in messages)
}

fun KotlinCompilation.Result.assertNoMessage(message: String) {
    assertFalse(message in messages)
}

inline fun KotlinCompilation.Result.irAssertions(block: (String) -> Unit) {
    assertOk()
    outputDirectory
        .parentFile
        .resolve("injekt/dump")
        .walkTopDown()
        .filter { it.isFile }
        .map { it.readText() }
        .joinToString("\n")
        .also {
            assert(it.isNotEmpty()) {
                "Source is empty"
            }
        }
        .let(block)
}

fun KotlinCompilation.Result.assertIrContainsText(times: Int, text: String) {
    irAssertions {
        val matchesCount = it.countMatches(text)
        assert(matchesCount == times) {
            "expected '$text' $times times but was found $matchesCount times in '$it'"
        }
    }
}

fun String.countMatches(other: String): Int = split(other)
    .dropLastWhile { it.isEmpty() }.size - 1

fun KotlinCompilation.Result.assertIrNotContainsText(text: String) {
    irAssertions {
        assert(text !in it) {
            "'$text' in source '$it'"
        }
    }
}
