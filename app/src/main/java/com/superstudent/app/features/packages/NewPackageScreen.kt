package com.superstudent.app.features.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.superstudent.app.AppContainer
import com.superstudent.app.Routes
import com.superstudent.app.container
import com.superstudent.app.ssViewModel
import com.superstudent.core.designsystem.SsColors
import com.superstudent.core.designsystem.SsPillButton
import com.superstudent.core.designsystem.SsShapes
import com.superstudent.core.designsystem.SsSpacing
import com.superstudent.core.designsystem.SsType
import com.superstudent.core.model.LearningGoal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val GOALS = listOf(
    LearningGoal.FINAL_REVIEW to "期末复习",
    LearningGoal.PREVIEW to "课前预习",
    LearningGoal.DAILY to "日常巩固",
    LearningGoal.PRESENTATION to "课堂展示",
)

data class NewPackageUiState(val busy: Boolean = false, val error: String? = null)

class NewPackageViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(NewPackageUiState())
    val state: StateFlow<NewPackageUiState> = _state.asStateFlow()

    fun create(title: String, goal: LearningGoal, chapterRange: String, onCreated: (String) -> Unit) {
        if (_state.value.busy) return
        _state.update { NewPackageUiState(busy = true) }
        viewModelScope.launch {
            runCatching {
                val identity = container.accountRepository.requireIdentityId()
                container.packageRepository.createPackage(identity, title, goal, chapterRange.ifBlank { null })
            }.onSuccess { entity ->
                _state.update { NewPackageUiState() }
                onCreated(entity.packageId)
            }.onFailure { e ->
                _state.update { NewPackageUiState(error = e.message ?: "创建失败") }
            }
        }
    }
}

@Composable
fun NewPackageScreen(navController: androidx.navigation.NavHostController) {
    val viewModel: NewPackageViewModel = ssViewModel { NewPackageViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var title by remember { mutableStateOf("") }
    var chapterRange by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf(LearningGoal.FINAL_REVIEW) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(SsSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(SsSpacing.Lg),
    ) {
        Text("新建学习包", style = SsType.Section, modifier = Modifier.testTag("new_package_title"))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth().testTag("pkg_title_input"),
            label = { Text("名称") },
            placeholder = { Text("例如：高等数学 第三章") },
            singleLine = true,
            shape = SsShapes.Input,
        )

        Column(verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
            Text("学习目标", style = SsType.Label)
            Row(horizontalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
                GOALS.forEach { (value, label) ->
                    SsPillButton(
                        text = label,
                        onClick = { goal = value },
                        outlined = goal != value,
                        modifier = Modifier.testTag("goal_$value"),
                    )
                }
            }
        }

        OutlinedTextField(
            value = chapterRange,
            onValueChange = { chapterRange = it },
            modifier = Modifier.fillMaxWidth().testTag("pkg_range_input"),
            label = { Text("章节范围（可选）") },
            placeholder = { Text("例如：3.1 - 3.5") },
            singleLine = true,
            shape = SsShapes.Input,
        )

        if (state.error != null) {
            Text(state.error.orEmpty(), style = SsType.BodySmall, color = SsColors.Error)
        }

        SsPillButton(
            text = if (state.busy) "创建中…" else "创建并添加资料",
            onClick = {
                viewModel.create(title.trim(), goal, chapterRange.trim()) { packageId ->
                    navController.navigate(Routes.detail(packageId)) {
                        popUpTo(Routes.PACKAGE_NEW) { inclusive = true }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("create_package_button"),
            enabled = !state.busy && title.isNotBlank(),
        )
    }
}
