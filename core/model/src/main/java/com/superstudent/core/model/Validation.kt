package com.superstudent.core.model

import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.math.abs
import kotlin.math.hypot

val ssJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = false
}

/** Lenient json for SSE/partial payloads. */
val ssJsonLenient = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
}

class ArtifactValidationException(message: String) : Exception(message)

/**
 * Strict artifact validation per design §5: envelope/schemaVersion/enums/ID refs/citations.
 * Anything failing here keeps the task out of SUCCEEDED.
 */
object ResultValidator {

    fun validateCitations(
        raw: String,
        expectedPackageId: String,
        allowedSourceIds: Set<String>? = null,
    ): CitationsData {
        val env = try {
            ssJson.decodeFromString<Envelope<CitationsData>>(raw)
        } catch (e: Exception) {
            throw ArtifactValidationException("citations.json 解析失败: ${e.message?.take(200)}")
        }
        requireSchema(env, expectedPackageId, "citations.json")
        val data = env.data
        if (data.citations.isEmpty()) throw ArtifactValidationException("citations.json 引用集合为空")
        val ids = mutableSetOf<String>()
        for (c in data.citations) {
            if (c.citationId.isBlank()) throw ArtifactValidationException("存在空 citationId")
            if (!ids.add(c.citationId)) throw ArtifactValidationException("重复 citationId: ${c.citationId}")
            if (c.quote.isBlank()) throw ArtifactValidationException("citation ${c.citationId} 缺少 quote")
            if (c.quote.length > 2000) throw ArtifactValidationException("citation ${c.citationId} quote 超长")
            val loc = c.locator
            if (loc == null || (loc.page == null && loc.slide == null && loc.paragraph == null && loc.lineStart == null)) {
                throw ArtifactValidationException("citation ${c.citationId} 缺少任何定位信息")
            }
        }
        requireOwnSources(data, allowedSourceIds)
        return data
    }

    /**
     * Anti-cross-library gate (FR-10 / design §7.2). Isolation here is LOGICAL, not a platform
     * permission boundary: qmind authenticates with the single Vault token, so nothing server-side
     * stops a retrieval from drifting into another student's Notebook. The client is therefore the
     * only backstop, and it refuses the WHOLE result set rather than dropping the foreign chunks —
     * a partially filtered set still proves the Notebook was reachable.
     */
    private fun requireOwnSources(data: CitationsData, allowedSourceIds: Set<String>?) {
        if (allowedSourceIds.isNullOrEmpty()) return
        val foreign = data.citations
            .mapNotNull { it.sourceId }
            .filter { it !in allowedSourceIds }
            .distinct()
        if (foreign.isNotEmpty()) {
            throw ArtifactValidationException(
                "检索结果包含不属于本学习包的来源，已整体拒绝：${foreign.take(3).joinToString(", ")}"
            )
        }
    }

    fun validatePlan(raw: String, expectedPackageId: String, knownCitationIds: Set<String>): PlanData {
        val env = try {
            ssJson.decodeFromString<Envelope<PlanData>>(raw)
        } catch (e: Exception) {
            throw ArtifactValidationException("plan.json 解析失败: ${e.message?.take(200)}")
        }
        requireSchema(env, expectedPackageId, "plan.json")
        val data = env.data
        if (data.topics.size < 3) throw ArtifactValidationException("plan.json 知识点少于 3 个")
        val topicIds = mutableSetOf<String>()
        var lastOrder = 0
        for (t in data.topics) {
            if (t.topicId.isBlank()) throw ArtifactValidationException("存在空 topicId")
            if (!topicIds.add(t.topicId)) throw ArtifactValidationException("重复 topicId: ${t.topicId}")
            if (t.title.isBlank()) throw ArtifactValidationException("topic ${t.topicId} 标题为空")
            if (t.estimatedMinutes <= 0) throw ArtifactValidationException("topic ${t.topicId} 时长必须为正数")
            if (t.order <= lastOrder) throw ArtifactValidationException("topic 顺序必须严格递增: ${t.topicId}")
            lastOrder = t.order
            if (t.citationIds.isEmpty()) throw ArtifactValidationException("topic ${t.topicId} 缺少引用")
            for (cid in t.citationIds) {
                if (cid !in knownCitationIds) throw ArtifactValidationException("topic ${t.topicId} 引用了不存在的 citationId: $cid")
            }
            val taskIds = mutableSetOf<String>()
            for (pt in t.tasks) {
                if (pt.taskId.isBlank()) throw ArtifactValidationException("topic ${t.topicId} 存在空任务 ID")
                if (!taskIds.add(pt.taskId)) throw ArtifactValidationException("重复学习任务 ID: ${pt.taskId}")
                if (pt.instruction.isBlank()) throw ArtifactValidationException("学习任务 ${pt.taskId} 说明为空")
                if (pt.minutes <= 0) throw ArtifactValidationException("学习任务 ${pt.taskId} 时长必须为正数")
            }
            for (pre in t.prerequisiteTopicIds) {
                if (pre !in topicIds && pre !in data.topics.map { it.topicId }) {
                    throw ArtifactValidationException("topic ${t.topicId} 前置引用不存在: $pre")
                }
            }
        }
        return data
    }

    fun validateCards(raw: String, expectedPackageId: String, knownCitationIds: Set<String>): CardsData {
        val env = try {
            ssJson.decodeFromString<Envelope<CardsData>>(raw)
        } catch (e: Exception) {
            throw ArtifactValidationException("cards.json 解析失败: ${e.message?.take(200)}")
        }
        requireSchema(env, expectedPackageId, "cards.json")
        val data = env.data
        if (data.cards.size < 5) throw ArtifactValidationException("cards.json 闪卡少于 5 张")
        val ids = mutableSetOf<String>()
        for (c in data.cards) {
            if (c.cardId.isBlank()) throw ArtifactValidationException("存在空 cardId")
            if (!ids.add(c.cardId)) throw ArtifactValidationException("重复 cardId: ${c.cardId}")
            if (c.front.isBlank() || c.back.isBlank()) throw ArtifactValidationException("闪卡 ${c.cardId} 正面/背面为空")
            if (c.citationIds.isEmpty()) throw ArtifactValidationException("闪卡 ${c.cardId} 缺少引用")
            for (cid in c.citationIds) {
                if (cid !in knownCitationIds) throw ArtifactValidationException("闪卡 ${c.cardId} 引用了不存在的 citationId: $cid")
            }
        }
        return data
    }

    private fun <T> requireSchema(env: Envelope<T>, expectedPackageId: String, name: String) {
        if (env.schemaVersion != 1) throw ArtifactValidationException("$name schemaVersion 必须为 1，实际 ${env.schemaVersion}")
        if (env.packageId != expectedPackageId) {
            throw ArtifactValidationException("$name packageId 不匹配: ${env.packageId}")
        }
    }

    // ---------- mindmap.json ----------

    fun validateMindmap(
        raw: String,
        expectedPackageId: String,
        knownCitationIds: Set<String>,
    ): MindmapData {
        val env = try {
            ssJson.decodeFromString<Envelope<MindmapData>>(raw)
        } catch (e: Exception) {
            throw ArtifactValidationException("mindmap.json 解析失败: ${e.message?.take(200)}")
        }
        requireSchema(env, expectedPackageId, "mindmap.json")
        val data = env.data
        if (data.root.label.isBlank()) throw ArtifactValidationException("mindmap.json 根节点标题为空")

        val seen = mutableSetOf<String>()
        var count = 0
        fun walk(node: MindmapNodeJson, depth: Int) {
            if (depth > MAX_MINDMAP_DEPTH) throw ArtifactValidationException("mindmap.json 层级超过 $MAX_MINDMAP_DEPTH 层")
            if (node.nodeId.isBlank()) throw ArtifactValidationException("mindmap.json 存在空 nodeId")
            if (!seen.add(node.nodeId)) throw ArtifactValidationException("重复 nodeId: ${node.nodeId}")
            if (node.label.isBlank()) throw ArtifactValidationException("节点 ${node.nodeId} 标题为空")
            if (node.citationIds.isEmpty()) throw ArtifactValidationException("节点 ${node.nodeId} 缺少引用")
            for (cid in node.citationIds) {
                if (cid !in knownCitationIds) throw ArtifactValidationException("节点 ${node.nodeId} 引用了不存在的 citationId: $cid")
            }
            count++
            if (count > MAX_MINDMAP_NODES) throw ArtifactValidationException("mindmap.json 节点数超过 $MAX_MINDMAP_NODES")
            node.children.forEach { walk(it, depth + 1) }
        }
        walk(data.root, 0)
        if (count < MIN_MINDMAP_NODES) throw ArtifactValidationException("mindmap.json 节点少于 $MIN_MINDMAP_NODES 个")
        return data
    }

    // ---------- deck.manifest.json ----------

    fun validateDeckManifest(
        raw: String,
        expectedPackageId: String,
        knownCitationIds: Set<String>,
    ): DeckManifestData {
        val env = try {
            ssJson.decodeFromString<Envelope<DeckManifestData>>(raw)
        } catch (e: Exception) {
            throw ArtifactValidationException("deck.manifest.json 解析失败: ${e.message?.take(200)}")
        }
        requireSchema(env, expectedPackageId, "deck.manifest.json")
        val data = env.data
        if (data.path.isBlank() || !data.path.endsWith(".pptx")) {
            throw ArtifactValidationException("deck.manifest.json path 必须指向 .pptx: ${data.path}")
        }
        if (data.sha256.isNullOrBlank()) throw ArtifactValidationException("deck.manifest.json 缺少 sha256")
        if (data.sizeBytes == null || data.sizeBytes <= 0) throw ArtifactValidationException("deck.manifest.json sizeBytes 非法")
        if (data.slides.isEmpty()) throw ArtifactValidationException("deck.manifest.json 没有幻灯片描述")
        if (data.slideCount != data.slides.size) {
            throw ArtifactValidationException("deck.manifest.json slideCount=${data.slideCount} 与 slides=${data.slides.size} 不一致")
        }
        var expectedSlide = 1
        for (s in data.slides) {
            if (s.slide != expectedSlide) throw ArtifactValidationException("幻灯片序号必须从 1 连续递增，实际 ${s.slide}")
            expectedSlide++
            if (s.title.isBlank()) throw ArtifactValidationException("第 ${s.slide} 页标题为空")
            if (s.kind == SlideKind.CONCEPT || s.kind == SlideKind.EXAMPLE) {
                if (s.citationIds.isEmpty()) throw ArtifactValidationException("第 ${s.slide} 页（${s.kind.name}）缺少引用")
                for (cid in s.citationIds) {
                    if (cid !in knownCitationIds) throw ArtifactValidationException("第 ${s.slide} 页引用了不存在的 citationId: $cid")
                }
            }
        }
        val kinds = data.slides.map { it.kind }.toSet()
        if (SlideKind.TITLE !in kinds) throw ArtifactValidationException("deck.manifest.json 缺少封面页 TITLE")
        if (SlideKind.CONCEPT !in kinds) throw ArtifactValidationException("deck.manifest.json 缺少讲解页 CONCEPT")
        if (SlideKind.EXAMPLE !in kinds) throw ArtifactValidationException("deck.manifest.json 缺少例题页 EXAMPLE")
        return data
    }

    // ---------- exercises.json ----------

    fun validateExercises(
        raw: String,
        expectedPackageId: String,
        knownCitationIds: Set<String>,
        knownTopicIds: Set<String> = emptySet(),
    ): ExercisesData {
        val env = try {
            ssJson.decodeFromString<Envelope<ExercisesData>>(raw)
        } catch (e: Exception) {
            throw ArtifactValidationException("exercises.json 解析失败: ${e.message?.take(200)}")
        }
        requireSchema(env, expectedPackageId, "exercises.json")
        val data = env.data
        if (data.exercises.size < MIN_EXERCISES) throw ArtifactValidationException("exercises.json 习题少于 $MIN_EXERCISES 道")
        val ids = mutableSetOf<String>()
        for (ex in data.exercises) {
            if (ex.exerciseId.isBlank()) throw ArtifactValidationException("存在空 exerciseId")
            if (!ids.add(ex.exerciseId)) throw ArtifactValidationException("重复 exerciseId: ${ex.exerciseId}")
            if (ex.stem.isBlank()) throw ArtifactValidationException("习题 ${ex.exerciseId} 题干为空")
            if (ex.analysis.isBlank()) throw ArtifactValidationException("习题 ${ex.exerciseId} 缺少解析")
            if (ex.citationIds.isEmpty()) throw ArtifactValidationException("习题 ${ex.exerciseId} 缺少引用")
            for (cid in ex.citationIds) {
                if (cid !in knownCitationIds) throw ArtifactValidationException("习题 ${ex.exerciseId} 引用了不存在的 citationId: $cid")
            }
            if (knownTopicIds.isNotEmpty()) {
                for (kp in ex.knowledgePointIds) {
                    if (kp !in knownTopicIds) throw ArtifactValidationException("习题 ${ex.exerciseId} 引用了不存在的知识点: $kp")
                }
            }
            when (ex.type) {
                ExerciseType.SINGLE_CHOICE -> {
                    if (ex.options.size < 2) throw ArtifactValidationException("单选题 ${ex.exerciseId} 选项少于 2 个")
                    val keys = mutableSetOf<String>()
                    for (opt in ex.options) {
                        if (opt.key.isBlank()) throw ArtifactValidationException("单选题 ${ex.exerciseId} 存在空选项 key")
                        if (!keys.add(opt.key)) throw ArtifactValidationException("单选题 ${ex.exerciseId} 选项 key 重复: ${opt.key}")
                        if (opt.text.isBlank()) throw ArtifactValidationException("单选题 ${ex.exerciseId} 选项 ${opt.key} 内容为空")
                    }
                    val answerKey = ex.answer.optionKey
                    if (answerKey.isNullOrBlank()) throw ArtifactValidationException("单选题 ${ex.exerciseId} 缺少 answer.optionKey")
                    if (answerKey !in keys) throw ArtifactValidationException("单选题 ${ex.exerciseId} 答案 $answerKey 不在选项中")
                }
                ExerciseType.CALCULATION -> {
                    if (ex.answer.value.isNullOrBlank()) throw ArtifactValidationException("计算题 ${ex.exerciseId} 缺少 answer.value")
                    ex.answer.tolerance?.let {
                        if (it < 0) throw ArtifactValidationException("计算题 ${ex.exerciseId} tolerance 不能为负")
                    }
                }
            }
        }
        return data
    }

    const val MIN_MINDMAP_NODES = 4
    const val MAX_MINDMAP_NODES = 300
    const val MAX_MINDMAP_DEPTH = 6
    const val MIN_EXERCISES = 3

    // ---------- mindmap.html visual contract v2 (ZLQ-140 D1 / D3 / D4) ----------

    /** Minimum visual gap between any two node rectangles, in CSS px. */
    const val MIN_NODE_GAP = 16.0

    /** Minimum outward advance per level, in CSS px — HORIZONTAL right, CENTER away from the root. */
    const val MIN_LEVEL_ADVANCE = 48.0

    /** Minimum distance between a parent's centre and its child's centre, in CSS px. */
    const val MIN_PARENT_CHILD_CENTRE_DISTANCE = 48.0

    /** CENTER root may sit at most this fraction of the canvas off the canvas centre, per axis. */
    const val CENTER_ROOT_TOLERANCE = 0.05

    /** mindmap.html is UTF-8 and must stay under 1 MiB. */
    const val MAX_MINDMAP_HTML_BYTES = 1024 * 1024

    /** Generators may round a coordinate; anything tighter than this is noise, not a mismatch. */
    private const val COORD_EPSILON = 0.5

    private const val REQUIRED_CSP = "default-src 'none'"

    private val FORBIDDEN_TAGS = listOf("iframe", "object", "embed", "form", "applet")

    private val HEADING_SELECTOR = "h1, h2, h3, h4, h5, h6"

    private val LIST_SELECTOR = "ul, ol, li"

    private val URL_ATTRS = setOf(
        "src", "href", "action", "formaction", "data", "poster", "background", "cite",
        "longdesc", "srcset", "manifest", "profile", "xlink:href",
    )

    private val CSS_URL = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""")

    private val URL_SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    /** Only in-page schemes survive the offline WebView; anything else is an external reference. */
    private val ALLOWED_URL_SCHEMES = setOf("data", "about", "blob")

    /**
     * Cross-checks `mindmap.html` against the `mindmap.json` visual manifest.
     *
     * Whether a page is a mindmap stops being a judgement call about markup and becomes a question
     * about identifiers, numbers and DOM structure: a document-style `ul`/`li` outline has no
     * canvas, no node rectangles and no edge set, so it fails here instead of being rendered as if
     * it were a graph. Failure messages are prefixed with the reason class the design asks to be
     * counted — `missing-contract` / `topology-mismatch` / `geometry-invalid` / `html-invalid`.
     *
     * @param strictV2 `true` for a run that is trying to reach SUCCEEDED. `false` only for reading a
     *   package that may predate v2: such a package is never retroactively judged bad, it simply
     *   yields no accepted HTML, and the UI falls back to the PNG or the native tree.
     */
    fun validateMindmapHtml(html: String, mindmap: MindmapData, strictV2: Boolean) {
        val visual = requireVisualManifest(mindmap, strictV2) ?: return
        val doc = parseHtml(html)
        requireHtmlShape(doc)
        requireSvgGraph(doc, mindmap, visual)
    }

    /**
     * The HTML the UI is allowed to render, or null.
     *
     * Acceptance is one rule on both paths — only the consequence differs. A strict run is the one
     * deciding whether the task SUCCEEDED, so a rejection rethrows and fails the whole fetch, which
     * is what puts the package back to READY for the student to regenerate. A lenient re-read of an
     * already-finished package never judges it bad; it just yields no HTML, and the UI falls back to
     * the PNG or the native structure tree. Without that split, a pre-v2 outline would keep being
     * rendered as though it were an accepted map.
     */
    fun acceptedMindmapHtml(html: String?, mindmap: MindmapData, strictV2: Boolean): String? {
        if (html == null) return null
        val failure = runCatching { validateMindmapHtml(html, mindmap, strictV2 = true) }.exceptionOrNull()
        if (failure == null) return html
        if (!strictV2) return null
        throw failure
    }

    /**
     * Returns the manifest to check the HTML against, or null when a legacy package is being read
     * leniently and has nothing to check against.
     */
    private fun requireVisualManifest(data: MindmapData, strictV2: Boolean): MindmapVisualJson? {
        val declared = data.visualContractVersion
        if (declared == null) {
            if (strictV2) {
                throw ArtifactValidationException("missing-contract: mindmap.json 缺少 visualContractVersion")
            }
            return null
        }
        if (declared != MINDMAP_VISUAL_CONTRACT_VERSION) {
            throw ArtifactValidationException(
                "missing-contract: visualContractVersion 必须为 $MINDMAP_VISUAL_CONTRACT_VERSION，实际 $declared"
            )
        }
        val visual = data.visual
            ?: throw ArtifactValidationException("missing-contract: mindmap.json 缺少 visual 视觉清单")
        validateVisualGeometry(data, visual)
        return visual
    }

    private fun validateVisualGeometry(data: MindmapData, visual: MindmapVisualJson) {
        val canvas = visual.canvas
        requirePositive("canvas.width", canvas.width)
        requirePositive("canvas.height", canvas.height)

        val rects = HashMap<String, Rect>(visual.nodes.size)
        for (node in visual.nodes) {
            if (node.nodeId.isBlank()) {
                throw ArtifactValidationException("topology-mismatch: visual.nodes 存在空 nodeId")
            }
            if (rects.put(node.nodeId, rectOf(node)) != null) {
                throw ArtifactValidationException("topology-mismatch: visual.nodes 重复 nodeId ${node.nodeId}")
            }
        }
        val tree = data.nodeIds()
        val missingNodes = tree - rects.keys
        if (missingNodes.isNotEmpty()) {
            throw ArtifactValidationException("topology-mismatch: visual.nodes 缺少树节点 ${brief(missingNodes)}")
        }
        val extraNodes = rects.keys - tree
        if (extraNodes.isNotEmpty()) {
            throw ArtifactValidationException("topology-mismatch: visual.nodes 含树外节点 ${brief(extraNodes)}")
        }
        for (node in visual.nodes) {
            requirePositive("节点 ${node.nodeId} 的 width", node.width)
            requirePositive("节点 ${node.nodeId} 的 height", node.height)
            requireFinite("节点 ${node.nodeId} 的 x", node.x)
            requireFinite("节点 ${node.nodeId} 的 y", node.y)
            val rect = rects[node.nodeId]!!
            if (rect.left < 0 || rect.top < 0) {
                throw ArtifactValidationException("geometry-invalid: 节点 ${node.nodeId} 越出画布左上角")
            }
            if (rect.right > canvas.width || rect.bottom > canvas.height) {
                throw ArtifactValidationException(
                    "geometry-invalid: 节点 ${node.nodeId} 未完整落在 ${canvas.width}x${canvas.height} 画布内"
                )
            }
        }

        val declaredEdges = visual.edges.map { it.from to it.to }
        if (declaredEdges.size != declaredEdges.toSet().size) {
            throw ArtifactValidationException("topology-mismatch: visual.edges 存在重复边")
        }
        val expectedEdges = data.treeEdges()
        val missingEdges = expectedEdges - declaredEdges
        if (missingEdges.isNotEmpty()) {
            throw ArtifactValidationException("topology-mismatch: visual.edges 缺少父子边 ${briefPairs(missingEdges)}")
        }
        // Self-loops and cross-level shortcuts are not parent→child relations, so both land here.
        val extraEdges = declaredEdges.toSet() - expectedEdges
        if (extraEdges.isNotEmpty()) {
            throw ArtifactValidationException("topology-mismatch: visual.edges 含非父子边 ${briefPairs(extraEdges)}")
        }

        val ids = visual.nodes.map { it.nodeId }
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val a = rects[ids[i]]!!
                val b = rects[ids[j]]!!
                val dx = maxOf(0.0, maxOf(a.left, b.left) - minOf(a.right, b.right))
                val dy = maxOf(0.0, maxOf(a.top, b.top) - minOf(a.bottom, b.bottom))
                val gap = hypot(dx, dy)
                if (gap < MIN_NODE_GAP) {
                    throw ArtifactValidationException(
                        "geometry-invalid: 节点 ${ids[i]} 与 ${ids[j]} 间距 $gap 小于 $MIN_NODE_GAP（重叠或过近）"
                    )
                }
            }
        }
        for ((from, to) in expectedEdges) {
            val a = rects[from]!!
            val b = rects[to]!!
            val distance = hypot(a.centerX - b.centerX, a.centerY - b.centerY)
            if (distance < MIN_PARENT_CHILD_CENTRE_DISTANCE) {
                throw ArtifactValidationException(
                    "geometry-invalid: 父子节点 $from→$to 中心距 $distance 小于 $MIN_PARENT_CHILD_CENTRE_DISTANCE"
                )
            }
        }

        when (data.layout) {
            MindmapLayout.HORIZONTAL -> validateHorizontalLayout(data, rects)
            MindmapLayout.CENTER -> validateCenterLayout(data, canvas, rects)
        }
    }

    /** Strict left→right level progression; a vertical list with decorative lines cannot pass. */
    private fun validateHorizontalLayout(data: MindmapData, rects: Map<String, Rect>) {
        val root = rects[data.root.nodeId]
            ?: throw ArtifactValidationException("geometry-invalid: visual.nodes 缺少根节点 ${data.root.nodeId}")
        for ((id, rect) in rects) {
            if (id != data.root.nodeId && rect.left < root.left) {
                throw ArtifactValidationException("geometry-invalid: HORIZONTAL 布局根节点不是最左节点（$id 更靠左）")
            }
        }
        fun walk(node: MindmapNodeJson) {
            val parent = rects[node.nodeId]!!
            for (child in node.children) {
                val childRect = rects[child.nodeId]!!
                val required = parent.right + MIN_LEVEL_ADVANCE
                if (childRect.left < required) {
                    throw ArtifactValidationException(
                        "geometry-invalid: HORIZONTAL 布局 ${node.nodeId}→${child.nodeId} 未左→右推进（需 x≥$required，实际 ${childRect.left}）"
                    )
                }
                walk(child)
            }
        }
        walk(data.root)
    }

    private fun validateCenterLayout(data: MindmapData, canvas: MindmapCanvasJson, rects: Map<String, Rect>) {
        val root = rects[data.root.nodeId]
            ?: throw ArtifactValidationException("geometry-invalid: visual.nodes 缺少根节点 ${data.root.nodeId}")
        val centreX = canvas.width / 2
        val centreY = canvas.height / 2
        val offX = abs(root.centerX - centreX)
        val offY = abs(root.centerY - centreY)
        if (offX > canvas.width * CENTER_ROOT_TOLERANCE) {
            throw ArtifactValidationException("geometry-invalid: CENTER 根节点横向偏离画布中心 $offX，超过 5%")
        }
        if (offY > canvas.height * CENTER_ROOT_TOLERANCE) {
            throw ArtifactValidationException("geometry-invalid: CENTER 根节点纵向偏离画布中心 $offY，超过 5%")
        }

        val children = data.root.children
        val sides = children.map { sideOf(rects[it.nodeId]!!, centreX) }
        if (children.size >= 2 && (sides.none { it < 0 } || sides.none { it > 0 })) {
            throw ArtifactValidationException("geometry-invalid: CENTER 根有多个直接子节点时必须左右各至少一个")
        }
        children.forEachIndexed { index, child ->
            val side = sides[index]
            val childRect = rects[child.nodeId]!!
            if (side == 0) {
                throw ArtifactValidationException("geometry-invalid: CENTER 子节点 ${child.nodeId} 压在根节点中心线上")
            }
            if (side > 0 && childRect.left < root.right + MIN_LEVEL_ADVANCE) {
                throw ArtifactValidationException("geometry-invalid: CENTER 根→${child.nodeId} 向右推进不足 $MIN_LEVEL_ADVANCE")
            }
            if (side < 0 && childRect.right > root.left - MIN_LEVEL_ADVANCE) {
                throw ArtifactValidationException("geometry-invalid: CENTER 根→${child.nodeId} 向左推进不足 $MIN_LEVEL_ADVANCE")
            }
            requireGrowsOutward(child, side, centreX, rects)
        }
    }

    /** Every descendant stays on its own side of the root's centre line and keeps moving outward. */
    private fun requireGrowsOutward(
        node: MindmapNodeJson,
        side: Int,
        centreX: Double,
        rects: Map<String, Rect>,
    ) {
        val rect = rects[node.nodeId]!!
        if (side > 0 && rect.left < centreX) {
            throw ArtifactValidationException("geometry-invalid: CENTER 右侧后代 ${node.nodeId} 越过根节点中心线")
        }
        if (side < 0 && rect.right > centreX) {
            throw ArtifactValidationException("geometry-invalid: CENTER 左侧后代 ${node.nodeId} 越过根节点中心线")
        }
        for (child in node.children) {
            val childRect = rects[child.nodeId]!!
            if (side > 0 && childRect.left < rect.right + MIN_LEVEL_ADVANCE) {
                throw ArtifactValidationException(
                    "geometry-invalid: CENTER 右侧 ${node.nodeId}→${child.nodeId} 每级向右推进不足 $MIN_LEVEL_ADVANCE"
                )
            }
            if (side < 0 && childRect.right > rect.left - MIN_LEVEL_ADVANCE) {
                throw ArtifactValidationException(
                    "geometry-invalid: CENTER 左侧 ${node.nodeId}→${child.nodeId} 每级向左推进不足 $MIN_LEVEL_ADVANCE"
                )
            }
            requireGrowsOutward(child, side, centreX, rects)
        }
    }

    /** -1 = wholly left of the centre line, +1 = wholly right, 0 = straddling it. */
    private fun sideOf(rect: Rect, centreX: Double): Int = when {
        rect.right <= centreX -> -1
        rect.left >= centreX -> 1
        else -> 0
    }

    private fun parseHtml(html: String): org.jsoup.nodes.Document {
        val size = html.encodeToByteArray().size
        if (size == 0) throw ArtifactValidationException("html-invalid: mindmap.html 为空")
        if (size > MAX_MINDMAP_HTML_BYTES) {
            throw ArtifactValidationException("html-invalid: mindmap.html $size 字节，超过上限 $MAX_MINDMAP_HTML_BYTES")
        }
        return try {
            Jsoup.parse(html)
        } catch (e: Exception) {
            throw ArtifactValidationException("html-invalid: mindmap.html 解析失败: ${e.message?.take(200)}")
        }
    }

    /** Offline / single-file / CSP rules — everything that is not the graph itself. */
    private fun requireHtmlShape(doc: org.jsoup.nodes.Document) {
        for (tag in FORBIDDEN_TAGS) {
            if (doc.select(tag).isNotEmpty()) {
                throw ArtifactValidationException("html-invalid: mindmap.html 含被禁止的 <$tag>")
            }
        }
        val metas = doc.select("meta")
        for (meta in metas) {
            val httpEquiv = meta.attr("http-equiv")
            if (httpEquiv.equals("refresh", ignoreCase = true)) {
                throw ArtifactValidationException("html-invalid: mindmap.html 含 meta refresh")
            }
        }
        val viewport = metas.firstOrNull { it.attr("name").equals("viewport", ignoreCase = true) }
            ?: throw ArtifactValidationException("html-invalid: mindmap.html 缺少 viewport meta")
        val viewportContent = normalize(viewport.attr("content"))
        if (!viewportContent.contains("width=device-width") || !viewportContent.contains("initial-scale=1")) {
            throw ArtifactValidationException(
                "html-invalid: viewport 必须是 width=device-width,initial-scale=1，实际 '$viewportContent'"
            )
        }
        val csp = metas.firstOrNull { it.attr("http-equiv").equals("Content-Security-Policy", ignoreCase = true) }
            ?: throw ArtifactValidationException("html-invalid: mindmap.html 缺少 Content-Security-Policy meta")
        val policy = normalize(csp.attr("content"))
        if (!policy.contains(REQUIRED_CSP)) {
            throw ArtifactValidationException("html-invalid: CSP 必须包含 $REQUIRED_CSP，实际 '$policy'")
        }
        if (policy.contains("http:") || policy.contains("https:") || policy.contains("*")) {
            throw ArtifactValidationException("html-invalid: CSP 放开了外部来源：'$policy'")
        }
        // Single file: no linked stylesheet, no external script, no CSS import.
        if (doc.select("link[href]").isNotEmpty()) {
            throw ArtifactValidationException("html-invalid: mindmap.html 必须是单文件，不得含 <link href>")
        }
        if (doc.select("script[src]").isNotEmpty()) {
            throw ArtifactValidationException("html-invalid: mindmap.html 必须是单文件，不得含 <script src>")
        }
        if (!containsTextSizeAdjust(doc)) {
            // Android boosts page text unless the page pins this down itself (ZLQ-140 D5).
            throw ArtifactValidationException("html-invalid: mindmap.html 必须固定 -webkit-text-size-adjust:100%")
        }
        for (el in doc.allElements) {
            for (attr in el.attributes()) {
                if (attr.key.lowercase() !in URL_ATTRS) continue
                for (candidate in attr.value.split(",")) {
                    externalRef(candidate.substringBefore(' '))?.let {
                        throw ArtifactValidationException("html-invalid: mindmap.html 引用了外部资源 ${attr.key}=$it")
                    }
                }
            }
            val inlineStyle = el.attr("style")
            if (inlineStyle.isNotBlank()) requireNoCssUrl(inlineStyle)
        }
        for (style in doc.select("style")) requireNoCssUrl(style.data())
    }

    private fun containsTextSizeAdjust(doc: org.jsoup.nodes.Document): Boolean {
        val haystack = buildString {
            append(doc.html())
            doc.select("style").forEach { append(it.data()) }
        }.replace(Regex("\\s+"), "")
        return haystack.contains("-webkit-text-size-adjust:100%")
    }

    private fun requireNoCssUrl(css: String) {
        if (css.contains("@import")) {
            throw ArtifactValidationException("html-invalid: mindmap.html 必须是单文件，CSS 不得含 @import")
        }
        for (match in CSS_URL.findAll(css)) {
            externalRef(match.groupValues[1])?.let {
                throw ArtifactValidationException("html-invalid: mindmap.html 的 CSS 引用了外部资源 url($it)")
            }
        }
    }

    /** The reference string when [value] points off-page, otherwise null. */
    private fun externalRef(value: String?): String? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty() || v.startsWith("#")) return null
        if (v.startsWith("//")) return v
        val scheme = URL_SCHEME.find(v)?.groupValues?.get(1)?.lowercase() ?: return null
        return if (scheme in ALLOWED_URL_SCHEMES) null else v
    }

    /** Root SVG, node set, edge set, coordinates and toggle structure must agree with the JSON. */
    private fun requireSvgGraph(doc: org.jsoup.nodes.Document, mindmap: MindmapData, visual: MindmapVisualJson) {
        val svgs = doc.select("svg#ss-mindmap")
        if (svgs.size != 1) {
            throw ArtifactValidationException("html-invalid: 必须含且仅含一个 svg#ss-mindmap，实际 ${svgs.size} 个")
        }
        val svg = svgs.first()!!
        if (svg.attr("data-contract-version") != MINDMAP_VISUAL_CONTRACT_VERSION.toString()) {
            throw ArtifactValidationException(
                "html-invalid: svg#ss-mindmap 的 data-contract-version 必须为 $MINDMAP_VISUAL_CONTRACT_VERSION，" +
                    "实际 '${svg.attr("data-contract-version")}'"
            )
        }
        if (svg.attr("data-layout") != mindmap.layout.name) {
            throw ArtifactValidationException(
                "html-invalid: svg data-layout='${svg.attr("data-layout")}' 与 mindmap.json layout=${mindmap.layout.name} 不一致"
            )
        }
        if (svg.attr("width") != "100%" || svg.attr("height") != "100%") {
            throw ArtifactValidationException("html-invalid: svg#ss-mindmap 必须 width=\"100%\" height=\"100%\"")
        }
        if (attrIgnoreCase(svg, "preserveAspectRatio") != "xMidYMid meet") {
            throw ArtifactValidationException(
                "html-invalid: svg#ss-mindmap 必须 preserveAspectRatio=\"xMidYMid meet\"，实际 '${attrIgnoreCase(svg, "preserveAspectRatio")}'"
            )
        }
        val viewBox = attrIgnoreCase(svg, "viewBox").trim().split(Regex("\\s+"))
        if (viewBox.size != 4 || viewBox[0].toDoubleOrNull() != 0.0 || viewBox[1].toDoubleOrNull() != 0.0 ||
            viewBox[2].toDoubleOrNull() != visual.canvas.width || viewBox[3].toDoubleOrNull() != visual.canvas.height
        ) {
            throw ArtifactValidationException(
                "html-invalid: svg viewBox 必须是 0 0 ${visual.canvas.width} ${visual.canvas.height}，实际 '${attrIgnoreCase(svg, "viewBox")}'"
            )
        }

        val nodes = scoped(svg, doc, NODE_SELECTOR, ".mindmap-node")
        val domIds = nodes.map { it.attr("data-node-id") }
        if (domIds.size != domIds.toSet().size) {
            throw ArtifactValidationException("html-invalid: .mindmap-node 的 data-node-id 存在重复")
        }
        val expectedIds = mindmap.nodeIds()
        val missingIds = expectedIds - domIds
        if (missingIds.isNotEmpty()) {
            throw ArtifactValidationException("topology-mismatch: mindmap.html 缺少节点 ${brief(missingIds)}")
        }
        val extraIds = domIds.toSet() - expectedIds
        if (extraIds.isNotEmpty()) {
            throw ArtifactValidationException("topology-mismatch: mindmap.html 含 JSON 之外的节点 ${brief(extraIds)}")
        }

        val edges = scoped(svg, doc, EDGE_SELECTOR, ".mindmap-edge")
        val domEdges = edges.map { it.attr("data-from") to it.attr("data-to") }
        if (domEdges.size != domEdges.toSet().size) {
            throw ArtifactValidationException("html-invalid: .mindmap-edge 存在重复边")
        }
        val expectedEdges = mindmap.treeEdges()
        val missingEdges = expectedEdges - domEdges
        if (missingEdges.isNotEmpty()) {
            throw ArtifactValidationException("topology-mismatch: mindmap.html 缺少分支线 ${briefPairs(missingEdges)}")
        }
        val extraEdges = domEdges.toSet() - expectedEdges
        if (extraEdges.isNotEmpty()) {
            throw ArtifactValidationException("topology-mismatch: mindmap.html 含 JSON 之外的分支线 ${briefPairs(extraEdges)}")
        }
        for (edge in edges) {
            if (!edge.tagName().equals("path", ignoreCase = true)) {
                throw ArtifactValidationException(
                    "html-invalid: .mindmap-edge ${edge.attr("data-from")}→${edge.attr("data-to")} 不是 SVG path 元素"
                )
            }
            if (edge.attr("d").isBlank()) {
                throw ArtifactValidationException(
                    "html-invalid: .mindmap-edge ${edge.attr("data-from")}→${edge.attr("data-to")} 的 path d 为空"
                )
            }
        }

        val nodeById = mindmap.flatten().map { it.second }.associateBy { it.nodeId }
        val visualById = visual.nodes.associateBy { it.nodeId }
        for (el in nodes) {
            val id = el.attr("data-node-id")
            val node = nodeById[id] ?: continue
            val text = normalize(el.text())
            if (!text.contains(normalize(node.label))) {
                throw ArtifactValidationException("html-invalid: 节点 $id 未显示 label '${node.label}'")
            }
            node.explanation?.takeIf { it.isNotBlank() }?.let {
                if (!text.contains(normalize(it))) {
                    throw ArtifactValidationException("html-invalid: 节点 $id 未显示 explanation")
                }
            }
            for (cid in node.citationIds) {
                if (!text.contains(cid)) {
                    throw ArtifactValidationException("html-invalid: 节点 $id 未标注 citationId $cid")
                }
            }
            val declared = visualById[id] ?: continue
            requireCoordinate(el, id, "data-x", declared.x)
            requireCoordinate(el, id, "data-y", declared.y)
            requireCoordinate(el, id, "data-width", declared.width)
            requireCoordinate(el, id, "data-height", declared.height)
            if (node.children.isNotEmpty() && el.select(TOGGLE_SELECTOR).isEmpty()) {
                throw ArtifactValidationException(
                    "html-invalid: 非叶节点 $id 缺少 button[data-action=toggle][aria-expanded]"
                )
            }
        }

        // The title belongs to the native header; a second one in the page is the triple-title defect.
        if (doc.select(HEADING_SELECTOR).size != svg.select(HEADING_SELECTOR).size) {
            throw ArtifactValidationException("html-invalid: svg#ss-mindmap 之外不得输出大标题/副标题")
        }
        // A document-flow outline is exactly the failure this contract exists to reject.
        if (doc.select(LIST_SELECTOR).size != svg.select(LIST_SELECTOR).size) {
            throw ArtifactValidationException("html-invalid: svg#ss-mindmap 之外不得出现 ul/ol/li —— 文档流列表不得充当导图")
        }
        for (list in svg.select("ul, ol")) {
            if (list.parents().none { it.hasClass("mindmap-node") }) {
                throw ArtifactValidationException("html-invalid: ul/ol 只能出现在单个 .mindmap-node 内容内部")
            }
        }
    }

    /**
     * Selects [selector] inside the graph and refuses the page when the same selector also matches
     * outside it — a node or edge the SVG does not own is not part of the map.
     */
    private fun scoped(
        svg: Element,
        doc: org.jsoup.nodes.Document,
        selector: String,
        label: String,
    ): List<Element> {
        val inside = svg.select(selector)
        val anywhere = doc.select(selector)
        if (inside.size != anywhere.size) {
            throw ArtifactValidationException("html-invalid: 存在 svg#ss-mindmap 之外的 $label")
        }
        // An element carrying the class but missing a required data attribute is malformed, not absent.
        val loose = doc.select(label).size
        if (loose != anywhere.size) {
            throw ArtifactValidationException("html-invalid: 存在缺少 data 属性的 $label 元素")
        }
        return inside
    }

    private fun requireCoordinate(el: Element, id: String, attr: String, expected: Double) {
        val raw = el.attr(attr)
        val actual = raw.trim().toDoubleOrNull()
            ?: throw ArtifactValidationException("html-invalid: 节点 $id 缺少或非法的 $attr（'$raw'）")
        if (abs(actual - expected) > COORD_EPSILON) {
            throw ArtifactValidationException(
                "html-invalid: 节点 $id 的 $attr=$actual 与 mindmap.json 视觉清单 $expected 不一致"
            )
        }
    }

    /** jsoup lowercases HTML attribute names but restores SVG ones; accept either spelling. */
    private fun attrIgnoreCase(el: Element, name: String): String =
        el.attr(name).ifBlank { el.attr(name.lowercase()) }

    private fun requirePositive(field: String, value: Double) {
        if (!value.isFinite() || value <= 0) {
            throw ArtifactValidationException("geometry-invalid: $field 必须是有限正数，实际 $value")
        }
    }

    private fun requireFinite(field: String, value: Double) {
        if (!value.isFinite()) {
            throw ArtifactValidationException("geometry-invalid: $field 必须是有限数，实际 $value")
        }
    }

    private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun brief(ids: Collection<String>): String = ids.take(3).joinToString(", ")

    private fun briefPairs(pairs: Collection<Pair<String, String>>): String =
        pairs.take(3).joinToString(", ") { "${it.first}→${it.second}" }

    private class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val centerX: Double get() = (left + right) / 2
        val centerY: Double get() = (top + bottom) / 2
    }

    private fun rectOf(node: MindmapVisualNodeJson) =
        Rect(node.x, node.y, node.x + node.width, node.y + node.height)

    private val NODE_SELECTOR = ".mindmap-node[data-node-id]"
    private val EDGE_SELECTOR = ".mindmap-edge[data-from][data-to]"
    private val TOGGLE_SELECTOR = "button[data-action=toggle][aria-expanded]"
}
