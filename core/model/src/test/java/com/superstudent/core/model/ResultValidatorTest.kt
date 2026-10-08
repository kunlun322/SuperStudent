package com.superstudent.core.model

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultValidatorTest {

    private fun idArray(ids: List<String>) = ids.joinToString(",", "[", "]") { "\"$it\"" }

    private val pkg = "pkg_test"
    private val citations = setOf("c1", "c2")

    private fun citationsRaw(packageId: String = pkg, schemaVersion: Int = 1) = """
        {"schemaVersion":$schemaVersion,"packageId":"$packageId","data":{"citations":[
          {"citationId":"c1","quote":"设函数 f 在点 x0 的某邻域内有定义","locator":{"page":3}},
          {"citationId":"c2","quote":"若极限存在则称 f 在 x0 连续","locator":{"page":7}}
        ]}}
    """.trimIndent()

    private fun planRaw(
        packageId: String = pkg,
        schemaVersion: Int = 1,
        topicCount: Int = 3,
        orders: List<Int> = (1..topicCount).toList(),
        citationIds: List<String> = listOf("c1"),
    ): String {
        val topics = (0 until topicCount).joinToString(",") { i ->
            """{"topicId":"t${i + 1}","order":${orders[i]},"title":"知识点 ${i + 1}","estimatedMinutes":20,
               "tasks":[{"taskId":"t${i + 1}a","type":"READ","instruction":"阅读并复述定义","minutes":10}],
               "citationIds":${idArray(citationIds)}}"""
        }
        return """{"schemaVersion":$schemaVersion,"packageId":"$packageId","data":{"topics":[$topics]}}"""
    }

    private fun cardsRaw(
        packageId: String = pkg,
        schemaVersion: Int = 1,
        count: Int = 5,
        citationIds: List<String> = listOf("c2"),
    ): String {
        val cards = (1..count).joinToString(",") { i ->
            """{"cardId":"card$i","kind":"DEFINITION","front":"问题 $i","back":"答案 $i",
               "citationIds":${idArray(citationIds)}}"""
        }
        return """{"schemaVersion":$schemaVersion,"packageId":"$packageId","data":{"cards":[$cards]}}"""
    }

    @Test
    fun `accepts well formed artifacts`() {
        assertEquals(2, ResultValidator.validateCitations(citationsRaw(), pkg).citations.size)
        assertEquals(3, ResultValidator.validatePlan(planRaw(), pkg, citations).topics.size)
        assertEquals(5, ResultValidator.validateCards(cardsRaw(), pkg, citations).cards.size)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects wrong schema version`() {
        ResultValidator.validatePlan(planRaw(schemaVersion = 2), pkg, citations)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects foreign package id`() {
        ResultValidator.validateCards(cardsRaw(packageId = "pkg_other"), pkg, citations)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects plan with fewer than three topics`() {
        ResultValidator.validatePlan(planRaw(topicCount = 2, orders = listOf(1, 2)), pkg, citations)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects non increasing topic order`() {
        ResultValidator.validatePlan(planRaw(orders = listOf(1, 1, 2)), pkg, citations)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects unknown citation reference in plan`() {
        ResultValidator.validatePlan(planRaw(citationIds = listOf("c999")), pkg, citations)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects cards below five`() {
        ResultValidator.validateCards(cardsRaw(count = 4), pkg, citations)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects card without citation`() {
        ResultValidator.validateCards(cardsRaw(citationIds = emptyList()), pkg, citations)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects citation without locator`() {
        val raw = """{"schemaVersion":1,"packageId":"$pkg","data":{"citations":[{"citationId":"c1","quote":"原文"}]}}"""
        ResultValidator.validateCitations(raw, pkg)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects citation without quote`() {
        val raw = """{"schemaVersion":1,"packageId":"$pkg","data":{"citations":[{"citationId":"c1","quote":"","locator":{"page":1}}]}}"""
        ResultValidator.validateCitations(raw, pkg)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects unparsable envelope`() {
        ResultValidator.validatePlan("not json", pkg, citations)
    }

    @Test
    fun `locator describes the page for citation display`() {
        val data = ResultValidator.validateCitations(citationsRaw(), pkg)
        assertTrue(data.citations.first().locator!!.describe().contains("3"))
    }

    // ---------- mindmap ----------

    private fun mindmapRaw(
        packageId: String = pkg,
        schemaVersion: Int = 1,
        citationIds: List<String> = listOf("c1"),
        childCount: Int = 3,
    ): String {
        val children = (1..childCount).joinToString(",") { i ->
            """{"nodeId":"mm_$i","label":"分支 $i","explanation":"解释 $i","citationIds":${idArray(citationIds)}}"""
        }
        return """{"schemaVersion":$schemaVersion,"packageId":"$packageId","data":{
            "title":"第三章知识树","layout":"CENTER",
            "root":{"nodeId":"mm_0","label":"导数","citationIds":${idArray(citationIds)},"children":[$children]},
            "artifacts":{"htmlPath":"superstudent/v1/packages/$packageId/results/mindmap.html"}}}"""
    }

    @Test
    fun `accepts well formed mindmap and flattens pre order`() {
        val data = ResultValidator.validateMindmap(mindmapRaw(), pkg, citations)
        val flat = data.flatten()
        assertEquals(4, flat.size)
        assertEquals("mm_0", flat.first().second.nodeId)
        assertEquals(0, flat.first().first)
        assertEquals(1, flat[1].first)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects mindmap node without citation`() {
        ResultValidator.validateMindmap(mindmapRaw(citationIds = emptyList()), pkg, citations)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects mindmap node with unknown citation`() {
        ResultValidator.validateMindmap(mindmapRaw(citationIds = listOf("c404")), pkg, citations)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects mindmap that is only a root`() {
        ResultValidator.validateMindmap(mindmapRaw(childCount = 0), pkg, citations)
    }

    // ---------- deck manifest ----------

    private fun deckRaw(
        packageId: String = pkg,
        schemaVersion: Int = 1,
        kinds: List<SlideKind> = listOf(
            SlideKind.TITLE, SlideKind.CONCEPT, SlideKind.CONCEPT, SlideKind.EXAMPLE, SlideKind.SUMMARY,
        ),
        slideCount: Int? = null,
        citationIds: List<String> = listOf("c1"),
        sha256: String? = "a".repeat(64),
    ): String {
        val slides = kinds.mapIndexed { i, k ->
            """{"slide":${i + 1},"title":"第 ${i + 1} 页","kind":"${k.name}","speakerNotes":true,
               "citationIds":${idArray(citationIds)}}"""
        }.joinToString(",")
        val count = slideCount ?: kinds.size
        val hashField = if (sha256 == null) "" else "\"sha256\":\"$sha256\","
        return """{"schemaVersion":$schemaVersion,"packageId":"$packageId","data":{
            "path":"superstudent/v1/packages/$packageId/results/deck.pptx",$hashField"sizeBytes":43753,
            "slideCount":$count,"theme":"superstudent-v1","slides":[$slides]}}"""
    }

    @Test
    fun `accepts well formed deck manifest`() {
        val data = ResultValidator.validateDeckManifest(deckRaw(), pkg, citations)
        assertEquals(5, data.slideCount)
        assertTrue(data.slides.any { it.kind == SlideKind.CONCEPT })
        assertTrue(data.slides.any { it.kind == SlideKind.EXAMPLE })
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects deck without example page`() {
        ResultValidator.validateDeckManifest(
            deckRaw(kinds = listOf(SlideKind.TITLE, SlideKind.CONCEPT, SlideKind.SUMMARY)), pkg, citations
        )
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects deck without concept page`() {
        ResultValidator.validateDeckManifest(
            deckRaw(kinds = listOf(SlideKind.TITLE, SlideKind.EXAMPLE, SlideKind.SUMMARY)), pkg, citations
        )
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects deck slide count mismatch`() {
        ResultValidator.validateDeckManifest(deckRaw(slideCount = 9), pkg, citations)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects deck manifest without hash`() {
        ResultValidator.validateDeckManifest(deckRaw(sha256 = null), pkg, citations)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects concept slide without citation`() {
        ResultValidator.validateDeckManifest(deckRaw(citationIds = emptyList()), pkg, citations)
    }

    // ---------- exercises ----------

    private fun exercisesRaw(
        packageId: String = pkg,
        schemaVersion: Int = 1,
        count: Int = 3,
        answerKey: String = "B",
        citationIds: List<String> = listOf("c1"),
        knowledgePointIds: List<String> = emptyList(),
    ): String {
        val items = (1..count).joinToString(",") { i ->
            if (i % 2 == 0) {
                """{"exerciseId":"ex$i","type":"CALCULATION","stem":"求 f(x)=x^2 在 x=1 处的导数",
                   "options":[],"answer":{"optionKey":null,"value":2,"tolerance":0.001},
                   "analysis":"由定义 f'(x)=2x，代入 x=1 得 2","difficulty":"MEDIUM",
                   "knowledgePointIds":${idArray(knowledgePointIds)},"citationIds":${idArray(citationIds)}}"""
            } else {
                """{"exerciseId":"ex$i","type":"SINGLE_CHOICE","stem":"下列哪项是导数的定义？",
                   "options":[{"key":"A","text":"极限不存在"},{"key":"B","text":"差商的极限"},{"key":"C","text":"积分"}],
                   "answer":{"optionKey":"$answerKey","value":null,"tolerance":null},
                   "analysis":"导数是差商当 Δx→0 时的极限","difficulty":"EASY",
                   "knowledgePointIds":${idArray(knowledgePointIds)},"citationIds":${idArray(citationIds)}}"""
            }
        }
        return """{"schemaVersion":$schemaVersion,"packageId":"$packageId","data":{"exercises":[$items]}}"""
    }

    @Test
    fun `accepts well formed exercises and grades both types`() {
        val data = ResultValidator.validateExercises(exercisesRaw(), pkg, citations)
        assertEquals(3, data.exercises.size)
        val choice = data.exercises.first { it.type == ExerciseType.SINGLE_CHOICE }
        assertTrue(choice.answer.isCorrect("B"))
        assertTrue(!choice.answer.isCorrect("A"))
        val calc = data.exercises.first { it.type == ExerciseType.CALCULATION }
        assertTrue(calc.answer.isCorrect("2.0005"))
        assertTrue(!calc.answer.isCorrect("3"))
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects exercises below minimum`() {
        ResultValidator.validateExercises(exercisesRaw(count = 2), pkg, citations)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects single choice answer outside options`() {
        ResultValidator.validateExercises(exercisesRaw(answerKey = "Z"), pkg, citations)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects exercise without citation`() {
        ResultValidator.validateExercises(exercisesRaw(citationIds = emptyList()), pkg, citations)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects exercise referencing unknown knowledge point`() {
        ResultValidator.validateExercises(
            exercisesRaw(knowledgePointIds = listOf("kp_404")), pkg, citations, knownTopicIds = setOf("kp_01")
        )
    }

    // ---- FR-10 anti-cross-library gate (logical isolation: the client is the only backstop) ----

    private fun citationsRawSourced(vararg sourceIds: String?): String {
        val entries = sourceIds.mapIndexed { i, sid ->
            val source = if (sid == null) "" else ",\"sourceId\":\"$sid\""
            """{"citationId":"c${i + 1}","quote":"引用片段 ${i + 1}"$source,"locator":{"page":${i + 1}}}"""
        }
        return """{"schemaVersion":1,"packageId":"$pkg","data":{"citations":[${entries.joinToString(",")}]}}"""
    }

    @Test
    fun `accepts citations whose sources are all owned`() {
        val data = ResultValidator.validateCitations(
            citationsRawSourced("s1", "s2"), pkg, allowedSourceIds = setOf("s1", "s2", "s3")
        )
        assertEquals(2, data.citations.size)
    }

    @Test(expected = ArtifactValidationException::class)
    fun `rejects the whole result set when one citation is foreign`() {
        ResultValidator.validateCitations(citationsRawSourced("s1", "s_other"), pkg, allowedSourceIds = setOf("s1"))
    }

    @Test
    fun `skips the ownership gate when no allowlist is supplied`() {
        val data = ResultValidator.validateCitations(citationsRawSourced("s_any"), pkg)
        assertEquals("s_any", data.citations.single().sourceId)
    }

    @Test
    fun `passes citations that carry no sourceId`() {
        val data = ResultValidator.validateCitations(citationsRawSourced(null, "s1"), pkg, allowedSourceIds = setOf("s1"))
        assertEquals(2, data.citations.size)
    }

    // ---------- mindmap visual contract v2 (ZLQ-137 / ZLQ-140 D1, D3, D4) ----------

    /**
     * One tree — a root, two level-1 branches and one level-2 leaf under the first — rendered twice.
     * Both fixtures carry the same nodeIds, labels, explanations, citationIds and parent→child edges;
     * only `layout` and the coordinates differ. That is what makes them a pair: a rule that happens
     * to hold for CENTER but not for HORIZONTAL shows up as an asymmetric result instead of hiding.
     */
    private val treeLabels = linkedMapOf(
        "mm_0" to ("导数" to "变化率的核心概念"),
        "mm_1" to ("极限" to "导数定义中的极限"),
        "mm_2" to ("求导法则" to "四则与复合求导"),
        "mm_3" to ("洛必达法则" to "零比零型未定式"),
    )

    private val treeChildren = linkedMapOf(
        "mm_0" to listOf("mm_1", "mm_2"),
        "mm_1" to listOf("mm_3"),
        "mm_2" to emptyList(),
        "mm_3" to emptyList(),
    )

    private val treeEdges = listOf("mm_0" to "mm_1", "mm_0" to "mm_2", "mm_1" to "mm_3")

    private val canvasWidth = 1600
    private val canvasHeight = 800

    /** x, y, width, height. Root on the canvas centre, one child right, one left, the leaf further right. */
    private val centerRects = linkedMapOf(
        "mm_0" to listOf(700.0, 370.0, 200.0, 60.0),
        "mm_1" to listOf(1050.0, 200.0, 200.0, 60.0),
        "mm_2" to listOf(350.0, 540.0, 200.0, 60.0),
        "mm_3" to listOf(1380.0, 100.0, 200.0, 60.0),
    )

    /** Same tree on the same canvas: root leftmost, every level strictly right of its parent. */
    private val horizontalRects = linkedMapOf(
        "mm_0" to listOf(40.0, 370.0, 200.0, 60.0),
        "mm_1" to listOf(400.0, 150.0, 200.0, 60.0),
        "mm_2" to listOf(400.0, 560.0, 200.0, 60.0),
        "mm_3" to listOf(760.0, 150.0, 200.0, 60.0),
    )

    private val viewportMeta = """<meta name="viewport" content="width=device-width, initial-scale=1">"""
    private val textAdjustCss = "-webkit-text-size-adjust: 100%;"
    private val toggleMarkup = """<button data-action="toggle" aria-expanded="true">折叠</button>"""
    private val cspMeta =
        """<meta http-equiv="Content-Security-Policy" """ +
            """content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:;">"""

    private fun treeNodeJson(id: String): String {
        val (label, explanation) = treeLabels.getValue(id)
        val children = treeChildren.getValue(id).joinToString(",", "[", "]") { treeNodeJson(it) }
        return """{"nodeId":"$id","label":"$label","explanation":"$explanation","citationIds":["c1"],"children":$children}"""
    }

    /**
     * `contractJson`, `edges` and `includeVisual` let a case corrupt one field of the manifest
     * without touching the rest.
     */
    private fun visualMindmapRaw(
        layout: String,
        rects: Map<String, List<Double>>,
        contractJson: String = "\"visualContractVersion\":2,",
        edges: List<Pair<String, String>> = treeEdges,
        includeVisual: Boolean = true,
    ): String {
        val nodes = rects.entries.joinToString(",") { (id, r) ->
            """{"nodeId":"$id","x":${r[0]},"y":${r[1]},"width":${r[2]},"height":${r[3]}}"""
        }
        val edgeJson = edges.joinToString(",") { (from, to) -> """{"from":"$from","to":"$to"}""" }
        val visual = if (!includeVisual) {
            ""
        } else {
            "\n            \"visual\":{\"canvas\":{\"width\":$canvasWidth,\"height\":$canvasHeight}," +
                "\"nodes\":[$nodes],\"edges\":[$edgeJson]},"
        }
        return """{"schemaVersion":1,"packageId":"$pkg","data":{
            "title":"第三章知识树","layout":"$layout",$contractJson$visual
            "root":${treeNodeJson("mm_0")},
            "artifacts":{"htmlPath":"superstudent/v1/packages/$pkg/results/mindmap.html"}}}"""
    }

    private fun nodeMarkup(id: String, r: List<Double>): String {
        val (label, explanation) = treeLabels.getValue(id)
        val x = r[0]
        val y = r[1]
        val w = r[2]
        val h = r[3]
        val toggle = if (treeChildren.getValue(id).isEmpty()) "" else toggleMarkup
        return """<g class="mindmap-node" data-node-id="$id" data-x="$x" data-y="$y" data-width="$w" data-height="$h">""" +
            """<rect x="$x" y="$y" width="$w" height="$h" rx="12"/>""" +
            """<text x="${x + w / 2}" y="${y + 24}">$label</text>""" +
            """<text x="${x + w / 2}" y="${y + 44}">$explanation</text>""" +
            """<text x="${x + w / 2}" y="${y + h - 4}">引用 c1</text>""" +
            toggle + "</g>"
    }

    /**
     * The reference page the prompt asks the cloud for. The inline script is a real collapse
     * implementation, not a placeholder: one click on a non-leaf toggle hides every descendant and
     * every edge touching it, flips `aria-expanded`, and a second click restores both.
     */
    private fun visualHtml(
        layout: String,
        rects: Map<String, List<Double>>,
        headExtra: String = "",
        bodyExtra: String = "",
        csp: String = cspMeta,
        dropEdges: Set<Pair<String, String>> = emptySet(),
    ): String {
        val edges = treeEdges.filterNot { it in dropEdges }.joinToString("\n") { (from, to) ->
            val a = rects.getValue(from)
            val b = rects.getValue(to)
            """<path class="mindmap-edge" data-from="$from" data-to="$to" """ +
                """d="M${a[0] + a[2]},${a[1] + a[3] / 2} L${b[0]},${b[1] + b[3] / 2}"/>"""
        }
        val nodes = rects.entries.joinToString("\n") { (id, r) -> nodeMarkup(id, r) }
        return """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
$viewportMeta
$csp
<style>html{$textAdjustCss}svg{display:block;width:100%;height:100%}</style>$headExtra
</head><body>
<svg id="ss-mindmap" data-contract-version="2" data-layout="$layout" viewBox="0 0 $canvasWidth $canvasHeight" width="100%" height="100%" preserveAspectRatio="xMidYMid meet">
$edges
$nodes
</svg>$bodyExtra
<script>
(function () {
  var svg = document.getElementById('ss-mindmap');
  var parentOf = {};
  each(svg.querySelectorAll('.mindmap-edge'), function (e) {
    parentOf[e.getAttribute('data-to')] = e.getAttribute('data-from');
  });
  function each(list, fn) { Array.prototype.forEach.call(list, fn); }
  function descendants(rootId) {
    var out = [];
    each(svg.querySelectorAll('.mindmap-node[data-node-id]'), function (n) {
      var cur = n.getAttribute('data-node-id');
      while (parentOf[cur]) {
        if (parentOf[cur] === rootId) { out.push(cur); return; }
        cur = parentOf[cur];
      }
    });
    return out;
  }
  function setHidden(id, hidden) {
    var node = svg.querySelector('.mindmap-node[data-node-id="' + id + '"]');
    if (node) { node.style.display = hidden ? 'none' : ''; }
    each(svg.querySelectorAll('.mindmap-edge[data-from="' + id + '"], .mindmap-edge[data-to="' + id + '"]'),
      function (e) { e.style.display = hidden ? 'none' : ''; });
  }
  each(svg.querySelectorAll('button[data-action="toggle"]'), function (btn) {
    btn.addEventListener('click', function () {
      var owner = btn.closest('.mindmap-node');
      if (!owner) { return; }
      var collapse = btn.getAttribute('aria-expanded') !== 'false';
      btn.setAttribute('aria-expanded', collapse ? 'false' : 'true');
      each(descendants(owner.getAttribute('data-node-id')), function (id) { setHidden(id, collapse); });
    });
  });
})();
</script>
</body></html>"""
    }

    /** A page shaped like the ZLQ-137 defect: a heading plus a document-flow outline, no graph. */
    private fun outlineHtml(): String = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
$viewportMeta
$cspMeta
<style>html{$textAdjustCss}</style>
</head><body>
<h1>第三章知识树</h1>
<ul><li>导数 —— 变化率的核心概念（引用 c1）</li>
<li>极限 —— 导数定义中的极限（引用 c1）</li>
<li>求导法则 —— 四则与复合求导（引用 c1）</li>
<li>洛必达法则 —— 零比零型未定式（引用 c1）</li></ul>
</body></html>"""

    private fun visualData(layout: String, rects: Map<String, List<Double>>): MindmapData =
        ResultValidator.validateMindmap(visualMindmapRaw(layout, rects), pkg, citations)

    @Test
    fun `accepts the center fixture as a graph shaped mindmap`() {
        val data = visualData("CENTER", centerRects)
        ResultValidator.validateMindmapHtml(visualHtml("CENTER", centerRects), data, strictV2 = true)
        assertEquals(MINDMAP_VISUAL_CONTRACT_VERSION, data.visualContractVersion)
        assertEquals(4, data.nodeIds().size)
        assertEquals(treeEdges.toSet(), data.treeEdges())
        assertEquals(setOf("mm_0", "mm_1"), data.nonLeafNodeIds())
    }

    @Test
    fun `accepts the horizontal fixture over the same tree`() {
        val center = visualData("CENTER", centerRects)
        val horizontal = visualData("HORIZONTAL", horizontalRects)
        ResultValidator.validateMindmapHtml(visualHtml("HORIZONTAL", horizontalRects), horizontal, strictV2 = true)
        // The pair differs only in layout and geometry — topology is byte-identical.
        assertEquals(center.nodeIds(), horizontal.nodeIds())
        assertEquals(center.treeEdges(), horizontal.treeEdges())
        assertNotEquals(center.layout, horizontal.layout)
        assertEquals(MindmapLayout.HORIZONTAL, horizontal.layout)
    }

    @Test
    fun `accepts integer coordinates in the manifest against decimal dom attributes`() {
        // A generator that writes 700 and one that writes 700.0 describe the same rectangle.
        val raw = visualMindmapRaw("CENTER", centerRects).replace("\"x\":700.0", "\"x\":700")
        val data = ResultValidator.validateMindmap(raw, pkg, citations)
        ResultValidator.validateMindmapHtml(visualHtml("CENTER", centerRects), data, strictV2 = true)
        assertEquals(700.0, data.visual!!.nodes.first { it.nodeId == "mm_0" }.x, 0.0)
    }

    @Test
    fun `the accepted page exposes a toggle on every non leaf node and on no leaf`() {
        val doc = Jsoup.parse(visualHtml("CENTER", centerRects))
        val toggles = doc.select("button[data-action=toggle][aria-expanded]")
        assertEquals(2, toggles.size)
        toggles.forEach { button ->
            val owner = button.parents().first { it.hasClass("mindmap-node") }
            assertTrue(owner.attr("data-node-id") in setOf("mm_0", "mm_1"))
            assertTrue(button.attr("aria-expanded").let { it == "true" || it == "false" })
        }
        listOf("mm_2", "mm_3").forEach { leaf ->
            val node = doc.selectFirst(".mindmap-node[data-node-id=$leaf]")!!
            assertTrue(node.select("button[data-action=toggle]").isEmpty())
        }
    }

    /**
     * A counter-example has to fail *for the reason the design counts* — an accidental throw from a
     * neighbouring rule would hide a hole in the rule actually under test.
     */
    private fun assertRejectedWith(prefix: String, block: () -> Unit) {
        val failure = try {
            block()
            null
        } catch (e: ArtifactValidationException) {
            e
        }
        assertNotNull("期望校验失败（$prefix），实际通过了", failure)
        assertTrue(
            "期望失败原因以 '$prefix:' 开头，实际 '${failure!!.message}'",
            failure.message!!.startsWith("$prefix:"),
        )
    }

    @Test
    fun `rejects a document flow outline instead of a graph`() = assertRejectedWith("html-invalid") {
        ResultValidator.validateMindmapHtml(outlineHtml(), visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects a heading outside the graph`() = assertRejectedWith("html-invalid") {
        val html = visualHtml("CENTER", centerRects, bodyExtra = "<h1>第三章知识树</h1>")
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects list markup outside the graph`() = assertRejectedWith("html-invalid") {
        val html = visualHtml("CENTER", centerRects, bodyExtra = "<ul><li>导数</li><li>极限</li></ul>")
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects html missing one edge`() = assertRejectedWith("topology-mismatch") {
        val html = visualHtml("CENTER", centerRects, dropEdges = setOf("mm_1" to "mm_3"))
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects a manifest missing one edge`() = assertRejectedWith("topology-mismatch") {
        val shortOfEdges = visualMindmapRaw(
            "CENTER", centerRects, edges = listOf("mm_0" to "mm_1", "mm_0" to "mm_2")
        )
        val data = ResultValidator.validateMindmap(shortOfEdges, pkg, citations)
        ResultValidator.validateMindmapHtml(visualHtml("CENTER", centerRects), data, strictV2 = true)
    }

    @Test
    fun `rejects html whose layout disagrees with the json`() = assertRejectedWith("html-invalid") {
        val html = visualHtml("HORIZONTAL", centerRects)
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects a dom node id the json does not declare`() = assertRejectedWith("topology-mismatch") {
        val html = visualHtml("CENTER", centerRects).replace("""data-node-id="mm_3"""", """data-node-id="mm_9"""")
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects node coordinates that disagree with the manifest`() = assertRejectedWith("html-invalid") {
        val shifted = centerRects + ("mm_1" to listOf(1090.0, 200.0, 200.0, 60.0))
        val html = visualHtml("CENTER", shifted)
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects a non leaf node without a toggle control`() = assertRejectedWith("html-invalid") {
        val html = visualHtml("CENTER", centerRects).replace(toggleMarkup, "")
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects overlapping nodes`() = assertRejectedWith("geometry-invalid") {
        val overlapping = centerRects + ("mm_2" to listOf(710.0, 380.0, 200.0, 60.0))
        val html = visualHtml("CENTER", overlapping)
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", overlapping), strictV2 = true)
    }

    @Test
    fun `rejects nodes closer than the minimum gap without overlapping`() = assertRejectedWith("geometry-invalid") {
        // mm_3 sits 10px to the right of mm_1's right edge: no overlap, still inside the canvas,
        // but below MIN_NODE_GAP, so the pairwise gap check has to catch it.
        val crowded = centerRects + ("mm_3" to listOf(1260.0, 200.0, 200.0, 60.0))
        val html = visualHtml("CENTER", crowded)
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", crowded), strictV2 = true)
    }

    @Test
    fun `rejects a horizontal tree that does not advance left to right`() = assertRejectedWith("geometry-invalid") {
        val flat = horizontalRects + ("mm_1" to listOf(100.0, 150.0, 200.0, 60.0))
        val html = visualHtml("HORIZONTAL", flat)
        ResultValidator.validateMindmapHtml(html, visualData("HORIZONTAL", flat), strictV2 = true)
    }

    @Test
    fun `rejects a center root that is off the canvas centre`() = assertRejectedWith("geometry-invalid") {
        val raised = centerRects + ("mm_0" to listOf(700.0, 100.0, 200.0, 60.0))
        val html = visualHtml("CENTER", raised)
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", raised), strictV2 = true)
    }

    @Test
    fun `rejects center children crowded onto one side`() = assertRejectedWith("geometry-invalid") {
        val oneSided = centerRects + ("mm_2" to listOf(1050.0, 540.0, 200.0, 60.0))
        val html = visualHtml("CENTER", oneSided)
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", oneSided), strictV2 = true)
    }

    @Test
    fun `rejects a node that leaves the canvas`() = assertRejectedWith("geometry-invalid") {
        val outside = centerRects + ("mm_3" to listOf(1500.0, 100.0, 200.0, 60.0))
        val html = visualHtml("CENTER", outside)
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", outside), strictV2 = true)
    }

    @Test
    fun `rejects an external stylesheet link`() = assertRejectedWith("html-invalid") {
        val html = visualHtml(
            "CENTER", centerRects,
            headExtra = """<link rel="stylesheet" href="https://cdn.example.com/mindmap.css">""",
        )
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects an external image reference inside the graph`() = assertRejectedWith("html-invalid") {
        val html = visualHtml("CENTER", centerRects, bodyExtra = """<img src="https://example.com/a.png">""")
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects a csp that opens the network`() = assertRejectedWith("html-invalid") {
        val loose = """<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https:;">"""
        val html = visualHtml("CENTER", centerRects, csp = loose)
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects a forbidden iframe`() = assertRejectedWith("html-invalid") {
        val html = visualHtml("CENTER", centerRects, bodyExtra = """<iframe src="about:blank"></iframe>""")
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects html without a viewport meta`() = assertRejectedWith("html-invalid") {
        val html = visualHtml("CENTER", centerRects).replace(viewportMeta, "")
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects html without the text size adjust pin`() = assertRejectedWith("html-invalid") {
        val html = visualHtml("CENTER", centerRects).replace(textAdjustCss, "")
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects html over one mebibyte`() = assertRejectedWith("html-invalid") {
        val html = visualHtml("CENTER", centerRects) + "<!--" + "x".repeat(1100 * 1024) + "-->"
        ResultValidator.validateMindmapHtml(html, visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects an empty html document`() = assertRejectedWith("html-invalid") {
        ResultValidator.validateMindmapHtml("", visualData("CENTER", centerRects), strictV2 = true)
    }

    @Test
    fun `rejects a wrong contract version`() = assertRejectedWith("missing-contract") {
        val raw = visualMindmapRaw("CENTER", centerRects, contractJson = "\"visualContractVersion\":1,")
        ResultValidator.validateMindmapHtml(
            visualHtml("CENTER", centerRects), ResultValidator.validateMindmap(raw, pkg, citations), strictV2 = true
        )
    }

    @Test
    fun `rejects a v2 manifest without a visual block`() = assertRejectedWith("missing-contract") {
        val raw = visualMindmapRaw("CENTER", centerRects, includeVisual = false)
        ResultValidator.validateMindmapHtml(
            visualHtml("CENTER", centerRects), ResultValidator.validateMindmap(raw, pkg, citations), strictV2 = true
        )
    }

    @Test
    fun `rejects a legacy package without the contract when the run is strict`() =
        assertRejectedWith("missing-contract") {
            val legacy = ResultValidator.validateMindmap(mindmapRaw(), pkg, citations)
            ResultValidator.validateMindmapHtml(visualHtml("CENTER", centerRects), legacy, strictV2 = true)
        }

    @Test
    fun `a legacy package yields no displayable html on a lenient re read`() {
        val legacy = ResultValidator.validateMindmap(mindmapRaw(), pkg, citations)
        assertNull(legacy.visualContractVersion)
        // D3.5: an old package is never retroactively judged bad — its map is simply not displayed.
        assertNull(ResultValidator.acceptedMindmapHtml(outlineHtml(), legacy, strictV2 = false))
        assertNull(ResultValidator.acceptedMindmapHtml(null, legacy, strictV2 = false))
    }

    @Test
    fun `a pre contract map fails the strict run that decides success`() =
        assertRejectedWith("missing-contract") {
            val legacy = ResultValidator.validateMindmap(mindmapRaw(), pkg, citations)
            ResultValidator.acceptedMindmapHtml(outlineHtml(), legacy, strictV2 = true)
        }

    @Test
    fun `only a page that cleared the contract is handed to the ui`() {
        val data = visualData("CENTER", centerRects)
        val html = visualHtml("CENTER", centerRects)
        assertEquals(html, ResultValidator.acceptedMindmapHtml(html, data, strictV2 = true))
        assertEquals(html, ResultValidator.acceptedMindmapHtml(html, data, strictV2 = false))
        // Which path asked changes the consequence, never the verdict: lenient hides, it never forgives.
        assertNull(ResultValidator.acceptedMindmapHtml(outlineHtml(), data, strictV2 = false))
    }
}
