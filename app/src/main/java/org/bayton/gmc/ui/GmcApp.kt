package org.bayton.gmc.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.bayton.gmc.notify.NotificationRouteRequest
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.navArgument
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.bayton.gmc.ui.billing.BillingScreen
import org.bayton.gmc.ui.login.LoginScreen
import org.bayton.gmc.ui.messages.ComposeMessageScreen
import org.bayton.gmc.ui.messages.MessageDetailScreen
import org.bayton.gmc.ui.pupils.PupilActivitiesScreen
import org.bayton.gmc.ui.pupils.PupilsScreen
import org.bayton.gmc.ui.reports.ReportsScreen
import org.bayton.gmc.ui.teacher.TeacherScreen

@Composable
fun GmcApp(startSignedIn: Boolean, notificationRequest: NotificationRouteRequest? = null,
    onNotificationConsumed: (NotificationRouteRequest) -> Unit = {}) {
    val nav = rememberNavController()
    // iOS-style push/pop: forward slides in from the right, back reverses to the right.
    val spec = tween<androidx.compose.ui.unit.IntOffset>(300)
    NavHost(
        nav,
        startDestination = if (startSignedIn) "main" else "login",
        enterTransition = { slideIntoContainer(SlideDirection.Left, spec) },
        exitTransition = { slideOutOfContainer(SlideDirection.Left, spec) },
        popEnterTransition = { slideIntoContainer(SlideDirection.Right, spec) },
        popExitTransition = { slideOutOfContainer(SlideDirection.Right, spec) },
    ) {
        composable("login") {
            LoginScreen(onSignedIn = { nav.navigate("main") { popUpTo("login") { inclusive = true } } })
        }
        composable("main") { MainScaffold(nav, notificationRequest = notificationRequest, onNotificationConsumed = onNotificationConsumed) }
        composable("message/{box}/{itemId}") { entry ->
            MessageDetailScreen(onBack = { nav.popFrom(entry) }, onReply = { subj, to, toName ->
                val re = if (subj.trim().startsWith("Re:", true) || subj.isBlank()) subj else "Re: $subj"
                nav.navigate("compose?subject=" + Uri.encode(re) + "&to=" + Uri.encode(to) + "&toName=" + Uri.encode(toName))
            })
        }
        composable(
            "compose?subject={subject}&to={to}&toName={toName}",
            arguments = listOf(
                navArgument("subject") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("to") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("toName") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            ComposeMessageScreen(
                prefillSubject = entry.arguments?.getString("subject").orEmpty(),
                toContactId = entry.arguments?.getString("to").orEmpty(),
                toName = entry.arguments?.getString("toName").orEmpty(),
                onBack = { nav.popFrom(entry) }, onSent = { nav.completeFrom(entry) },
            )
        }
        composable("reports") { entry -> ReportsScreen(onBack = { nav.popFrom(entry) }) }
        composable("pupils") { entry ->
            PupilsScreen(
                onBack = { nav.popFrom(entry) },
                onOpen = { pupilId, name -> nav.navigate("pupil/$pupilId?name=" + Uri.encode(name)) },
            )
        }
        composable(
            "pupil/{pupilId}?name={name}",
            arguments = listOf(
                navArgument("pupilId") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry -> PupilActivitiesScreen(onBack = { nav.popFrom(entry) }) }
        composable("billing") { entry -> BillingScreen(onBack = { nav.popFrom(entry) }) }
        composable("schedule/history") { entry -> org.bayton.gmc.ui.schedule.HistoryScreen(onBack = { nav.popFrom(entry) }) }
        composable("settings") { entry -> org.bayton.gmc.ui.notifications.SettingsScreen(onBack = { nav.popFrom(entry) }) }
        composable("teacher") { entry ->
            TeacherScreen(onBack = { nav.popFrom(entry) }, onMessage = { to, name ->
                nav.navigate("compose?to=" + Uri.encode(to) + "&toName=" + Uri.encode(name))
            })
        }
    }
    LaunchedEffect(nav, startSignedIn) {
        // Unlike currentBackStackEntryFlow, visibleEntries also emits when the stack
        // becomes empty. Recheck the actual destination so transitions never reset tabs.
        nav.visibleEntries.collect { nav.recoverEmptyStack(startSignedIn) }
    }
    LaunchedEffect(startSignedIn, notificationRequest?.id) {
        if (startSignedIn && notificationRequest != null && nav.currentDestination?.route != "main") {
            if (!nav.popBackStack("main", false)) nav.navigate("main") { popUpTo(0) { inclusive = true } }
        }
    }
}
