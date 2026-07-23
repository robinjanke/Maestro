package maestro.drivers.desktop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.TreeNode

internal object DesktopHierarchy {
    private val mapper = jacksonObjectMapper()

    data class Dump(
        val pid: Int?,
        val root: TreeNode,
    )

    fun parse(json: String): Dump {
        val node = mapper.readTree(json)
        val pid = node.path("pid").takeIf { !it.isMissingNode && !it.isNull }?.asInt()
        val rootNode = node.path("root")
        if (rootNode.isMissingNode || rootNode.isNull) {
            error("Hierarchy dump missing root: ${json.take(400)}")
        }
        return Dump(pid = pid, root = toTreeNode(rootNode))
    }

    private fun toTreeNode(node: JsonNode, depth: Int = 0): TreeNode {
        if (depth > 60) return TreeNode()

        val id = textOrNull(node, "id")
        val text = textOrNull(node, "text").orEmpty()
        val role = textOrNull(node, "role").orEmpty()
        val bounds = textOrNull(node, "bounds") ?: "[0,0][0,0]"
        val enabled = node.path("enabled").asBoolean(true)
        val focused = node.path("focused").asBoolean(false)
        val selected = node.path("selected").asBoolean(false)
        val clickable = node.path("clickable").asBoolean(
            role.lowercase() in CLICKABLE_ROLES,
        )

        val attributes = mutableMapOf(
            "text" to text,
            "bounds" to bounds,
            "role" to role,
        )
        if (!id.isNullOrBlank()) {
            attributes["resource-id"] = id
        }

        val children = node.path("children").takeIf { it.isArray }?.map { toTreeNode(it, depth + 1) }.orEmpty()

        return TreeNode(
            attributes = attributes,
            children = children,
            clickable = clickable,
            enabled = enabled,
            focused = focused,
            selected = selected,
        )
    }

    private fun textOrNull(node: JsonNode, field: String): String? {
        val value = node.path(field)
        if (value.isMissingNode || value.isNull) return null
        val text = value.asText()
        return text.takeIf { it.isNotBlank() }
    }

    private val CLICKABLE_ROLES = setOf(
        "button",
        "link",
        "menuitem",
        "checkbox",
        "radiobutton",
        "tab",
        "text",
        "edit",
        "combobox",
        "listitem",
        "hyperlink",
        "pushbutton",
        "axbutton",
        "axlink",
        "axmenuitem",
        "axtextfield",
        "axcheckbox",
        "axradiobutton",
        "axpopupbutton",
        "axcell",
    )
}
