package com.superstudent.app.features.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.superstudent.app.container
import com.superstudent.app.features.learn.LearnScreen
import com.superstudent.app.features.packages.PackageListScreen
import com.superstudent.app.features.profile.ProfileScreen
import com.superstudent.core.designsystem.SsColors
import com.superstudent.core.designsystem.SsSpacing
import com.superstudent.core.designsystem.SsType
import kotlinx.coroutines.launch

enum class SsTab(val key: String, val label: String) {
    HOME("home", "首页"),
    LEARN("learn", "学习"),
    STUDY("study", "自学"),
    CIRCLE("circle", "圈子"),
    PROFILE("profile", "我的"),
    ;

    companion object {
        val ORDER = listOf(HOME, LEARN, STUDY, CIRCLE, PROFILE)
        fun of(key: String?): SsTab = ORDER.firstOrNull { it.key == key } ?: STUDY
    }
}

@Composable
fun ShellScreen(navController: androidx.navigation.NavHostController, initialTab: String) {
    val container = container()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(SsTab.of(initialTab)) }

    LaunchedEffect(tab) { scope.launch { container.prefs.setLastTab(tab.key) } }

    Scaffold(
        containerColor = SsColors.Background,
        bottomBar = {
            Surface(color = SsColors.Surface, shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = SsSpacing.Sm),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SsTab.ORDER.forEach { item ->
                        TabItem(
                            tab = item,
                            selected = item == tab,
                            onClick = { tab = item },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(padding)) {
            when (tab) {
                SsTab.STUDY -> PackageListScreen(navController = navController)
                SsTab.LEARN -> LearnScreen()
                SsTab.PROFILE -> ProfileScreen(navController = navController)
                else -> Placeholder(tab.label)
            }
        }
    }
}

@Composable
private fun TabItem(tab: SsTab, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .clickable(onClick = onClick)
            .testTag("tab_${tab.key}")
            .background(if (selected) SsColors.PrimaryContainer else SsColors.Surface)
            .padding(horizontal = SsSpacing.Md, vertical = SsSpacing.Sm)
            .height(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            tab.label,
            style = if (selected) SsType.Label else SsType.BodySmall,
            color = if (selected) SsColors.Primary else SsType.BodySmall.color,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Placeholder(label: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(SsSpacing.Xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$label 功能即将上线", style = SsType.Section, modifier = Modifier.testTag("placeholder_$label"))
        Text("本版交付「自学」主链路与「学习」资源承接。", style = SsType.BodySmall)
    }
}
