/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro

import maestro.UiElement.Companion.toUiElementOrNull

data class TreeNode(
    val attributes: MutableMap<String, String> = mutableMapOf(),
    val children: List<TreeNode> = emptyList(),
    val clickable: Boolean? = null,
    val enabled: Boolean? = null,
    val focused: Boolean? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
) {

    fun aggregate(): List<TreeNode> {
        return listOf(this) + children.flatMap { it.aggregate() }
    }

    fun isAncestorOf(descendant: TreeNode): Boolean {
        if (this === descendant) {
            return false
        }
        return aggregate().any { it === descendant }
    }

    /**
     * Flutter Web often exposes a single semantics node with viewport-sized bounds while the
     * actionable control is a smaller descendant. When the matched node covers most of the
     * screen, prefer the smallest reasonable descendant in the main content area for tapping.
     */
    fun resolveCompactTapTarget(screenWidth: Int, screenHeight: Int): TreeNode {
        val selfElement = toUiElementOrNull() ?: return this
        val viewportArea = screenWidth * screenHeight
        if (selfElement.bounds.area() < (viewportArea * 0.3)) {
            return this
        }

        val candidates = children
            .flatMap { it.aggregate() }
            .mapNotNull { node ->
                node.toUiElementOrNull()?.let { ui ->
                    val bounds = ui.bounds
                    val area = bounds.area()
                    if (area in 1500..(viewportArea / 6) &&
                        bounds.width in 50..700 &&
                        bounds.height in 18..100
                    ) {
                        node to ui
                    } else {
                        null
                    }
                }
            }

        if (candidates.isEmpty()) {
            return this
        }

        val sidebarMaxX = (screenWidth * 0.25).toInt()
        val minActionY = (screenHeight * 0.25).toInt()
        val mainContent = candidates.filter { (_, ui) ->
            ui.bounds.x >= sidebarMaxX && ui.bounds.y >= minActionY
        }
        val actionBand = candidates.filter { (_, ui) ->
            ui.bounds.x >= sidebarMaxX && ui.bounds.y >= minActionY
        }
        val pool = when {
            actionBand.isNotEmpty() -> actionBand
            mainContent.isNotEmpty() -> mainContent
            else -> candidates.filter { (_, ui) -> ui.bounds.y >= minActionY }
        }

        if (pool.isEmpty()) {
            return this
        }

        return pool.minWithOrNull(
            compareBy<Pair<TreeNode, UiElement>> { (_, ui) -> ui.bounds.area() }
                .thenByDescending { (_, ui) -> ui.bounds.y },
        )?.first ?: this
    }

}
