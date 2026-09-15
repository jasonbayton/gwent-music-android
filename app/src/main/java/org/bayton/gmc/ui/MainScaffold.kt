package org.bayton.gmc.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.bayton.gmc.notify.NotificationRouteRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.bayton.gmc.ui.account.AccountContent
import org.bayton.gmc.ui.home.HomeContent
import org.bayton.gmc.ui.home.HomeViewModel
import org.bayton.gmc.ui.messages.MessagesContent
import org.bayton.gmc.ui.schedule.ScheduleContent
import org.bayton.gmc.ui.theme.LocalBayton

private data class Tab(val label: String, val icon: ImageVector)

/** The four primary sections as a swipeable pager with a shared bottom bar (DAG/CAG style). */
@Composable
fun MainScaffold(nav: NavHostController, initialTab: Int = 0,
    notificationRequest: NotificationRouteRequest? = null,
    onNotificationConsumed: (NotificationRouteRequest) -> Unit = {}) {
    val c = LocalBayton.current
    val tabs = listOf(
        Tab("Home", Icons.Outlined.Home),
        Tab("Schedule", Icons.Outlined.CalendarMonth),
        Tab("Messages", Icons.Outlined.Forum),
        Tab("Account", Icons.Outlined.Person),
    )
    val pager = rememberPagerState(initialPage = initialTab.coerceIn(0, tabs.size - 1), pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    LaunchedEffect(notificationRequest?.id) {
        notificationRequest?.let { request ->
            pager.scrollToPage(request.tab)
            onNotificationConsumed(request)
        }
    }

    fun goto(i: Int) { scope.launch { pager.animateScrollToPage(i) } }
    fun toLogin() = nav.navigate("login") { popUpTo(0) { inclusive = true } }
    val open: (String) -> Unit = { key ->
        when (key) {
            "home" -> goto(0); "schedule" -> goto(1); "messages" -> goto(2); "account" -> goto(3)
            else -> nav.navigate(key)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,   // root baytonBackground() shows through
        // Screens own their top (status-bar) inset so backgrounds bleed edge to edge; the bottom
        // bar keeps its own navigation-bar inset. Zeroing content insets here avoids double padding.
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            Column {
                HorizontalDivider(color = c.line)
                NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = pager.currentPage == i,
                            onClick = { goto(i) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = c.accent, selectedTextColor = c.accent,
                                unselectedIconColor = c.inkFaint, unselectedTextColor = c.inkFaint,
                                indicatorColor = Color.Transparent,
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(pad), key = { it }) { page ->
            when (page) {
                0 -> {
                    val vm: HomeViewModel = hiltViewModel()
                    val ui by vm.ui.collectAsStateWithLifecycle()
                    HomeContent(androidx.compose.foundation.layout.PaddingValues(0.dp), ui, open = open, onMessageTeacher = { to, name ->
                        if (to.isBlank()) nav.navigate("teacher")
                        else nav.navigate("compose?to=" + android.net.Uri.encode(to) + "&toName=" + android.net.Uri.encode(name))
                    }, onRetryOverview = vm::retryOverview, onRetryTerm = vm::retryTerm,
                        onRetryMessages = vm::retryMessages, onRefresh = vm::refresh, onReconnect = ::toLogin)
                }
                1 -> ScheduleContent(androidx.compose.foundation.layout.PaddingValues(0.dp), onOpenHistory = { nav.navigate("schedule/history") })
                2 -> MessagesContent(androidx.compose.foundation.layout.PaddingValues(0.dp), openMessage = { nav.navigate(it) }, onCompose = { nav.navigate("teacher") })
                3 -> AccountContent(androidx.compose.foundation.layout.PaddingValues(0.dp), onSignedOut = ::toLogin, onOpenSettings = { nav.navigate("settings") })
            }
        }
    }
}
