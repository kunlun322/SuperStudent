package com.superstudent.core.model

import kotlinx.serialization.Serializable

// ---------- Unified envelope (design §5) ----------

@Serializable
data class EnvelopeMeta(
    val templateId: String? = null,
    val sessionId: String? = null,
    val attempt: Int? = null,
)

@Serializable
data class Envelope<T>(
    val schemaVersion: Int,
    val packageId: String,
    val generatedAt: String? = null,
    val sourceRevision: String? = null,
    val generator: EnvelopeMeta? = null,
    val data: T,
)

// ---------- plan.json ----------

@Serializable
data class PlanTaskJson(
    val taskId: String,
    val type: PlanTaskType,
    val instruction: String,
    val minutes: Int,
)

@Serializable
data class PlanTopicJson(
    val topicId: String,
    val order: Int,
    val title: String,
    val summary: String? = null,
    val difficulty: TopicDifficulty = TopicDifficulty.MEDIUM,
    val priority: TopicPriority = TopicPriority.SHOULD,
    val estimatedMinutes: Int,
    val tasks: List<PlanTaskJson> = emptyList(),
    val prerequisiteTopicIds: List<String> = emptyList(),
    val citationIds: List<String> = emptyList(),
)

@Serializable
data class PlanData(
    val title: String? = null,
    val totalMinutes: Int? = null,
    val goal: LearningGoal? = null,
    val topics: List<PlanTopicJson> = emptyList(),
)

// ---------- cards.json ----------

@Serializable
data class CardImageJson(
    val status: CardImageStatus = CardImageStatus.SKIPPED,
    val path: String? = null,
    val promptHash: String? = null,
    val alt: String? = null,
)

@Serializable
data class FlashCardJson(
    val cardId: String,
    val kind: CardKind = CardKind.DEFINITION,
    val front: String,
    val back: String,
    val hint: String? = null,
    val difficulty: CardDifficulty = CardDifficulty.MEDIUM,
    val tags: List<String> = emptyList(),
    val citationIds: List<String> = emptyList(),
    val image: CardImageJson? = null,
)

@Serializable
data class CardsData(
    val cards: List<FlashCardJson> = emptyList(),
)

// ---------- citations.json ----------

@Serializable
data class CitationLocator(
    val page: Int? = null,
    val slide: Int? = null,
    val paragraph: Int? = null,
    val lineStart: Int? = null,
    val lineEnd: Int? = null,
    val byteStart: Long? = null,
    val byteEnd: Long? = null,
) {
    fun describe(): String = when {
        page != null -> "第 $page 页"
        slide != null -> "第 $slide 页幻灯片"
        paragraph != null -> "第 $paragraph 段"
        lineStart != null && lineEnd != null -> "第 $lineStart-$lineEnd 行"
        lineStart != null -> "第 $lineStart 行"
        else -> "原文定位缺失"
    }
}

@Serializable
data class CitationJson(
    val citationId: String,
    val sourceId: String? = null,
    val sourceFileName: String? = null,
    val sourceSha256: String? = null,
    val locator: CitationLocator? = null,
    val quote: String,
    val chunkId: String? = null,
    val score: Double? = null,
)

@Serializable
data class CitationsData(
    val citations: List<CitationJson> = emptyList(),
)

// ---------- mindmap.json (design §5.4) ----------

@Serializable
data class MindmapNodeJson(
    val nodeId: String,
    val label: String,
    val explanation: String? = null,
    val citationIds: List<String> = emptyList(),
    val children: List<MindmapNodeJson> = emptyList(),
)

@Serializable
data class MindmapArtifactsJson(
    val htmlPath: String? = null,
    val pngPath: String? = null,
)

// ---------- mindmap.json visual contract v2 (ZLQ-140 D1) ----------

/**
 * Coordinates are modelled as Double on purpose: a generator that emits `700.0` for an integral
 * position is describing the same rectangle as one that emits `700`, and rejecting it would fail a
 * package for a formatting difference rather than a contract breach.
 */
@Serializable
data class MindmapCanvasJson(
    val width: Double,
    val height: Double,
)

@Serializable
data class MindmapVisualNodeJson(
    val nodeId: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

@Serializable
data class MindmapVisualEdgeJson(
    val from: String,
    val to: String,
)

@Serializable
data class MindmapVisualJson(
    val canvas: MindmapCanvasJson,
    val nodes: List<MindmapVisualNodeJson> = emptyList(),
    val edges: List<MindmapVisualEdgeJson> = emptyList(),
)

/** The contract version the client's HTML cross-check enforces. */
const val MINDMAP_VISUAL_CONTRACT_VERSION = 2

@Serializable
data class MindmapData(
    val title: String? = null,
    val layout: MindmapLayout = MindmapLayout.CENTER,
    val root: MindmapNodeJson,
    val artifacts: MindmapArtifactsJson? = null,
    /**
     * Nullable so a package published before visual contract v2 still deserializes. A null here
     * marks a legacy package: it is never retroactively judged bad, but its HTML was never
     * accepted either, so the UI must not show it (ZLQ-140 D3.5).
     */
    val visualContractVersion: Int? = null,
    val visual: MindmapVisualJson? = null,
) {
    /** Pre-order walk, parents before children — the expansion order the read-only tree uses. */
    fun flatten(): List<Pair<Int, MindmapNodeJson>> {
        val out = mutableListOf<Pair<Int, MindmapNodeJson>>()
        fun walk(node: MindmapNodeJson, depth: Int) {
            out += depth to node
            node.children.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    /** Every direct parent→child relation in the tree, as an unordered set of pairs. */
    fun treeEdges(): Set<Pair<String, String>> {
        val out = mutableSetOf<Pair<String, String>>()
        fun walk(node: MindmapNodeJson) {
            node.children.forEach { child ->
                out += node.nodeId to child.nodeId
                walk(child)
            }
        }
        walk(root)
        return out
    }

    fun nodeIds(): Set<String> = flatten().map { it.second.nodeId }.toSet()

    /** Nodes with at least one child — exactly the ones that must expose a toggle control. */
    fun nonLeafNodeIds(): Set<String> = flatten()
        .filter { it.second.children.isNotEmpty() }
        .map { it.second.nodeId }
        .toSet()
}

// ---------- deck.manifest.json (design §5.5) ----------

@Serializable
data class DeckSlideJson(
    val slide: Int,
    val title: String,
    val kind: SlideKind,
    val speakerNotes: Boolean = false,
    val citationIds: List<String> = emptyList(),
)

/** Sidecar for the binary deck.pptx — the PPTX itself never carries JSON fields. */
@Serializable
data class DeckManifestData(
    val path: String,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val slideCount: Int,
    val theme: String? = null,
    val slides: List<DeckSlideJson> = emptyList(),
)

// ---------- exercises.json (design §5.6) ----------

@Serializable
data class ExerciseOptionJson(
    val key: String,
    val text: String,
)

@Serializable
data class ExerciseAnswerJson(
    val optionKey: String? = null,
    @Serializable(with = LooseTextSerializer::class) val value: String? = null,
    val tolerance: Double? = null,
) {
    /** Choice answers grade by option key; numeric answers grade within tolerance; else by normalized text. */
    fun isCorrect(given: String): Boolean {
        val actual = given.trim()
        if (actual.isEmpty()) return false
        if (!optionKey.isNullOrBlank()) return optionKey.equals(actual, ignoreCase = true)
        val expected = value?.trim().orEmpty()
        if (expected.isEmpty()) return false
        val expectedNumber = expected.toDoubleOrNull()
        val actualNumber = actual.toDoubleOrNull()
        if (expectedNumber != null && actualNumber != null) {
            val allowed = tolerance?.takeIf { it >= 0 } ?: 0.0
            return kotlin.math.abs(expectedNumber - actualNumber) <= allowed
        }
        return expected.replace("\\s".toRegex(), "").equals(actual.replace("\\s".toRegex(), ""), ignoreCase = true)
    }

    fun describe(): String = when {
        !optionKey.isNullOrBlank() -> optionKey
        !value.isNullOrBlank() -> value
        else -> "—"
    }
}

@Serializable
data class ExerciseJson(
    val exerciseId: String,
    val type: ExerciseType,
    val stem: String,
    val options: List<ExerciseOptionJson> = emptyList(),
    val answer: ExerciseAnswerJson,
    val analysis: String,
    val difficulty: ExerciseDifficulty = ExerciseDifficulty.MEDIUM,
    val knowledgePointIds: List<String> = emptyList(),
    val citationIds: List<String> = emptyList(),
)

@Serializable
data class ExercisesData(
    val exercises: List<ExerciseJson> = emptyList(),
)
