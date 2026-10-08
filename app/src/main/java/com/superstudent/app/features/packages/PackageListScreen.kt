package com.superstudent.app.features.packages

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superstudent.app.Routes
import com.superstudent.app.container
import com.superstudent.app.ssViewModel
import com.superstudent.core.database.LearningPackageEntity
import com.superstudent.core.designsystem.SsCard
import com.superstudent.core.designsystem.SsColors
import com.superstudent.core.designsystem.SsPillButton
import com.superstudent.core.designsystem.SsSpacing
import com.superstudent.core.designsystem.SsStatusChip
import com.superstudent.core.designsystem.SsTone
import com.superstudent.core.designsystem.SsType
import com.superstudent.core.model.LearningGoal
import com.superstudent.core.model.PackageStatus

@Composable
fun PackageListScreen(navController: androidx.navigation.NavHostController) {
    val viewModel: PackageListViewModel = ssViewModel { PackageListViewModel(it) }
    val packages by viewModel.packages.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(SsSpacing.Lg)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("我的学习包", style = SsType.Section, modifier = Modifier.testTag("packages_title"))
            SsPillButton(
                text = "新建",
                onClick = { navController.navigate(Routes.PACKAGE_NEW) },
                modifier = Modifier.testTag("new_package_button"),
            )
        }

        if (packages.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(top = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SsSpacing.Md),
            ) {
                Text("还没有学习包", style = SsType.Label)
                Text("上传你的课件或讲义，AI 会为你生成学习计划和记忆卡片。", style = SsType.BodySmall)
                SsPillButton(
                    text = "创建第一个学习包",
                    onClick = { navController.navigate(Routes.PACKAGE_NEW) },
                    modifier = Modifier.testTag("empty_new_package"),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = SsSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(SsSpacing.Md),
            ) {
                items(packages, key = { it.packageId }) { pkg ->
                    PackageCard(pkg) { navController.navigate(Routes.detail(pkg.packageId)) }
                }
            }
        }
    }
}

@Composable
private fun PackageCard(pkg: LearningPackageEntity, onClick: () -> Unit) {
    SsCard(Modifier.fillMaxWidth().testTag("package_${pkg.packageId}")) {
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(SsSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(pkg.title, style = SsType.Label, modifier = Modifier.weight(1f))
                StatusChip(pkg.status)
            }
            Text(goalLabel(pkg.goal) + pkg.chapterRange?.let { " · $it" }.orEmpty(), style = SsType.BodySmall)
            Text("资料 ${pkg.sourceCount} 份", style = SsType.Caption, color = SsColors.Primary)
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val parsed = runCatching { PackageStatus.valueOf(status) }.getOrNull()
    val (label, tone) = when (parsed) {
        PackageStatus.DONE -> "已完成" to SsTone.SUCCESS
        PackageStatus.GENERATING -> "生成中" to SsTone.ACCENT
        PackageStatus.READY -> "可生成" to SsTone.INFO
        PackageStatus.LOCAL_ONLY -> "仅本机" to SsTone.NEUTRAL
        PackageStatus.RECOVERY_REQUIRED -> "需恢复" to SsTone.DANGER
        PackageStatus.DRAFT, null -> "草稿" to SsTone.NEUTRAL
    }
    SsStatusChip(label, tone, Modifier.testTag("package_status"))
}

internal fun goalLabel(raw: String): String = when (runCatching { LearningGoal.valueOf(raw) }.getOrNull()) {
    LearningGoal.FINAL_REVIEW -> "期末复习"
    LearningGoal.PREVIEW -> "课前预习"
    LearningGoal.DAILY -> "日常巩固"
    LearningGoal.PRESENTATION -> "课堂展示"
    null -> raw
}
