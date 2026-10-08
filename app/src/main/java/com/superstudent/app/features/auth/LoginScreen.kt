package com.superstudent.app.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superstudent.app.ssViewModel
import com.superstudent.core.designsystem.SsColors
import com.superstudent.core.designsystem.SsPillButton
import com.superstudent.core.designsystem.SsShapes
import com.superstudent.core.designsystem.SsSpacing
import com.superstudent.core.designsystem.SsType

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    /**
     * Why this screen is being shown when the student did not ask for it: the one unified copy for a
     * credential that could not be used (ZLQ-119 §4.2). It is a notice, not an error — nothing the
     * student just typed went wrong — so it renders apart from [LoginUiState.error].
     */
    notice: String? = null,
) {
    val viewModel: LoginViewModel = ssViewModel { LoginViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    var pat by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }

    LaunchedEffect(state.loggedIn) { if (state.loggedIn) onLoggedIn() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(SsSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(SsSpacing.Lg),
    ) {
        Spacer(Modifier.height(SsSpacing.Xxl))
        Text("超级学生", style = SsType.Title, modifier = Modifier.testTag("app_title"))
        Text("用你自己的资料，生成属于你的学习计划与记忆卡片。", style = SsType.BodySmall)

        OutlinedTextField(
            value = pat,
            onValueChange = { pat = it },
            modifier = Modifier.fillMaxWidth().testTag("pat_input"),
            label = { Text("访问令牌 PAT") },
            placeholder = { Text("pt-...") },
            singleLine = true,
            shape = SsShapes.Input,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            enabled = !state.busy && !state.restoring,
        )
        Text("令牌只保存在本机安全存储中，不会写入代码、日志或云端。", style = SsType.Caption)

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth().testTag("username_input"),
            label = { Text("学生用户名") },
            placeholder = { Text("3~40 个字符") },
            singleLine = true,
            shape = SsShapes.Input,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            enabled = !state.busy && !state.restoring,
        )

        if (notice != null) {
            Text(
                notice,
                style = SsType.BodySmall,
                color = SsColors.Error,
                modifier = Modifier.testTag("login_notice"),
            )
        }

        if (state.error != null) {
            Text(
                state.error.orEmpty(),
                style = SsType.BodySmall,
                color = SsColors.Error,
                modifier = Modifier.testTag("login_error"),
            )
        }

        SsPillButton(
            text = when {
                state.restoring -> "正在恢复学习数据…"
                state.busy -> "登录中…"
                // The automatic retries are already spent when this shows, so the button is the retry
                // entry ZLQ-138 asks for — and saying 重试登录 tells the student that pressing it again
                // is the intended next step, not a repeat of a mistake.
                state.canRetry -> "重试登录"
                else -> "登录"
            },
            onClick = { viewModel.login(pat, username) },
            modifier = Modifier.fillMaxWidth().testTag("login_button"),
            enabled = !state.busy && !state.restoring && pat.isNotBlank() && username.isNotBlank(),
        )
    }
}
