package com.superstudent.app.features.results

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.superstudent.app.ssViewModel
import com.superstudent.core.designsystem.SsCard
import com.superstudent.core.designsystem.SsColors
import com.superstudent.core.designsystem.SsPillButton
import com.superstudent.core.designsystem.SsResultTab
import com.superstudent.core.designsystem.SsShapes
import com.superstudent.core.designsystem.SsSpacing
import com.superstudent.core.designsystem.SsStatusChip
import com.superstudent.core.designsystem.SsTone
import com.superstudent.core.designsystem.SsType
import com.superstudent.core.model.CardImageStatus
import com.superstudent.core.model.CardMastery
import com.superstudent.core.model.CitationJson
import com.superstudent.core.model.FlashCardJson
import com.superstudent.core.model.PlanTopicJson
import com.superstudent.core.model.TopicPriority
import java.io.File

private val TABS = listOf("学习计划", "记忆卡片", "导图", "PPT", "习题")

/**
 * The five result entries AC-01 requires. Each pane renders an artifact that already passed strict
 * validation, so this screen has no "coming soon" placeholder and no partial-success path.
 */
@Composable
fun ResultsScreen(
    navController: NavHostController,
    packageId: String,
) {
    val viewModel: ResultsViewModel = ssViewModel { ResultsViewModel(it) }
    LaunchedEffect(packageId) { viewModel.bind(packageId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }
    var citation by remember { mutableStateOf<CitationJson?>(null) }

    // Binaries stay on Drive until the tab that needs them is actually opened (§3.5 step 4).
    LaunchedEffect(tab, state.results) {
        if (state.results == null) return@LaunchedEffect
        when (tab) {
            1 -> viewModel.loadCardImages()
            2 -> viewModel.loadMindmapArtifacts()
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(SsSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(SsSpacing.Md),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("学习结果", style = SsType.Section, modifier = Modifier.weight(1f).testTag("results_title"))
            Text(
                "返回",
                style = SsType.Label,
                color = SsColors.Primary,
                modifier = Modifier.clickable(onClick = { navController.popBackStack() }).testTag("results_back"),
            )
        }

        SsResultTab(
            tabs = TABS,
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.testTag("result_tabs"),
        )

        if (state.qmindHidden) {
            Text(
                "知识库删除尚未确认，相关原文引用已暂时隐藏",
                style = SsType.Caption,
                color = SsColors.Error,
                modifier = Modifier.testTag("qmind_pending"),
            )
        }

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在读取学习结果…", style = SsType.BodySmall, modifier = Modifier.testTag("results_loading"))
            }
            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(SsSpacing.Md)) {
                    Text(state.error.orEmpty(), style = SsType.BodySmall, color = SsColors.Error,
                        modifier = Modifier.testTag("results_error"))
                    SsPillButton(text = "重试", onClick = viewModel::load, modifier = Modifier.testTag("results_retry"))
                }
            }
            else -> {
                val results = state.results
                if (results == null) {
                    Text("暂无结果", style = SsType.BodySmall)
                } else {
                    val onCitation: (String) -> Unit = { id -> results.citationById[id]?.let { citation = it } }
                    when (tab) {
                        0 -> PlanPane(
                            topics = results.plan.topics.sortedBy { it.order },
                            completed = state.completedTopicIds,
                            onCitation = onCitation,
                            onComplete = viewModel::completeTopic,
                        )
                        1 -> CardsPane(
                            cards = results.cards.cards,
                            masteryOf = { state.progress[it]?.mastery ?: CardMastery.NEW.name },
                            reviewedCount = state.progress.values.count { it.reviewCount > 0 },
                            reviewOrder = viewModel.reviewOrderFor(results.cards.cards.map { it.cardId }),
                            imageOf = { state.cardImages[it] },
                            imagesBusy = state.imagesBusy,
                            imagesSkippedOffline = state.imagesSkippedOffline,
                            onImageNeeded = viewModel::loadCardImage,
                            onCitation = onCitation,
                            onAnswer = viewModel::answer,
                        )
                        2 -> MindmapPane(
                            data = results.mindmap,
                            html = state.mindmapHtml,
                            htmlError = state.mindmapHtmlError,
                            png = state.mindmapPng,
                            loading = state.mindmapLoading,
                            onCitation = onCitation,
                        )
                        3 -> DeckPane(
                            manifest = results.deck,
                            busy = state.deckBusy,
                            error = state.deckError,
                            savedUri = state.deckSavedUri,
                            onFetchDeck = viewModel::fetchDeck,
                            onSaved = viewModel::onDeckSaved,
                            onFailed = viewModel::setDeckError,
                            onCitation = onCitation,
                        )
                        else -> ExercisesPane(
                            exercises = results.exercises.exercises,
                            progress = state.exerciseProgress,
                            rePracticeOrder = viewModel.rePracticeOrderFor(results.exercises.exercises.map { it.exerciseId }),
                            onCitation = onCitation,
                            onAnswer = viewModel::answerExercise,
                        )
                    }
                }
            }
        }
    }

    citation?.let { current ->
        AlertDialog(
            onDismissRequest = { citation = null },
            shape = SsShapes.Sheet,
            title = { Text("原文引用", style = SsType.Label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
                    Text(
                        current.locator?.describe() ?: "原文定位缺失",
                        style = SsType.Caption,
                        color = SsColors.Primary,
                        modifier = Modifier.testTag("citation_locator"),
                    )
                    Text(displayQuote(current.quote), style = SsType.BodySmall, modifier = Modifier.testTag("citation_quote"))
                    if (current.sourceFileName != null) {
                        Text("来源：${current.sourceFileName}", style = SsType.Caption, modifier = Modifier.testTag("citation_source"))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { citation = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun PlanPane(
    topics: List<PlanTopicJson>,
    completed: Set<String>,
    onCitation: (String) -> Unit,
    onComplete: (String) -> Unit,
) {
    if (topics.isEmpty()) {
        Text("计划为空", style = SsType.BodySmall, modifier = Modifier.testTag("plan_empty"))
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("plan_list"),
        verticalArrangement = Arrangement.spacedBy(SsSpacing.Md),
    ) {
        items(topics, key = { it.topicId }) { topic ->
            SsCard(Modifier.fillMaxWidth().testTag("plan_topic_${topic.topicId}")) {
                Column(Modifier.padding(SsSpacing.Lg), verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
                        Text("${topic.order}", style = SsType.Title, color = SsColors.Primary)
                        Text(
                            topic.title,
                            style = SsType.Label,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = topic.citationIds.isNotEmpty()) { onCitation(topic.citationIds.first()) }
                                .testTag("topic_title"),
                        )
                        if (topic.topicId in completed) SsStatusChip("已完成", SsTone.SUCCESS)
                    }
                    Text(
                        "${topic.estimatedMinutes} 分钟 · ${priorityLabel(topic.priority)}",
                        style = SsType.Caption,
                    )
                    topic.summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = SsType.BodySmall) }
                    topic.tasks.forEach { task ->
                        Text("· ${task.instruction}（${task.minutes} 分钟）", style = SsType.BodySmall)
                    }
                    if (topic.citationIds.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(SsSpacing.Xs)) {
                            topic.citationIds.take(3).forEach { id ->
                                SsStatusChip(
                                    "引用 $id",
                                    SsTone.INFO,
                                    Modifier.clickable { onCitation(id) }.testTag("topic_citation_$id"),
                                )
                            }
                        }
                    }
                    if (topic.topicId !in completed) {
                        SsPillButton(
                            text = "标记完成",
                            onClick = { onComplete(topic.topicId) },
                            outlined = true,
                            modifier = Modifier.testTag("topic_complete_${topic.topicId}"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CardsPane(
    cards: List<FlashCardJson>,
    masteryOf: (String) -> String,
    reviewedCount: Int,
    reviewOrder: List<String>,
    imageOf: (String) -> File?,
    imagesBusy: Boolean,
    imagesSkippedOffline: Boolean,
    onImageNeeded: (String) -> Unit,
    onCitation: (String) -> Unit,
    onAnswer: (String, Boolean) -> Unit,
) {
    if (cards.isEmpty()) {
        Text("卡片为空", style = SsType.BodySmall, modifier = Modifier.testTag("cards_empty"))
        return
    }
    var reviewMode by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    var flipped by remember { mutableStateOf(false) }

    val ordered = remember(cards, reviewMode, reviewOrder) {
        if (!reviewMode) {
            cards
        } else {
            val byId = cards.associateBy { it.cardId }
            val orderedIds = reviewOrder.filter { byId.containsKey(it) }
            val rest = cards.map { it.cardId }.filterNot { it in orderedIds }
            (orderedIds + rest).mapNotNull { byId[it] }
        }
    }
    val safeIndex = index.coerceIn(0, ordered.lastIndex)
    val card = ordered[safeIndex]

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(SsSpacing.Md)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SsPillButton(
                text = if (reviewMode) "复习模式" else "顺序模式",
                onClick = {
                    reviewMode = !reviewMode
                    index = 0
                    flipped = false
                },
                outlined = !reviewMode,
                modifier = Modifier.testTag("review_mode_toggle"),
            )
            Text(
                "${reviewedCount.coerceAtMost(ordered.size)}/${ordered.size}",
                style = SsType.Label,
                color = SsColors.Primary,
                modifier = Modifier.testTag("cards_progress"),
            )
        }
        Text(
            when {
                imagesSkippedOffline -> "仅在 Wi-Fi 下自动加载配图，可在「我的」中关闭该限制"
                imagesBusy -> "正在加载卡片配图…"
                reviewMode -> "未掌握的卡片优先出现"
                else -> "按生成顺序学习"
            },
            style = SsType.Caption,
            modifier = Modifier.testTag("cards_hint"),
        )

        SsCard(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable { flipped = !flipped }
                .testTag("flashcard"),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(SsSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (flipped) "答案" else "问题",
                        style = SsType.Caption,
                        color = SsColors.Primary,
                    )
                    SsStatusChip(masteryLabel(masteryOf(card.cardId)), toneFor(masteryOf(card.cardId)),
                        Modifier.testTag("card_mastery"))
                }
                Text(
                    if (flipped) card.back else card.front,
                    style = SsType.Body,
                    modifier = Modifier.testTag(if (flipped) "card_back" else "card_front"),
                )
                if (flipped) {
                    // design §6: an image that never arrived must not make the card unusable.
                    val cardImage = card.image
                    // The Wi-Fi gate covers batch prefetch only; opening a card is an explicit
                    // user request for that one picture.
                    LaunchedEffect(card.cardId) {
                        if (cardImage?.status == CardImageStatus.READY && imageOf(card.cardId) == null) {
                            onImageNeeded(card.cardId)
                        }
                    }
                    if (cardImage?.status == CardImageStatus.READY) {
                        val file = imageOf(card.cardId)
                        if (file != null) {
                            AsyncImage(
                                model = file,
                                contentDescription = cardImage.alt ?: "卡片配图",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().height(160.dp).testTag("card_image"),
                            )
                        } else {
                            CardImagePlaceholder(cardImage.alt)
                        }
                    } else if (cardImage?.status == CardImageStatus.FAILED) {
                        CardImagePlaceholder(cardImage.alt)
                    }
                    card.hint?.takeIf { it.isNotBlank() }?.let { Text("提示：$it", style = SsType.Caption) }
                    if (card.citationIds.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(SsSpacing.Xs)) {
                            card.citationIds.take(3).forEach { id ->
                                SsStatusChip("引用 $id", SsTone.INFO,
                                    Modifier.clickable { onCitation(id) }.testTag("card_citation_$id"))
                            }
                        }
                    }
                } else {
                    Text("点击卡片查看答案", style = SsType.Caption)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
            SsPillButton(
                text = "没记住",
                onClick = {
                    onAnswer(card.cardId, false)
                    flipped = false
                    advance(index, ordered.size) { index = it }
                },
                outlined = true,
                modifier = Modifier.testTag("card_wrong"),
            )
            SsPillButton(
                text = "记住了",
                onClick = {
                    onAnswer(card.cardId, true)
                    flipped = false
                    advance(index, ordered.size) { index = it }
                },
                modifier = Modifier.weight(1f).testTag("card_right"),
            )
        }
        if (safeIndex == ordered.lastIndex && reviewedCount >= ordered.size) {
            Text("本轮复习完成", style = SsType.Label, color = SsColors.Primary, modifier = Modifier.testTag("cards_done"))
        }
    }
}

/**
 * Fallback for a missing or rejected ImageGen picture. Drawn rather than an icon asset: the project
 * ships no icon font, and the point is that the card keeps working either way.
 */
@Composable
private fun CardImagePlaceholder(alt: String?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(SsShapes.Input)
            .background(SsColors.PrimaryContainer)
            .padding(SsSpacing.Md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
    ) {
        Canvas(Modifier.size(40.dp).testTag("card_image_placeholder")) {
            drawRoundRect(
                color = SsColors.Primary,
                size = Size(size.width, size.height * 0.66f),
                cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
            )
            drawCircle(
                color = SsColors.Accent,
                radius = size.width * 0.16f,
                center = Offset(size.width * 0.74f, size.height * 0.22f),
            )
        }
        Text(
            alt?.takeIf { it.isNotBlank() } ?: "配图暂不可用",
            style = SsType.Caption,
            color = SsColors.Primary,
        )
    }
}

private fun advance(current: Int, size: Int, set: (Int) -> Unit) {
    if (current + 1 < size) set(current + 1)
}

private fun masteryLabel(mastery: String): String = when (runCatching { CardMastery.valueOf(mastery) }.getOrNull()) {
    CardMastery.NEW -> "未学习"
    CardMastery.LEARNING -> "学习中"
    CardMastery.MASTERED -> "已掌握"
    null -> mastery
}

private fun toneFor(mastery: String): SsTone = when (runCatching { CardMastery.valueOf(mastery) }.getOrNull()) {
    CardMastery.NEW -> SsTone.NEUTRAL
    CardMastery.LEARNING -> SsTone.ACCENT
    CardMastery.MASTERED -> SsTone.SUCCESS
    null -> SsTone.NEUTRAL
}

private fun priorityLabel(priority: TopicPriority): String = when (priority) {
    TopicPriority.MUST -> "必须掌握"
    TopicPriority.SHOULD -> "建议掌握"
    TopicPriority.OPTIONAL -> "选学"
}

/**
 * Quotes are extracted from PDF/HTML text, so `<`, `>` and `&` can arrive XML-escaped. Only this
 * render site reverses the five predefined entities; the stored citation bytes stay untouched.
 * `&amp;` goes last so `&amp;lt;` is not double-unescaped.
 */
internal fun displayQuote(quote: String): String = quote
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&#39;", "'")
    .replace("&amp;", "&")
