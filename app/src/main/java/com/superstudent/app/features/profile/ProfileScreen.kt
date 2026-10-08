package com.superstudent.app.features.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.superstudent.app.AppContainer
import com.superstudent.app.Routes
import com.superstudent.app.container
import com.superstudent.app.ssViewModel
import com.superstudent.core.designsystem.SsCard
import com.superstudent.core.designsystem.SsColors
import com.superstudent.core.designsystem.SsPillButton
import com.superstudent.core.designsystem.SsSpacing
import com.superstudent.core.designsystem.SsType
import com.superstudent.core.repository.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProfileUiState(
    val username: String? = null,
    val identityId: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val loggedOut: Boolean = false,
    // design §6
    val cardImagesEnabled: Boolean = true,
    val prefetchWifiOnly: Boolean = true,
    // FR-12: local convenience copies only; Drive stays authoritative.
    val cacheBytes: Long = 0,
)

class ProfileViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val (identityId, username) = container.prefs.snapshotAccount()
            _state.update { it.copy(identityId = identityId, username = username) }
            refreshCache()
        }
        viewModelScope.launch {
            combine(container.prefs.cardImagesEnabled, container.prefs.prefetchWifiOnly) { images, wifi ->
                images to wifi
            }.collect { (images, wifi) ->
                _state.update { it.copy(cardImagesEnabled = images, prefetchWifiOnly = wifi) }
            }
        }
    }

    fun setCardImagesEnabled(enabled: Boolean) {
        viewModelScope.launch { container.prefs.setCardImagesEnabled(enabled) }
    }

    fun setPrefetchWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch { container.prefs.setPrefetchWifiOnly(wifiOnly) }
    }

    /**
     * FR-12 cleanup entry. Clearing drops the local copies of card pictures and mindmap binaries;
     * the Drive originals and every result JSON are untouched, so anything the student opens again
     * is re-downloaded on demand.
     */
    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.binaryCache.clear() }
            refreshCache()
            _state.update { it.copy(message = "已清理本机缓存，云端结果未改动") }
        }
    }

    private suspend fun refreshCache() {
        val bytes = withContext(Dispatchers.IO) { container.binaryCache.sizeBytes() }
        _state.update { it.copy(cacheBytes = bytes) }
    }

    fun sync() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val identity = _state.value.identityId
            if (identity == null) {
                _state.update { it.copy(busy = false, message = "尚未登录") }
                return@launch
            }
            val result = runCatching { container.restoreRepository.restore(identity) }
            _state.update {
                it.copy(
                    busy = false,
                    message = when (result.getOrNull()) {
                        RestoreResult.OK -> "已从云端恢复学习数据"
                        RestoreResult.RECOVERY_REQUIRED -> "index.json 缺失，已按 package.json 重建，请检查学习包"
                        RestoreResult.NO_REMOTE_DATA -> "云端暂无学习数据"
                        null -> result.exceptionOrNull()?.message ?: "同步失败"
                    },
                )
            }
        }
    }

    /** Deletes the encrypted PAT (Keystore alias + ciphertext) and clears the session pointer. */
    fun logout() {
        viewModelScope.launch {
            val identity = _state.value.identityId
            if (identity != null) runCatching { container.accountRepository.logout(identity) }
            container.logout()
            _state.update { ProfileUiState(loggedOut = true) }
        }
    }
}

@Composable
fun ProfileScreen(navController: androidx.navigation.NavHostController) {
    val viewModel: ProfileViewModel = ssViewModel { ProfileViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.loggedOut) {
        if (state.loggedOut) {
            navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(SsSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(SsSpacing.Md),
    ) {
        Text("我的", style = SsType.Section, modifier = Modifier.testTag("profile_title"))

        SsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(SsSpacing.Lg), verticalArrangement = Arrangement.spacedBy(SsSpacing.Xs)) {
                Text(state.username ?: "未登录", style = SsType.Label, modifier = Modifier.testTag("profile_username"))
                Text(
                    "Identity: ${state.identityId ?: "-"}",
                    style = SsType.Caption,
                    modifier = Modifier.testTag("profile_identity"),
                )
                Text("资料隔离方式：LOGICAL（按 Identity 逻辑隔离）", style = SsType.Caption)
            }
        }

        if (state.message != null) {
            Text(state.message.orEmpty(), style = SsType.BodySmall, color = SsColors.Primary,
                modifier = Modifier.testTag("profile_message"))
        }

        SsPillButton(
            text = if (state.busy) "同步中…" else "同步云端数据",
            onClick = viewModel::sync,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("sync_button"),
        )

        SsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(SsSpacing.Lg), verticalArrangement = Arrangement.spacedBy(SsSpacing.Md)) {
                Text("卡片配图", style = SsType.Label)
                ToggleRow(
                    title = "下载 ImageGen 配图",
                    caption = "关闭后卡片照常可用，只显示文字与本地占位图",
                    checked = state.cardImagesEnabled,
                    onCheckedChange = viewModel::setCardImagesEnabled,
                    tag = "toggle_card_images",
                )
                ToggleRow(
                    title = "仅在 Wi-Fi 下自动加载",
                    caption = "开启后移动网络不批量预取配图，点开单张卡片仍可加载",
                    checked = state.prefetchWifiOnly,
                    onCheckedChange = viewModel::setPrefetchWifiOnly,
                    tag = "toggle_wifi_only",
                )
            }
        }

        SsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(SsSpacing.Lg), verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm)) {
                Text("本机缓存", style = SsType.Label)
                Text(
                    "已用 ${formatBytes(state.cacheBytes)} · 上限 100 MB，超出按最久未用清理",
                    style = SsType.Caption,
                    modifier = Modifier.testTag("cache_size"),
                )
                Text(
                    "清理只删除本机的配图与导图副本，云端结果与学习进度不受影响，再次打开会按需重新下载。",
                    style = SsType.Caption,
                )
                SsPillButton(
                    text = "清理缓存",
                    onClick = viewModel::clearCache,
                    outlined = true,
                    modifier = Modifier.testTag("clear_cache_button"),
                )
            }
        }

        SsPillButton(
            text = "退出登录",
            onClick = {
                viewModel.logout()
            },
            outlined = true,
            modifier = Modifier.fillMaxWidth().testTag("logout_button"),
        )
        Text(
            "退出登录会删除本机保存的访问令牌密文与密钥别名；云端资料保留。",
            style = SsType.Caption,
        )

    }
}

@Composable
private fun ToggleRow(
    title: String,
    caption: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        Modifier.fillMaxWidth().testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SsSpacing.Md),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SsSpacing.Xs)) {
            Text(title, style = SsType.BodySmall)
            Text(caption, style = SsType.Caption)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag("${tag}_switch"))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}
