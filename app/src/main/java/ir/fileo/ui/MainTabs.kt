package ir.fileo.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import ir.fileo.R
import ir.fileo.ui.history.HistoryScreen
import ir.fileo.ui.home.HomeScreen
import ir.fileo.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private data class TopLevelDestination(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val TOP_LEVEL_DESTINATIONS = listOf(
    TopLevelDestination(R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    TopLevelDestination(R.string.nav_history, Icons.Filled.History, Icons.Outlined.History),
    TopLevelDestination(R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

/**
 * سه تب اصلی — HorizontalPager هم‌گام با NavigationBar:
 * هم کلیک روی تب و هم سوایپ دستی کار می‌کند؛ صفحه‌های مجاور از قبل ترکیب شده‌اند
 * (beyondViewportPageCount) تا سوییچ کاملاً آنی حس شود.
 */
@Composable
fun MainTabsScreen(
    onSend: () -> Unit,
    onReceive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(initialPage = 0) { TOP_LEVEL_DESTINATIONS.size }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                TOP_LEVEL_DESTINATIONS.forEachIndexed { index, destination ->
                    val selected = selectedPage(pagerState) == index
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            beyondViewportPageCount = 1,
        ) { page ->
            when (page) {
                0 -> HomeScreen(onSend = onSend, onReceive = onReceive)
                1 -> HistoryScreen()
                else -> SettingsScreen()
            }
        }
    }
}

/**
 * حین درگ، هایلایت با targetPage جابه‌جا می‌شود (فیدبک فوری به سوایپ)؛
 * پس از settle با currentPage هم‌گام می‌مانیم.
 */
private fun selectedPage(pagerState: PagerState): Int =
    if (pagerState.isScrollInProgress) pagerState.targetPage else pagerState.currentPage
