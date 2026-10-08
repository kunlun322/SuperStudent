package com.superstudent.app.features.results

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import com.superstudent.core.database.ExerciseProgressEntity
import com.superstudent.core.designsystem.SsCard
import com.superstudent.core.designsystem.SsColors
import com.superstudent.core.designsystem.SsPillButton
import com.superstudent.core.designsystem.SsSpacing
import com.superstudent.core.designsystem.SsStatusChip
import com.superstudent.core.designsystem.SsTone
import com.superstudent.core.designsystem.SsType
import com.superstudent.core.model.ExerciseDifficulty
import com.superstudent.core.model.ExerciseJson
import com.superstudent.core.model.ExerciseType

/**
 * Exercises and wrong-answer re-practice (FR-09). Grading happens locally against exercises.json;
 * only the outcome is persisted, to Room + progress.json. exercises.json is never written back, so
 * a re-practice round always sees the original stem and answer.
 */
@Composable
fun ExercisesPane(
    exercises: List<ExerciseJson>,
    progress: Map<String, ExerciseProgressEntity>,
    rePracticeOrder: List<String>,
    onCitation: (String) -> Unit,
    onAnswer: (String, Boolean) -> Unit,
) {
    if (exercises.isEmpty()) {
        Text("习题为空", style = SsType.BodySmall, modifier = Modifier.testTag("exercises_empty"))
        return
    }
    var rePractice by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    var given by remember { mutableStateOf("") }
    var graded by remember { mutableStateOf<Boolean?>(null) }

    val ordered = remember(exercises, rePractice, rePracticeOrder) {
        if (!rePractice) {
            exercises
        } else {
            val byId = exercises.associateBy { it.exerciseId }
            val wrong = rePracticeOrder.filter { progress[it]?.lastCorrect == false }
            val rest = exercises.map { it.exerciseId }.filterNot { it in wrong }
            (wrong + rest).mapNotNull { byId[it] }
        }
    }
    val safeIndex = index.coerceIn(0, ordered.lastIndex)
    val exercise = ordered[safeIndex]
    val answered = progress.values.count { it.attempts > 0 }
    val correct = progress.values.count { it.lastCorrect }

    fun reset() {
        given = ""
        graded = null
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(SsSpacing.Md)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SsPillButton(
                text = if (rePractice) "错题复练" else "顺序练习",
                onClick = {
                    rePractice = !rePractice
                    index = 0
                    reset()
                },
                outlined = !rePractice,
                modifier = Modifier.testTag("repractice_toggle"),
            )
            Text(
                "已答 $answered/${ordered.size} · 正确 $correct",
                style = SsType.Label,
                color = SsColors.Primary,
                modifier = Modifier.testTag("exercise_progress"),
            )
        }
        Text(
            if (rePractice) "答错的题目优先出现" else "按出题顺序练习",
            style = SsType.Caption,
        )

        SsCard(Modifier.fillMaxWidth().weight(1f).testTag("exercise_card")) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(SsSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(SsSpacing.Md),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SsStatusChip(
                        if (exercise.type == ExerciseType.SINGLE_CHOICE) "单选" else "计算",
                        SsTone.INFO,
                    )
                    SsStatusChip(difficultyLabel(exercise.difficulty), SsTone.NEUTRAL)
                    Text("${safeIndex + 1}/${ordered.size}", style = SsType.Caption)
                }
                Text(exercise.stem, style = SsType.Body, modifier = Modifier.testTag("exercise_stem"))

                if (exercise.type == ExerciseType.SINGLE_CHOICE) {
                    Column(verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
                        exercise.options.forEach { option ->
                            val selected = given == option.key
                            SsCard(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = graded == null) { given = option.key }
                                    .testTag("exercise_option_${option.key}"),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(SsSpacing.Md),
                                    horizontalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        option.key,
                                        style = SsType.Label,
                                        color = if (selected) SsColors.Primary else SsType.Label.color,
                                    )
                                    Text(option.text, style = SsType.BodySmall, modifier = Modifier.weight(1f))
                                    if (selected) Text("●", style = SsType.Caption, color = SsColors.Primary)
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = given,
                        onValueChange = { given = it },
                        enabled = graded == null,
                        singleLine = true,
                        label = { Text("填写计算结果", style = SsType.Caption) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag("exercise_input"),
                    )
                }

                if (graded == null) {
                    SsPillButton(
                        text = "提交答案",
                        onClick = {
                            val ok = exercise.answer.isCorrect(given)
                            graded = ok
                            onAnswer(exercise.exerciseId, ok)
                        },
                        enabled = given.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("exercise_submit"),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SsStatusChip(
                                if (graded == true) "答对" else "答错",
                                if (graded == true) SsTone.SUCCESS else SsTone.DANGER,
                                Modifier.testTag("exercise_result"),
                            )
                            Text("正确答案：${exercise.answer.describe()}", style = SsType.BodySmall)
                        }
                        Text("解析", style = SsType.Label, color = SsColors.Primary)
                        Text(exercise.analysis, style = SsType.BodySmall, modifier = Modifier.testTag("exercise_analysis"))
                        if (exercise.citationIds.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(SsSpacing.Xs)) {
                                exercise.citationIds.take(3).forEach { id ->
                                    SsStatusChip(
                                        "引用 $id",
                                        SsTone.INFO,
                                        Modifier.clickable { onCitation(id) }.testTag("exercise_citation_$id"),
                                    )
                                }
                            }
                        }
                        SsPillButton(
                            text = if (safeIndex == ordered.lastIndex) "完成本轮" else "下一题",
                            onClick = {
                                if (safeIndex < ordered.lastIndex) index = safeIndex + 1
                                reset()
                            },
                            outlined = true,
                            modifier = Modifier.fillMaxWidth().testTag("exercise_next"),
                        )
                    }
                }
            }
        }

        if (answered >= ordered.size) {
            Text("本轮练习完成", style = SsType.Label, color = SsColors.Primary, modifier = Modifier.testTag("exercises_done"))
        }
    }
}

private fun difficultyLabel(difficulty: ExerciseDifficulty): String = when (difficulty) {
    ExerciseDifficulty.EASY -> "基础"
    ExerciseDifficulty.MEDIUM -> "中等"
    ExerciseDifficulty.HARD -> "挑战"
}
