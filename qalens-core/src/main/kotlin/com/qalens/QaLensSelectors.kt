package com.qalens

/** A value-only, redacted view of one visible Compose node. IDs are scoped to the live tree. */
data class QaLensSelectorNode(val id: String, val parentId: String?, val attributes: Map<String, String>)

data class QaLensSelectorSuggestion(val title: String, val xpath: String, val matches: Int, val stability: String)

/**
 * Selectors address QaLens XML, not Android's accessibility/Appium XML. Evaluation implements a
 * bounded XPath 1.0 subset: node child/descendant paths, positive sibling positions, and attribute
 * equality joined with `and`. Generated literals support both quote characters using concat().
 * No imported XML, executable functions, regexes or arbitrary XPath evaluation enter the host.
 */
object QaLensSelectors {
    const val MAX_NODES = 1_000
    const val MAX_XPATH = 4_096
    val attributes = setOf("tag", "label", "text", "description", "role", "enabled", "selected", "heading",
        "clickable", "focusable", "tap", "type", "scroll")

    fun literal(value: String): String {
        val clean = xmlValue(value)
        return when {
            '\'' !in clean -> "'$clean'"
            '"' !in clean -> "\"$clean\""
            else -> "concat(" + clean.split('\'').joinToString(",\"'\",") { "'$it'" } + ")"
        }
    }

    /** Keep XML 1.0 attributes and evaluated selectors identical, including broken host strings. */
    fun xmlValue(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            when {
                c.isHighSurrogate() && i < value.length && value[i].isLowSurrogate() -> { append(c); append(value[i++]) }
                c.isSurrogate() -> Unit
                c == '\n' || c == '\r' || c == '\t' || c.code in 0x20..0xFFFD -> append(c)
            }
        }
    }

    fun search(nodes: List<QaLensSelectorNode>, query: String, tagged: Boolean = false, action: String = ""): List<QaLensSelectorNode> {
        val needle = query.trim()
        return nodes.take(MAX_NODES).filter { node ->
            (!tagged || !node.attributes["tag"].isNullOrBlank()) &&
                (action.isBlank() || node.attributes[action] == "true") &&
                (needle.isBlank() || node.attributes.values.any { it.contains(needle, ignoreCase = true) })
        }
    }

    fun suggest(nodes: List<QaLensSelectorNode>, id: String): List<QaLensSelectorSuggestion> {
        val tree = nodes.take(MAX_NODES)
        val target = tree.firstOrNull { it.id == id } ?: return emptyList()
        val result = linkedMapOf<String, QaLensSelectorSuggestion>()
        fun add(title: String, xpath: String, stability: String) {
            if (xpath.length <= MAX_XPATH && xpath !in result) {
                val count = runCatching { resolve(tree, xpath).size }.getOrNull() ?: return
                result[xpath] = QaLensSelectorSuggestion(title, xpath, count, stability)
            }
        }
        fun predicate(key: String, value: String) = "@$key=${literal(value)}"
        val tag = target.attributes["tag"].orEmpty()
        if (tag.isNotBlank() && !tag.contains("[REDACTED]", true)) {
            add("Test tag", "//node[${predicate("tag", tag)}]", "tag")
            val byId = tree.associateBy { it.id }
            var ancestor = target.parentId?.let(byId::get)
            val seen = mutableSetOf(id)
            var depth = 0
            while (ancestor != null && seen.add(ancestor.id) && depth++ < 64) {
                val ancestorTag = ancestor.attributes["tag"].orEmpty()
                if (ancestorTag.isNotBlank() && !ancestorTag.contains("[REDACTED]", true)) {
                    add("Within $ancestorTag", "//node[${predicate("tag", ancestorTag)}]//node[${predicate("tag", tag)}]", "tag")
                    if (result.values.lastOrNull()?.matches == 1) break
                }
                ancestor = ancestor.parentId?.let(byId::get)
            }
        }
        val role = target.attributes["role"].orEmpty()
        for (key in listOf("description", "text", "label")) {
            val value = target.attributes[key].orEmpty()
            if (value.isBlank() || value == "[protected]" || value.contains("[REDACTED]", true) || value == id) continue
            val condition = predicate(key, value)
            if (role.isNotBlank()) add("Role + $key", "//node[${predicate("role", role)} and $condition]", "content")
            add(key.replaceFirstChar { it.uppercase() }, "//node[$condition]", "content")
        }
        val path = position(tree, target)
        if (path != null) add("Tree position (changes with layout)", path, "position")
        return result.values.sortedWith(compareBy<QaLensSelectorSuggestion> { it.matches != 1 }.thenBy { it.stability == "position" }).take(8)
    }

    private fun position(nodes: List<QaLensSelectorNode>, target: QaLensSelectorNode): String? {
        val byId = nodes.associateBy { it.id }
        val parts = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var current: QaLensSelectorNode? = target
        while (current != null) {
            if (!seen.add(current.id) || parts.size >= 64) return null
            val parent = current.parentId?.takeIf { it in byId }
            val siblings = nodes.filter { it.parentId?.takeIf(byId::containsKey) == parent }
            parts.add("node[${siblings.indexOfFirst { it.id == current!!.id } + 1}]")
            current = parent?.let(byId::get)
        }
        return "/qalens/" + parts.asReversed().joinToString("/")
    }

    fun resolve(nodes: List<QaLensSelectorNode>, xpath: String): List<QaLensSelectorNode> {
        val steps = Parser(xpath).parse()
        val tree = nodes.take(MAX_NODES)
        val byId = tree.associateBy { it.id }
        var context = setOf<String?>(null)
        for (step in steps) {
            val next = linkedSetOf<String>()
            val candidates = tree.filter { node ->
                if (!step.descendant) node.parentId?.takeIf(byId::containsKey) in context else {
                    var current = node.parentId?.takeIf(byId::containsKey)
                    val seen = mutableSetOf(node.id)
                    var depth = 0
                    while (current !in context && current != null && seen.add(current) && depth++ < MAX_NODES) current = byId[current]?.parentId?.takeIf(byId::containsKey)
                    current in context
                }
            }
            val matching = candidates.filter { node -> step.tests.all { (key, value) -> node.attributes[key]?.let(::xmlValue) == value } }
            if (step.position == null) next.addAll(matching.map { it.id })
            else for (siblings in matching.groupBy { it.parentId?.takeIf(byId::containsKey) }.values)
                siblings.getOrNull(step.position - 1)?.let { next.add(it.id) }
            context = next
            if (context.isEmpty()) break
        }
        return tree.filter { it.id in context }
    }

    fun xml(nodes: List<QaLensSelectorNode>): String {
        val tree = nodes.take(MAX_NODES)
        val ids = tree.map { it.id }.toSet()
        val children = tree.groupBy { it.parentId?.takeIf(ids::contains) }
        val emitted = mutableSetOf<String>()
        return buildString {
            append("<qalens version=\"1\" coverage=\"visible-compose\">")
            val pending = java.util.ArrayDeque<Pair<QaLensSelectorNode?, Boolean>>()
            children[null].orEmpty().asReversed().forEach { pending.addLast(it to false) }
            while (pending.isNotEmpty()) {
                val (node, closing) = pending.removeLast()
                if (closing) { append("</node>"); continue }
                if (node == null || !emitted.add(node.id)) continue
                append("<node id=\"").append(escape(node.id)).append('"')
                node.attributes.filterKeys { it in attributes }.forEach { (key, value) -> append(' ').append(key).append("=\"").append(escape(value)).append('"') }
                append('>')
                pending.addLast(null to true)
                children[node.id].orEmpty().asReversed().forEach { pending.addLast(it to false) }
            }
            append("</qalens>")
        }
    }

    private fun escape(value: String): String = buildString {
        xmlValue(value).forEach { c -> append(when (c) {
            '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"
            '\n' -> "&#10;"; '\r' -> "&#13;"; '\t' -> "&#9;"
            else -> c.toString()
        }) }
    }

    private data class Step(val descendant: Boolean, val tests: Map<String, String>, val position: Int?)
    private class Parser(private val input: String) {
        private var p = 0
        private fun fail(): Nothing = throw IllegalArgumentException("Use /qalens/node[n] or //node[@tag='value']; node paths, attribute equality and 'and' are supported")
        private fun ws() { while (p < input.length && input[p].isWhitespace()) p++ }
        private fun eat(value: String): Boolean { ws(); if (!input.startsWith(value, p)) return false; p += value.length; return true }
        private fun requireToken(value: String) { if (!eat(value)) fail() }
        fun parse(): List<Step> {
            require(input.isNotBlank() && input.length <= MAX_XPATH) { "XPath must be 1–$MAX_XPATH characters" }
            val steps = mutableListOf<Step>()
            if (!eat("/qalens") && !input.trimStart().startsWith("//node")) fail()
            while (p < input.length) {
                ws(); if (p == input.length) break
                val descendant = eat("//")
                if (!descendant) requireToken("/")
                requireToken("node")
                var position: Int? = null
                val tests = linkedMapOf<String, String>()
                if (eat("[")) {
                    ws()
                    if (p < input.length && input[p].isDigit()) {
                        val start = p
                        while (p < input.length && input[p].isDigit()) p++
                        position = input.substring(start, p).toIntOrNull()?.takeIf { it in 1..MAX_NODES } ?: fail()
                    } else {
                        do {
                            requireToken("@")
                            val start = p
                            while (p < input.length && input[p].isLetter()) p++
                            val key = input.substring(start, p)
                            if (key !in attributes || key in tests || tests.size >= 13) fail()
                            requireToken("="); tests[key] = value()
                        } while (eat("and"))
                    }
                    requireToken("]")
                }
                steps.add(Step(descendant, tests, position))
                if (steps.size > 64) fail()
            }
            if (steps.isEmpty()) fail()
            return steps
        }
        private fun value(): String {
            if (eat("concat(")) {
                val parts = mutableListOf<String>()
                do { if (parts.size >= 128) fail(); parts.add(quoted()) } while (eat(","))
                requireToken(")"); if (parts.size < 2) fail()
                return parts.joinToString("")
            }
            return quoted()
        }
        private fun quoted(): String {
            ws(); val quote = input.getOrNull(p) ?: fail()
            if (quote != '\'' && quote != '"') fail()
            p++; val start = p
            while (p < input.length && input[p] != quote) p++
            if (p >= input.length) fail()
            return input.substring(start, p++)
        }
    }
}
