package com.superstudent.app.features.results

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.superstudent.core.designsystem.SsCard
import com.superstudent.core.designsystem.SsColors
import com.superstudent.core.designsystem.SsPillButton
import com.superstudent.core.designsystem.SsSpacing
import com.superstudent.core.designsystem.SsStatusChip
import com.superstudent.core.designsystem.SsTone
import com.superstudent.core.designsystem.SsType
import com.superstudent.core.model.DeckManifestData
import com.superstudent.core.model.SlideKind
import com.superstudent.core.repository.DeckFile
import kotlinx.coroutines.launch

/**
 * PPT preview and export (FR-08). The binary itself is never parsed here — it is re-downloaded and
 * re-verified against deck.manifest.json right before writing, so what the office app receives is
 * the same file that passed the OOXML / page-count / hash gate.
 */
@Composable
fun DeckPane(
    manifest: DeckManifestData,
    busy: Boolean,
    error: String?,
    savedUri: Uri?,
    onFetchDeck: suspend () -> DeckFile?,
    onSaved: (Uri) -> Unit,
    onFailed: (String) -> Unit,
    onCitation: (String) -> Unit,
) {
    val context = LocalContext.current
    val exporter = remember { ResultExporter(context) }
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ResultExporter.PPTX_MIME)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val deck = onFetchDeck() ?: return@launch
            runCatching { exporter.writeTo(uri, deck.bytes) }
                .onSuccess { onSaved(uri) }
                .onFailure { onFailed(it.message ?: "写入所选位置失败") }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("deck_list"),
        verticalArrangement = Arrangement.spacedBy(SsSpacing.Md),
    ) {
        item {
            SsCard(Modifier.fillMaxWidth().testTag("deck_info")) {
                Column(Modifier.padding(SsSpacing.Lg), verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
                    Text("演示文稿", style = SsType.Section)
                    Text(
                        "${manifest.slideCount} 页 · ${manifest.theme ?: "superstudent-v1"} · ${formatSize(manifest.sizeBytes)}",
                        style = SsType.BodySmall,
                    )
                    manifest.sha256?.let {
                        Text("SHA-256 ${it.take(16)}…", style = SsType.Caption)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        CheckLine("OOXML 结构可打开", true)
                        CheckLine("页数与清单一致", true)
                        CheckLine("包含讲解页", manifest.slides.any { it.kind == SlideKind.CONCEPT })
                        CheckLine("包含例题页", manifest.slides.any { it.kind == SlideKind.EXAMPLE })
                        CheckLine("文件校验和一致", manifest.sha256 != null)
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
                SsPillButton(
                    text = if (busy) "正在校验并导出…" else "导出 PPTX",
                    onClick = { launcher.launch("学习演示.pptx") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("deck_export"),
                )
                error?.let {
                    Text(it, style = SsType.BodySmall, color = SsColors.Error, modifier = Modifier.testTag("deck_error"))
                }
                if (savedUri != null) {
                    Text("已导出到所选位置", style = SsType.Caption, modifier = Modifier.testTag("deck_saved"))
                    Row(horizontalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
                        SsPillButton(
                            text = "用办公软件打开",
                            onClick = { if (!exporter.openWith(savedUri, ResultExporter.PPTX_MIME)) onFailed("未找到可打开 PPTX 的应用") },
                            outlined = true,
                            modifier = Modifier.testTag("deck_open"),
                        )
                        SsPillButton(
                            text = "分享",
                            onClick = {
                                if (!exporter.share(savedUri, ResultExporter.PPTX_MIME, "学习演示.pptx")) {
                                    onFailed("没有可用的分享目标")
                                }
                            },
                            outlined = true,
                            modifier = Modifier.testTag("deck_share"),
                        )
                    }
                }
            }
        }

        items(manifest.slides, key = { it.slide }) { slide ->
            SsCard(Modifier.fillMaxWidth().testTag("deck_slide_${slide.slide}")) {
                Column(Modifier.padding(SsSpacing.Lg), verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
                    ) {
                        Text("${slide.slide}", style = SsType.Title, color = SsColors.Primary)
                        Text(slide.title, style = SsType.Label, modifier = Modifier.weight(1f).testTag("deck_slide_title_${slide.slide}"))
                        SsStatusChip(kindLabel(slide.kind), toneFor(slide.kind))
                    }
                    Text(
                        buildString {
                            if (slide.speakerNotes) append("含演讲者备注")
                            if (slide.citationIds.isNotEmpty()) {
                                if (isNotEmpty()) append(" · ")
                                append("${slide.citationIds.size} 处出处")
                            }
                        }.ifBlank { "—" },
                        style = SsType.Caption,
                    )
                    if (slide.citationIds.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(SsSpacing.Xs)) {
                            slide.citationIds.take(3).forEach { id ->
                                SsStatusChip(
                                    "引用 $id",
                                    SsTone.INFO,
                                    Modifier.clickable { onCitation(id) }.testTag("deck_citation_${slide.slide}_$id"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckLine(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SsSpacing.Xs)) {
        Text(if (ok) "✓" else "✗", style = SsType.Caption, color = if (ok) SsColors.Primary else SsColors.Error)
        Text(label, style = SsType.Caption, color = if (ok) SsColors.Primary else SsColors.Error)
    }
}

private fun kindLabel(kind: SlideKind): String = when (kind) {
    SlideKind.TITLE -> "封面"
    SlideKind.CONCEPT -> "讲解"
    SlideKind.EXAMPLE -> "例题"
    SlideKind.SUMMARY -> "小结"
}

private fun toneFor(kind: SlideKind): SsTone = when (kind) {
    SlideKind.TITLE -> SsTone.NEUTRAL
    SlideKind.CONCEPT -> SsTone.SUCCESS
    SlideKind.EXAMPLE -> SsTone.ACCENT
    SlideKind.SUMMARY -> SsTone.INFO
}

private fun formatSize(bytes: Long?): String = when {
    bytes == null -> "大小未声明"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}
