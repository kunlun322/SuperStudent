package com.superstudent.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.superstudent.app.auth.AuthSessionState
import com.superstudent.app.auth.REAUTHENTICATION_MESSAGE
import com.superstudent.app.features.auth.LoginScreen
import com.superstudent.app.features.packages.NewPackageScreen
import com.superstudent.app.features.packages.PackageDetailScreen
import com.superstudent.app.features.results.ResultsScreen
import com.superstudent.app.features.shell.ShellScreen
import com.superstudent.app.features.shell.SsTab
import com.superstudent.app.features.tasks.ResumeReason
import com.superstudent.app.features.tasks.TaskForegroundService
import com.superstudent.core.database.DatabaseBootstrapFailure
import com.superstudent.core.database.DatabaseBootstrapState
import com.superstudent.core.designsystem.SsSpacing
import com.superstudent.core.designsystem.SsTheme
import com.superstudent.core.designsystem.SsType
import com.superstudent.core.upload.SourceRecoveryTrigger
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SsTheme {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    // §3.7: the database is the thing that failed, so no surface below may query it.
                    // This is checked before `container()` on purpose — the container builds its Room
                    // handle lazily, and a screen that touched it would throw inside the composition
                    // instead of telling the student the upgrade failed.
                    val bootstrap by DatabaseBootstrapState.state.collectAsStateWithLifecycle()
                    val bootstrapFailure = (bootstrap as? DatabaseBootstrapState.Bootstrap.Failed)?.failure
                    if (bootstrapFailure != null) {
                        DatabaseBlockedPage(bootstrapFailure)
                        return@Box
                    }
                    val container = container()
                    val navController = rememberNavController()
                    val notificationPermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { }
                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    var start by remember { mutableStateOf<String?>(null) }

                    // §4.2/§4.5: one unified copy and one route for every credential failure, whatever
                    // found it — a cold start that could not decrypt, or a 401 that arrived mid-run.
                    val authState by container.authSession.state.collectAsStateWithLifecycle()
                    val reauthNotice = if (authState is AuthSessionState.ReauthenticationRequired) {
                        REAUTHENTICATION_MESSAGE
                    } else {
                        null
                    }
                    LaunchedEffect(authState) {
                        // Guarded on a composed destination: before the NavHost exists there is
                        // nothing to navigate from, and the cold start below already picks LOGIN.
                        if (reauthNotice != null &&
                            navController.currentDestination != null &&
                            navController.currentDestination?.route != Routes.LOGIN
                        ) {
                            navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                        }
                    }

                    LaunchedEffect(Unit) {
                        val identityId = container.prefs.snapshotAccount().first
                        if (!container.hasPat() || identityId.isNullOrBlank()) {
                            start = Routes.LOGIN
                        } else {
                            // Cold-start recovery is StartupReconciler's task-run-recovery rule,
                            // registered in SsApplication.onCreate — the one process-level startup
                            // entry, driven by the application pass and the identity-ready signal
                            // (PackagesViewModel). This branch only picks the route.
                            start = Routes.shell(SsTab.STUDY.key)
                        }
                    }

                    val initial = start
                    if (initial != null) {
                        NavHost(navController = navController, startDestination = initial) {
                            composable(Routes.LOGIN) {
                                LoginScreen(onLoggedIn = {
                                    // Activity scope, not the login entry's: this recovery has to
                                    // outlive the navigation that immediately follows it. Its timing
                                    // is fixed (ZLQ-126 §3.7 / design §5.3) — first thing the callback
                                    // does, ahead of the navigation — and the coordinator below it is
                                    // what keeps it from being a second RESUME_ALL when it lands while
                                    // the application pass is still running.
                                    lifecycleScope.launch {
                                        TaskForegroundService.reconcileAndResume(
                                            applicationContext,
                                            ResumeReason.LOGIN_COMPLETED,
                                        )
                                    }
                                    // §3.4 LOGIN_COMPLETED, its own launch so a slow task-run recovery
                                    // cannot delay the source scan. An upload that failed with
                                    // AUTH_EXPIRED is waiting on exactly this: a credential now exists,
                                    // so the row's 重新登录 entry has done its whole job.
                                    lifecycleScope.launch {
                                        container.sourceRecovery.request(SourceRecoveryTrigger.LOGIN)
                                    }
                                    navController.navigate(Routes.shell(SsTab.STUDY.key)) {
                                        popUpTo(Routes.LOGIN) { inclusive = true }
                                    }
                                }, notice = reauthNotice)
                            }
                            composable(
                                route = Routes.SHELL,
                                arguments = listOf(navArgument("tab") {
                                    type = NavType.StringType
                                    defaultValue = SsTab.STUDY.key
                                }),
                            ) { entry ->
                                ShellScreen(
                                    navController = navController,
                                    initialTab = entry.arguments?.getString("tab") ?: SsTab.STUDY.key,
                                )
                            }
                            composable(Routes.PACKAGE_NEW) {
                                NewPackageScreen(navController = navController)
                            }
                            composable(
                                route = Routes.PACKAGE_DETAIL,
                                arguments = listOf(navArgument("packageId") { type = NavType.StringType }),
                            ) { entry ->
                                PackageDetailScreen(
                                    navController = navController,
                                    packageId = entry.arguments?.getString("packageId").orEmpty(),
                                )
                            }
                            composable(
                                route = Routes.RESULTS,
                                arguments = listOf(navArgument("packageId") { type = NavType.StringType }),
                            ) { entry ->
                                ResultsScreen(
                                    navController = navController,
                                    packageId = entry.arguments?.getString("packageId").orEmpty(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The blocking page for a local database that could not be opened (ZLQ-132 §3.7).
 *
 * It shows the failure *code* and never the exception: a Room migration failure interpolates the
 * database path and the failing SQL, and this page is exactly where that would end up on a screenshot.
 * Upload is disabled by construction — nothing here can reach a worker, and the three source workers
 * return early on [DatabaseBootstrapState.isFailed] so a queued retry cannot throw either.
 */
@Composable
private fun DatabaseBlockedPage(failure: DatabaseBootstrapFailure) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(SsSpacing.Lg)
            .testTag("database_blocked_page"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "本地数据升级失败，请重启后重试",
            style = SsType.Body,
            modifier = Modifier.testTag("database_blocked_title"),
        )
        Text(
            "错误码：${failure.wire}",
            style = SsType.Caption,
            modifier = Modifier.testTag("database_blocked_code"),
        )
    }
}
