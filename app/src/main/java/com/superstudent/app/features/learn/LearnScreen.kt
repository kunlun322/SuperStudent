package com.superstudent.app.features.learn

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superstudent.app.Routes
import com.superstudent.app.features.packages.PackageListViewModel
import com.superstudent.app.ssViewModel
import com.superstudent.core.database.LearningPackageEntity
import com.superstudent.core.designsystem.SsCard
import com.superstudent.core.designsystem.SsColors
import com.superstudent.core.designsystem.SsSpacing
import com.superstudent.core.designsystem.SsStatusChip
import com.superstudent.core.designsystem.SsTone
import com.superstudent.core.designsystem.SsType

/**
 * FR-11: link-style handoff only.
 *
 * There is deliberately no platform shared-library retrieval on this screen — design §7.3 rules it
 * out of this version, so there is nothing here to isolate and nothing here that can leak another
 * student's chunks. Every card opens the system browser; the app keeps no copy of any course data.
 */
@Composable
fun LearnScreen() {
    val context = LocalContext.current
    val viewModel: PackageListViewModel = ssViewModel { PackageListViewModel(it) }
    val packages by viewModel.packages.collectAsStateWithLifecycle()
    var failed by remember { mutableStateOf(false) }

    fun open(url: String) {
        failed = !openExternal(context, url)
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(SsSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(SsSpacing.Xs)) {
                Text("学习资源", style = SsType.Section, modifier = Modifier.testTag("learn_title"))
                Text(
                    "按专业或你的学习内容跳转到已有的免费绩点课。本版只做链接承接，不检索平台共享库。",
                    style = SsType.BodySmall,
                )
            }
        }

        if (failed) {
            item {
                Text(
                    "未找到可打开浏览器的应用",
                    style = SsType.BodySmall,
                    color = SsColors.Error,
                    modifier = Modifier.testTag("learn_error"),
                )
            }
        }

        if (packages.isNotEmpty()) {
            item {
                Text("我的学习内容", style = SsType.Label, modifier = Modifier.testTag("learn_mine"))
            }
            items(packages, key = { it.packageId }) { pkg ->
                MineCard(pkg, onSearch = { open(CourseCatalog.searchUrl(it)) })
            }
        }

        item {
            Text("按专业浏览", style = SsType.Label, modifier = Modifier.testTag("learn_disciplines"))
        }
        item {
            SsCard(
                Modifier
                    .fillMaxWidth()
                    .clickable { open(CourseCatalog.SMART_EDU) }
                    .padding(SsSpacing.Md)
                    .testTag("learn_smartedu"),
            ) {
                Column(Modifier.padding(SsSpacing.Sm), verticalArrangement = Arrangement.spacedBy(SsSpacing.Xs)) {
                    Text("国家高等教育智慧教育平台", style = SsType.BodySmall)
                    Text("官方免费课程库，含大量学分课", style = SsType.Caption)
                }
            }
        }
        items(CourseCatalog.DISCIPLINES, key = { it.key }) { discipline ->
            DisciplineCard(discipline, onOpen = { open(CourseCatalog.categoryUrl(discipline.key)) })
        }
    }
}

@Composable
private fun MineCard(pkg: LearningPackageEntity, onSearch: (String) -> Unit) {
    SsCard(
        Modifier
            .fillMaxWidth()
            .clickable { onSearch(pkg.title) }
            .testTag("learn_package_${pkg.packageId}"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(SsSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SsSpacing.Sm),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SsSpacing.Xs)) {
                Text(pkg.title, style = SsType.BodySmall, modifier = Modifier.testTag("learn_package_title"))
                Text("查找同名免费绩点课", style = SsType.Caption)
            }
            SsStatusChip("${pkg.sourceCount} 份资料", SsTone.NEUTRAL)
        }
    }
}

@Composable
private fun DisciplineCard(discipline: CourseCatalog.Discipline, onOpen: () -> Unit) {
    SsCard(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag("learn_discipline_${discipline.key}"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(SsSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SsSpacing.Xs),
        ) {
            Text(discipline.label, style = SsType.Label, modifier = Modifier.testTag("learn_discipline_label"))
            Text(discipline.sample, style = SsType.Caption)
        }
    }
}

/** Leaving the app is the whole feature; no in-app browser, so no extra attack surface is added. */
private fun openExternal(context: Context, url: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
