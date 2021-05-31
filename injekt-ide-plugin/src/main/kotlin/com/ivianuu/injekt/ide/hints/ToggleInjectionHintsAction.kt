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
package com.ivianuu.injekt.ide.hints

import com.intellij.codeInsight.daemon.*
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.*

var injectionHintsEnabled = false

class ToggleInjectionHintsAction : ToggleAction(
  "Toggle injection hints",
  "Show hints for injected parameters",
  null
) {
  override fun isSelected(event: AnActionEvent): Boolean = injectionHintsEnabled

  override fun setSelected(event: AnActionEvent, value: Boolean) {
    injectionHintsEnabled = value
    ProjectManager.getInstance().openProjects.forEach {
      DaemonCodeAnalyzer.getInstance(event.project).restart()
    }
  }
}