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

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  kotlin("jvm")
  kotlin("kapt")
  kotlin("plugin.serialization")
  id("com.github.johnrengelman.shadow")
  id("com.ivianuu.shaded_injekt")
}

apply(from = "https://raw.githubusercontent.com/IVIanuu/gradle-scripts/master/java-8.gradle")
apply(from = "https://raw.githubusercontent.com/IVIanuu/gradle-scripts/master/kt-compiler-args.gradle")

val shadowJar = tasks.getByName<ShadowJar>("shadowJar") {
  configurations = listOf(project.configurations.getByName("compileOnly"))
  relocate("org.jetbrains.kotlin.com.intellij", "com.intellij")
  dependencies {
    exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
    exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib-common"))
    exclude(dependency("org.jetbrains:annotations"))

    exclude(dependency("com.intellij:openapi"))
    exclude(dependency("com.intellij:extensions"))
    exclude(dependency("com.intellij:annotations"))
  }
}

/*
kotlin {
  target {
    compilations.forEach { compilation ->
      val sourceSetName = name

      val project = compilation.compileKotlinTask.project

      val dumpDir = project.buildDir.resolve("injekt/dump/$sourceSetName")
        .also { it.mkdirs() }

      val pluginOptions = listOf(
        SubpluginOption(
          key = "dumpDir",
          value = dumpDir.absolutePath
        ),
        SubpluginOption(
          key = "rootPackage",
          value = "com.ivianuu.shaded_injekt"
        )
      )

      pluginOptions.forEach { option ->
        compilation.kotlinOptions.freeCompilerArgs += listOf(
          "-P", "plugin:com.ivianuu.injekt:${option.key}=${option.value}"
        )
      }
    }
  }
}*/

artifacts {
  archives(shadowJar)
}

dependencies {
  implementation(Deps.autoService)
  kapt(Deps.autoService)
  api(Deps.Kotlin.compilerEmbeddable)
  compileOnly(Deps.AndroidX.Compose.compiler)
  implementation(Deps.KotlinSerialization.json)
  //kotlinCompilerPluginClasspath(Deps.InjektShaded.compilerPlugin)
}

plugins.apply("com.vanniktech.maven.publish")
