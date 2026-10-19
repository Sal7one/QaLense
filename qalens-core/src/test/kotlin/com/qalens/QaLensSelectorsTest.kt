package com.qalens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import org.w3c.dom.NodeList

class QaLensSelectorsTest {
    private fun node(id: String, parent: String? = null, vararg attrs: Pair<String, String>) = QaLensSelectorNode(id, parent, attrs.toMap())
    private val tree = listOf(node("a", null, "tag" to "cart"),
        node("b", "a", "tag" to "remove", "text" to "Bob's \"item\" & <gift>\nمرحبا", "role" to "Button", "tap" to "true"),
        node("c", null, "tag" to "saved"), node("d", "c", "tag" to "remove", "role" to "Button", "tap" to "true"))

    private fun standardIds(xml: String, xpath: String): List<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val result = XPathFactory.newInstance().newXPath().evaluate(xpath, document, XPathConstants.NODESET) as NodeList
        return (0 until result.length).map { result.item(it).attributes.getNamedItem("id").nodeValue }
    }
    @Test fun suggestionsAndSubsetAgreeWithRealXPath() {
        val xml = QaLensSelectors.xml(tree)
        val suggestions = QaLensSelectors.suggest(tree, "b")
        assertTrue(suggestions.any { it.title == "Within cart" && it.matches == 1 })
        assertTrue(suggestions.any { it.title == "Test tag" && it.matches == 2 })
        for (suggestion in suggestions) {
            val ours = QaLensSelectors.resolve(tree, suggestion.xpath).map { it.id }
            assertEquals(standardIds(xml, suggestion.xpath), ours, suggestion.xpath)
            assertTrue("b" in ours)
            assertEquals(ours.size, suggestion.matches)
        }
    }
    @Test fun actionsSearchAndExactAttributes() {
        assertEquals(listOf("b", "d"), QaLensSelectors.search(tree, "BUTTON", action = "tap").map { it.id })
        assertEquals(listOf("b"), QaLensSelectors.search(tree, "مرحبا").map { it.id })
        assertEquals(listOf("b", "d"), QaLensSelectors.resolve(tree, "//node[@role='Button' and @tap='true']").map { it.id })
        assertTrue(QaLensSelectors.resolve(tree, "//node[@tag='absent']").isEmpty())
    }
    @Test fun malformedOrExpensiveExpressionsAreRejected() {
        for (xpath in listOf("", "/node", "//*", "//node[0]", "//node[@secret='x']", "//node[@tag='x' or @tag='y']", "//node[contains(@text,'x')]", "//node[@tag='a'][1]", "//node[@tag='x'] | //node", "//node[9999999999999999]", "//node[@tag=concat('x')]", "x".repeat(4097))) {
            assertFailsWith<IllegalArgumentException>(xpath) { QaLensSelectors.resolve(tree, xpath) }
        }
    }
    @Test fun protectedValuesDoNotBecomeLocators() {
        val protected = listOf(node("private", null, "label" to "[protected]", "text" to "[REDACTED]"))
        assertEquals(listOf("position"), QaLensSelectors.suggest(protected, "private").map { it.stability })
    }
    @Test fun invalidHostCharactersCannotBreakXmlOrChangeMatches() {
        val unusual = listOf(node("a", null, "tag" to "bad\u0000\uFFFE\uD800 tag 😀", "text" to "line\n\tend"))
        val xml = QaLensSelectors.xml(unusual)
        assertTrue(xml.contains("bad tag 😀"))
        for (suggestion in QaLensSelectors.suggest(unusual, "a"))
            assertEquals(standardIds(xml, suggestion.xpath), QaLensSelectors.resolve(unusual, suggestion.xpath).map { it.id })
    }
    @Test fun positionsUseVisibleSiblingsAndDifferentRoots() {
        for (xpath in listOf("/qalens/node[2]/node[1]", "//node[1]", "//node[@tag='cart']/node[@tag='remove']")) {
            assertEquals(standardIds(QaLensSelectors.xml(tree), xpath), QaLensSelectors.resolve(tree, xpath).map { it.id })
        }
        val orphan = listOf(node("child", "omitted", "tag" to "orphan"))
        assertEquals("/qalens/node[1]", QaLensSelectors.suggest(orphan, "child").last().xpath)
    }
}
