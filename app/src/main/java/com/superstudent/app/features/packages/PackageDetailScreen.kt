package com.superstudent.app.features.packages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superstudent.app.Routes
import com.superstudent.app.container
import com.superstudent.app.features.packages.upload.SourceAccess
import com.superstudent.app.features.packages.upload.UploadProgressBus
import com.superstudent.app.features.tasks.QmindDeleter
import com.superstudent.app.features.tasks.TaskForegroundService
import com.superstudent.app.ssViewModel
import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.database.TaskRunEntity
import com.superstudent.core.designsystem.SsCard
import com.superstudent.core.designsystem.SsColors
import com.superstudent.core.designsystem.SsPillButton
import com.superstudent.core.designsystem.SsProgressRing
import com.superstudent.core.designsystem.SsShapes
import com.superstudent.core.designsystem.SsSpacing
import com.superstudent.core.designsystem.SsStatusChip
import com.superstudent.core.designsystem.SsTone
import com.superstudent.core.designsystem.SsType
import com.superstudent.core.model.Credits
import com.superstudent.core.model.TaskState
import com.superstudent.core.upload.SourceAction
import com.superstudent.core.upload.SourceRowPresenter
import com.superstudent.core.upload.SourceRowTone
import java.time.Duration
import java.time.Instant

@Composable
fun PackageDetailScreen(
    navController: androidx.navigation.NavHostController,
    packageId: String,
) {
    val context = LocalContext.current
    val viewModel: PackageDetailViewModel = ssViewModel { PackageDetailViewModel(it) }
    LaunchedEffect(packageId) { viewModel.bind(packageId) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val startError by viewModel.startError.collectAsStateWithLifecycle()
    val deleteState by viewModel.deleteState.collectAsStateWithLifecycle()
    val deletingIds by viewModel.deletingIds.collectAsStateWithLifecycle()
    val pendingDeleteIds by viewModel.pendingDeleteIds.collectAsStateWithLifecycle()
    val uploadNotice by viewModel.uploadNotice.collectAsStateWithLifecycle()
    val uploadTicks by UploadProgressBus.ticks.collectAsStateWithLifecycle()
    var showTextDialog by remember { mutableStateOf(false) }
    var reselectTarget by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.enqueueUpload(context, uris, null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris -> viewModel.enqueueUpload(context, uris, null) }

    val reselectPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = reselectTarget
        reselectTarget = null
        if (uri != null && target != null) viewModel.reselectSource(context, target, uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(SsSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(SsSpacing.Md),
    ) {
        val pkg = ui.pkg
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(pkg?.title ?: "学习包", style = SsType.Section, modifier = Modifier.weight(1f).testTag("detail_title"))
            Text(
                "返回",
                style = SsType.Label,
                color = SsColors.Primary,
                modifier = Modifier
                    .clickable(onClick = { navController.popBackStack() })
                    .testTag("detail_back"),
            )
        }
        if (pkg != null) {
            Text(
                goalLabel(pkg.goal) + pkg.chapterRange?.let { " · $it" }.orEmpty(),
                style = SsType.BodySmall,
            )
        }

        RunStatusCard(
            ui = ui,
            onCancel = { viewModel.requestCancel(context) },
            onRetry = { viewModel.startGeneration(context) },
            onViewResults = { navController.navigate(Routes.results(packageId)) },
        )

        if (startError != null) {
            Text(startError.orEmpty(), style = SsType.BodySmall, color = SsColors.Error,
                modifier = Modifier.testTag("detail_error"))
        }

        uploadNotice?.let { notice ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    notice,
                    style = SsType.Caption,
                    color = SsColors.Error,
                    modifier = Modifier.weight(1f).testTag("source_notice"),
                )
                Text(
                    "关闭",
                    style = SsType.Label,
                    color = SsColors.Primary,
                    modifier = Modifier
                        .clickable(onClick = viewModel::clearUploadNotice)
                        .testTag("source_notice_dismiss"),
                )
            }
        }

        deleteState?.summary?.let { summary ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    summary,
                    style = SsType.Caption,
                    color = if (deleteState?.outcome == QmindDeleter.Outcome.DELETE_PENDING) SsColors.Error else SsColors.Primary,
                    modifier = Modifier.weight(1f).testTag("delete_summary"),
                )
                if (deleteState?.outcome == QmindDeleter.Outcome.DELETE_PENDING) {
                    Text(
                        "重试删除",
                        style = SsType.Label,
                        color = SsColors.Primary,
                        modifier = Modifier
                            .clickable(onClick = { viewModel.retryPendingDeletes(context) })
                            .testTag("delete_retry"),
                    )
                } else {
                    Text(
                        "关闭",
                        style = SsType.Label,
                        color = SsColors.Primary,
                        modifier = Modifier.clickable(onClick = viewModel::clearDeleteState).testTag("delete_dismiss"),
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
            SsPillButton(
                text = "添加文件",
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.testTag("pick_file_button"),
            )
            SsPillButton(
                text = "添加图片",
                onClick = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.testTag("pick_image_button"),
            )
            SsPillButton(
                text = "粘贴文本",
                onClick = { showTextDialog = true },
                outlined = true,
                modifier = Modifier.testTag("paste_text_button"),
            )
        }
        Text(
            SourceAccess.SUPPORTED_HINT,
            style = SsType.Caption,
            modifier = Modifier.testTag("source_format_hint"),
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
        ) {
            items(ui.sources, key = { it.sourceId }) { source ->
                SourceRow(
                    source = source,
                    deleting = source.sourceId in deletingIds,
                    retrievalPending = source.sourceId in pendingDeleteIds,
                    percent = uploadTicks[source.sourceId]?.percent,
                    onDelete = { viewModel.removeSource(context, source.sourceId) },
                    onRetry = { viewModel.retryUpload(context, source.sourceId) },
                    onReselect = {
                        reselectTarget = source.sourceId
                        reselectPicker.launch(arrayOf("*/*"))
                    },
                    // §5.2: navigation only. A successful login wakes the coordinator with
                    // LOGIN_COMPLETED (MainActivity), which re-claims through the normal path — this
                    // entry must not upload around the claim (C6).
                    onReauthenticate = { navController.navigate(Routes.LOGIN) },
                )
            }
        }
    }

    if (showTextDialog) {
        PasteTextDialog(
            onDismiss = { showTextDialog = false },
            onConfirm = { text ->
                showTextDialog = false
                viewModel.enqueueUpload(context, emptyList(), text.take(SourceAccess.MAX_TEXT_CHARS))
            },
        )
    }
}

@Composable
private fun RunStatusCard(
    ui: PackageDetailUi,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onViewResults: () -> Unit,
) {
    val run = ui.run
    SsCard(Modifier.fillMaxWidth().testTag("run_status_card")) {
        Column(
            Modifier.fillMaxWidth().padding(SsSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
        ) {
            if (run == null) {
                Text("还没有生成记录", style = SsType.Label)
                Text("资料上传完成后即可开始生成学习计划与卡片。", style = SsType.BodySmall)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SsSpacing.Md)) {
                    SsProgressRing(
                        progress = if (ui.isRunning) run.progress / 100f else if (run.state == TaskState.SUCCEEDED.name) 1f else null,
                        modifier = Modifier.testTag("run_progress"),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(stateLabel(run.state), style = SsType.Label, modifier = Modifier.testTag("run_state"))
                        Text(
                            if (ui.isRunning) "阶段：${TaskForegroundService.stageLabel(run.stage)} · ${run.progress}%"
                            else "第 ${run.attempt} 次尝试",
                            style = SsType.BodySmall,
                            modifier = Modifier.testTag("run_stage"),
                        )
                        durationLabel(run)?.let {
                            Text("耗时 $it", style = SsType.Caption, modifier = Modifier.testTag("run_duration"))
                        }
                        if (run.credits > 0) {
                            Text(
                                "已消耗 ${Credits.format(run.credits)} credits",
                                style = SsType.Caption,
                                modifier = Modifier.testTag("run_credits"),
                            )
                        }
                        if (!ui.isRunning && !run.errorMessage.isNullOrBlank()) {
                            Text(
                                run.errorMessage.orEmpty(),
                                style = SsType.BodySmall,
                                color = SsColors.Error,
                                modifier = Modifier.testTag("run_error"),
                            )
                        }
                    }
                }
            }

            if (ui.isRunning && ui.hasResults) {
                Text(
                    "正在生成新版本，当前可查看上一版结果",
                    style = SsType.BodySmall,
                    modifier = Modifier.testTag("stale_results_hint"),
                )
            }

            // ZLQ-114 §5.6 fallback. Not a second set of buttons: the cancel already left `isRunning`
            // false and `canGenerate` true, so 重新生成 below is the same entry as ever. This only says
            // out loud that the package status still on screen is stale and being repaired.
            if (ui.isCanceledGeneratingMismatch) {
                Text(
                    "取消已记录，状态正在修复，可重新生成",
                    style = SsType.BodySmall,
                    modifier = Modifier.testTag("cancel_mismatch_hint"),
                )
            }

            // Deliberately not mutually exclusive: a published result set stays reachable while a new
            // one is generating or after it was cancelled (ZLQ-80).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
            ) {
                if (ui.isRunning) {
                    SsPillButton(
                        text = if (run?.state == TaskState.CANCEL_REQUESTED.name) "取消中…" else "取消生成",
                        onClick = onCancel,
                        outlined = true,
                        enabled = run?.state != TaskState.CANCEL_REQUESTED.name,
                        modifier = Modifier.testTag("cancel_button"),
                    )
                }
                if (ui.hasResults) {
                    SsPillButton(
                        text = if (ui.isRunning) "查看结果（上一版）" else "查看结果",
                        onClick = onViewResults,
                        modifier = Modifier.testTag("view_results_button"),
                    )
                }
                if (!ui.isRunning) {
                    if (ui.hasResults) {
                        SsPillButton(
                            text = "重新生成",
                            onClick = onRetry,
                            outlined = true,
                            enabled = ui.canGenerate,
                            modifier = Modifier.testTag("regenerate_button"),
                        )
                    } else {
                        SsPillButton(
                            text = when {
                                ui.canGenerate -> if (run == null) "开始生成" else "重新生成"
                                ui.blockedByFormat -> "资料格式不支持"
                                else -> "等待资料上传"
                            },
                            onClick = onRetry,
                            enabled = ui.canGenerate,
                            modifier = Modifier.testTag("generate_button"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: SourceAssetEntity,
    deleting: Boolean,
    retrievalPending: Boolean,
    percent: Int?,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onReselect: () -> Unit,
    onReauthenticate: () -> Unit,
) {
    // §5.2: every piece of copy and every entry comes from the presenter, so this composable holds no
    // business rule and never probes a lock. `now` is read per recomposition on purpose — a frozen
    // clock would keep an expired lease reading as 上传中 until something else invalidated the row.
    val row = SourceRowPresenter.of(source, System.currentTimeMillis(), deleting, retrievalPending)
    val chipLabel = if (percent != null && row.tone == SourceRowTone.ACCENT) {
        "${row.stateText} $percent%"
    } else {
        row.stateText
    }
    val id = source.sourceId
    SsCard(Modifier.fillMaxWidth().testTag("source_row_$id")) {
        Column(
            Modifier.fillMaxWidth().padding(SsSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SsSpacing.Xs),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        source.displayName,
                        style = SsType.BodySmall,
                        modifier = Modifier.testTag("source_name_$id"),
                    )
                    Text(
                        (if (source.sizeBytes > 0) "${source.sizeBytes / 1024} KB · " else "") +
                            (if (source.kind == "TEXT") "粘贴文本" else source.mimeType.orEmpty()),
                        style = SsType.Caption,
                    )
                }
                SsStatusChip(
                    chipLabel,
                    tone(row.tone),
                    Modifier
                        // §3.8: the state is read out with the row's id, so a screen-reader user can
                        // tell QA which row said what without a URI or a credential in the string.
                        .semantics { contentDescription = "资料状态：$chipLabel；sourceId=$id" }
                        .testTag("source_state_$id"),
                )
                when {
                    row.busy -> Text(
                        "删除中…",
                        style = SsType.Caption,
                        modifier = Modifier.testTag("source_deleting_$id"),
                    )
                    SourceAction.DELETE in row.actions -> SourceRowAction(
                        label = "删除",
                        description = "删除资料：${source.displayName}；sourceId=$id",
                        testTag = "source_delete_$id",
                        color = SsColors.Error,
                        onClick = onDelete,
                    )
                    // Same tombstone, never a second delete transaction: `beginDelete` reports
                    // "already armed" and the worker is unique-named with `ExistingWorkPolicy.KEEP`.
                    SourceAction.RETRY_DELETE in row.actions -> SourceRowAction(
                        label = "重试删除",
                        description = "重试删除：${source.displayName}；sourceId=$id",
                        testTag = "source_retry_delete_$id",
                        color = SsColors.Error,
                        onClick = onDelete,
                    )
                }
            }

            // FR-01: a failure is written back here, not only into a notification that can be missed.
            // `errorText` carries a stored message only when it differs from the rendered one, so a row
            // written by an older build keeps the only record of why it failed.
            row.errorText?.let { message ->
                Text(
                    message,
                    style = SsType.Caption,
                    color = SsColors.Error,
                    modifier = Modifier.testTag("source_error_$id"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SsSpacing.Md)) {
                if (SourceAction.RETRY_UPLOAD in row.actions) {
                    SourceRowAction(
                        label = "重试",
                        description = "重试上传：${source.displayName}；sourceId=$id",
                        testTag = "source_retry_$id",
                        color = SsColors.Primary,
                        onClick = onRetry,
                    )
                }
                if (SourceAction.RESELECT_FILE in row.actions) {
                    SourceRowAction(
                        label = "重新选择",
                        description = "重新选择资料：${source.displayName}；sourceId=$id",
                        testTag = "source_reselect_$id",
                        color = SsColors.Primary,
                        onClick = onReselect,
                    )
                }
                if (SourceAction.REAUTHENTICATE in row.actions) {
                    SourceRowAction(
                        label = "重新登录",
                        description = "重新登录以上传：${source.displayName}；sourceId=$id",
                        testTag = "source_reauth_$id",
                        color = SsColors.Primary,
                        onClick = onReauthenticate,
                    )
                }
            }
        }
    }
}

/**
 * One recovery entry. §3.8: a 48 dp target, an explicit `contentDescription` and `Role.Button`, because
 * the previous shape was a bare clickable `Text` that TalkBack announced as its own short label with no
 * way to tell which row it belonged to.
 */
@Composable
private fun SourceRowAction(
    label: String,
    description: String,
    testTag: String,
    color: Color,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = SsType.Label,
        color = color,
        modifier = Modifier
            .heightIn(min = SsSpacing.MinTouch)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(testTag),
    )
}

/** Exhaustive on purpose: a tone added to the presenter must be mapped here, not silently dropped. */
private fun tone(tone: SourceRowTone): SsTone = when (tone) {
    SourceRowTone.NEUTRAL -> SsTone.NEUTRAL
    SourceRowTone.ACCENT -> SsTone.ACCENT
    SourceRowTone.SUCCESS -> SsTone.SUCCESS
    SourceRowTone.DANGER -> SsTone.DANGER
}

@Composable
private fun PasteTextDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = SsShapes.Sheet,
        title = { Text("粘贴文本资料") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp).testTag("paste_text_input"),
                    shape = SsShapes.Input,
                    placeholder = { Text("把讲义、笔记或题目粘贴到这里") },
                )
                Spacer(Modifier.height(SsSpacing.Sm))
                Text("${text.length} 字", style = SsType.Caption)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.trim().length >= 20) { Text("上传") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** FR-13: task-level duration, straight from the timestamps task.json already carries. */
internal fun durationLabel(run: TaskRunEntity): String? {
    val start = run.startedAt ?: return null
    val end = run.finishedAt ?: return null
    val seconds = runCatching { Duration.between(Instant.parse(start), Instant.parse(end)).seconds }.getOrNull()
    if (seconds == null || seconds < 0) return null
    return if (seconds < 60) "$seconds 秒" else "${seconds / 60} 分 ${seconds % 60} 秒"
}

private fun stateLabel(state: String): String = when (runCatching { TaskState.valueOf(state) }.getOrNull()) {
    TaskState.QUEUED -> "排队中"
    TaskState.RUNNING -> "生成中"
    TaskState.RETRY_WAIT -> "等待重试"
    TaskState.SUCCEEDED -> "已完成"
    TaskState.FAILED_RETRYABLE -> "失败（可重试）"
    TaskState.FAILED_PERMANENT -> "失败（不可重试）"
    TaskState.CANCEL_REQUESTED -> "取消中"
    TaskState.CANCELED -> "已取消"
    TaskState.UNKNOWN -> "状态未知，需关注"
    TaskState.AUTH_EXPIRED -> "登录已失效"
    TaskState.ACCESS_DENIED -> "无权限"
    TaskState.IDENTITY_INVALID -> "账号异常"
    null -> state
}
