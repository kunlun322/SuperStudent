package com.superstudent.app.features.results

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.superstudent.core.designsystem.SsCard
import com.superstudent.core.designsystem.SsColors
import com.superstudent.core.designsystem.SsPillButton
import com.superstudent.core.designsystem.SsShapes
import com.superstudent.core.designsystem.SsSpacing
import com.superstudent.core.designsystem.SsStatusChip
import com.superstudent.core.designsystem.SsTone
import com.superstudent.core.designsystem.SsType
import com.superstudent.core.model.MindmapData
import com.superstudent.core.model.MindmapLayout
import com.superstudent.core.model.MindmapNodeJson
import java.io.File
import kotlinx.coroutines.delay

/**
 * Read-only mindmap (FR-07). The graphic comes from mindmap.html in a hardened offline WebView on
 * every supported API level, and falls back to mindmap.png — then to the structure tree — only when
 * the WebView fails at runtime (ZLQ-145 ruling, option 2, replacing ZLQ-140 D6).
 *
 * There is deliberately no OS-version branch here. An Android level is not a reliable proxy for
 * WebView capability: the provider updates independently, so an API 28 device may render the
 * interactive map perfectly and an API 34 device may have no provider at all. What decides is the
 * positive handshake in [MindmapWebView], not the API level.
 *
 * The tree below the graphic is built from mindmap.json, which is what makes AC-02 unconditional:
 * node click → explanation + Citation with locator, independent of whether any binary arrived, and
 * independent of which render path the graphic took.
 */
@Composable
fun MindmapPane(
    data: MindmapData,
    html: String?,
    htmlError: String?,
    png: File?,
    loading: Boolean,
    onCitation: (String) -> Unit,
) {
    var expanded by remember(data.root.nodeId) { mutableStateOf(setOf(data.root.nodeId)) }
    val nodes = remember(data.root, expanded) { visibleNodes(data.root, expanded) }

    val controller = remember { MindmapRenderController() }
    val expectation = remember(data) { data.renderExpectation() }
    // Binding during composition is what lets the first frame already know whether HTML is in play;
    // the generation is the token every WebView callback must carry, so a document change, a retry
    // or leaving the page all invalidate the callbacks and the timer of the previous one.
    var generation by remember(html) { mutableStateOf(controller.bind(html)) }
    var render by remember(html) { mutableStateOf(controller.state) }
    DisposableEffect(controller) {
        controller.onStateChanged = { render = it }
        onDispose {
            controller.onStateChanged = null
            controller.release()
        }
    }
    // The 8-second deadline, armed per generation. Compose cancels this on a generation change and
    // on disposal, and `onTick` re-checks the elapsed time, so a render that already succeeded or
    // already fell back cannot be degraded again by a late tick.
    LaunchedEffect(generation) {
        delay(MINDMAP_HTML_RENDER_TIMEOUT_MS)
        controller.onTick()
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("mindmap_tree"),
        verticalArrangement = Arrangement.spacedBy(SsSpacing.Md),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        data.title ?: "思维导图",
                        style = SsType.Section,
                        modifier = Modifier.weight(1f).testTag("mindmap_title"),
                    )
                    SsStatusChip(
                        if (data.layout == MindmapLayout.HORIZONTAL) "横向布局" else "中心布局",
                        SsTone.NEUTRAL,
                    )
                }
                Text("点击任意节点可查看讲解与原文出处", style = SsType.Caption)
            }
        }

        item {
            // D5: the graph viewport follows the container width but stays bounded, so a wide window
            // cannot stretch this item far enough to crowd out the structure tree below it.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val visualHeight = maxWidth.coerceIn(360.dp, 560.dp)
                SsCard(Modifier.fillMaxWidth().height(visualHeight).testTag("mindmap_visual")) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when (val current = render) {
                            is MindmapRenderState.LoadingHtml, is MindmapRenderState.HtmlReady -> {
                                if (html != null) {
                                    // One session per generation: `key` discards the old WebView,
                                    // its pending probes and its callbacks instead of letting them
                                    // outlive the document they belonged to.
                                    key(generation) {
                                        MindmapWebView(
                                            html = html,
                                            expectation = expectation,
                                            onReady = { controller.onHtmlReady(generation) },
                                            onRenderFailure = { controller.onFailure(generation, it) },
                                            modifier = Modifier.fillMaxSize().testTag("mindmap_web"),
                                        )
                                    }
                                }
                                // The WebView stays composed and laying out while it handshakes —
                                // the probe measures real geometry, so it cannot run against a view
                                // that is not attached — but an unpainted surface is never shown.
                                if (current is MindmapRenderState.LoadingHtml) RenderPlaceholder()
                            }
                            is MindmapRenderState.Fallback -> if (png != null) {
                                MindmapPng(png)
                            } else {
                                DegradedNotice(current.reason)
                            }
                            MindmapRenderState.NoHtml -> when {
                                loading -> RenderPlaceholder()
                                png != null -> MindmapPng(png)
                                else -> Text(
                                    htmlError ?: "暂无图形版本，可继续使用下方结构树",
                                    style = SsType.Caption,
                                    modifier = Modifier.padding(SsSpacing.Lg).testTag("mindmap_visual_empty"),
                                )
                            }
                        }
                    }
                }
            }
        }

        // The manual escape hatch. It exists for the one thing no probe can see — every DOM and
        // visual-state check passing while the system compositor still paints nothing — so it is
        // offered only when there is a static image to escape to. It is a per-document operator
        // choice, never an SDK-based preselection.
        if (html != null && png != null && render !is MindmapRenderState.NoHtml) {
            item {
                val fallback = render as? MindmapRenderState.Fallback
                Column(verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
                    if (fallback != null) {
                        Text(
                            fallback.reason?.let { "交互导图加载失败（${it.name}），已改用静态图；下方结构树不受影响" }
                                ?: "已按你的选择显示静态图；下方结构树不受影响",
                            style = SsType.Caption,
                            modifier = Modifier.testTag("mindmap_fallback_notice"),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().testTag("mindmap_render_choice"),
                        horizontalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
                    ) {
                        if (fallback == null) {
                            SsPillButton(
                                text = "显示静态图",
                                outlined = true,
                                onClick = { controller.requestStaticImage() },
                                modifier = Modifier.testTag("mindmap_show_static"),
                            )
                        } else {
                            SsPillButton(
                                text = "重试交互导图",
                                outlined = true,
                                onClick = { generation = controller.retry() },
                                modifier = Modifier.testTag("mindmap_retry_html"),
                            )
                        }
                    }
                }
            }
        }

        if (html == null && htmlError != null && png != null) {
            item { Text(htmlError, style = SsType.Caption, color = SsColors.Error) }
        }

        items(nodes, key = { it.second.nodeId }) { (depth, node, hasChildren) ->
            val isOpen = node.nodeId in expanded
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = (depth * 16).dp)
                    .testTag("mindmap_node_${node.nodeId}"),
            ) {
                // SsCard's content slot is Box-scoped, so the header row and the expanded body
                // must share one Column or they paint on top of each other.
                SsCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = node.citationIds.isNotEmpty()) {
                                    onCitation(node.citationIds.first())
                                }
                                .padding(SsSpacing.Md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
                        ) {
                            Box(
                                Modifier
                                    .size(SsSpacing.Xl)
                                    .clickable(enabled = hasChildren) {
                                        expanded = if (isOpen) expanded - node.nodeId else expanded + node.nodeId
                                    }
                                    .testTag("mindmap_expand_${node.nodeId}"),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (hasChildren) {
                                    Text(
                                        if (isOpen) "−" else "+",
                                        style = SsType.Label,
                                        color = SsColors.Primary,
                                    )
                                } else {
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .background(SsColors.Accent, SsShapes.Pill),
                                    )
                                }
                            }
                            Text(
                                node.label,
                                style = if (depth == 0) SsType.Label else SsType.BodySmall,
                                color = if (depth == 0) SsColors.Primary else SsType.BodySmall.color,
                                modifier = Modifier.weight(1f).testTag("mindmap_label_${node.nodeId}"),
                            )
                            if (node.citationIds.isNotEmpty()) {
                                Text("${node.citationIds.size} 处出处", style = SsType.Caption)
                            }
                        }
                        if (isOpen) {
                            Column(
                                Modifier.fillMaxWidth().padding(start = SsSpacing.Md, end = SsSpacing.Md, bottom = SsSpacing.Md),
                                verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
                            ) {
                                node.explanation?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = SsType.BodySmall, modifier = Modifier.testTag("mindmap_explanation_${node.nodeId}"))
                                }
                                if (node.citationIds.isNotEmpty()) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(SsSpacing.Xs),
                                    ) {
                                        node.citationIds.take(3).forEach { id ->
                                            SsStatusChip(
                                                "引用 $id",
                                                SsTone.INFO,
                                                Modifier
                                                    .clickable { onCitation(id) }
                                                    .testTag("mindmap_citation_${node.nodeId}_$id"),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Covers the WebView until the handshake completes. The WebView is composed and laying out behind
 * this — the probe measures real geometry, so it cannot run against a view that is not attached —
 * but an unpainted or half-painted surface is never shown to the student.
 */
@Composable
private fun RenderPlaceholder() {
    Box(
        Modifier.fillMaxSize().background(SsColors.Surface).testTag("mindmap_web_loading"),
        contentAlignment = Alignment.Center,
    ) {
        Text("正在渲染交互导图…", style = SsType.Caption)
    }
}

/**
 * The explicit degradation notice for a package with no PNG. The ruling requires the card to say so
 * rather than sit blank, and the structure tree below stays available either way.
 */
@Composable
private fun DegradedNotice(reason: MindmapRenderFailure?) {
    Text(
        reason?.let { "交互导图加载失败（${it.name}），本包无静态图，请继续使用下方结构树" }
            ?: "交互导图当前不可用，本包无静态图，请继续使用下方结构树",
        style = SsType.Caption,
        color = SsColors.Error,
        modifier = Modifier.padding(SsSpacing.Lg).testTag("mindmap_visual_empty"),
    )
}

@Composable
private fun MindmapPng(png: File) {
    AsyncImage(
        model = png,
        contentDescription = "思维导图",
        modifier = Modifier.fillMaxSize().testTag("mindmap_png"),
        contentScale = ContentScale.Fit,
    )
}

/**
 * What the probe is told to look for. The accepted manifest is the source of truth; a package that
 * predates visual contract v2 has none, and for those the tree counts are what the DOM contract
 * requires anyway — and its HTML was never accepted, so the probe rejects it and the pane falls
 * back to the PNG or the tree.
 */
private fun MindmapData.renderExpectation(): MindmapExpectation {
    val declared = visual
    val flat = flatten()
    return MindmapExpectation(
        nodeCount = declared?.nodes?.size ?: flat.size,
        edgeCount = declared?.edges?.size ?: treeEdges().size,
        hasNonLeafNodes = flat.any { it.second.children.isNotEmpty() },
    )
}

/** Parents before children; a node is listed only when every ancestor is expanded. */
private fun visibleNodes(
    root: MindmapNodeJson,
    expanded: Set<String>,
): List<Triple<Int, MindmapNodeJson, Boolean>> {
    val out = mutableListOf<Triple<Int, MindmapNodeJson, Boolean>>()
    fun walk(node: MindmapNodeJson, depth: Int) {
        val hasChildren = node.children.isNotEmpty()
        out += Triple(depth, node, hasChildren)
        if (!hasChildren || node.nodeId !in expanded) return
        node.children.forEach { walk(it, depth + 1) }
    }
    walk(root, 0)
    return out
}
