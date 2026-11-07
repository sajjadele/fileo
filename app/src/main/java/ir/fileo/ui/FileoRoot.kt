package ir.fileo.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ir.fileo.ui.receive.ReceiveScreen
import ir.fileo.ui.send.SendScreen

object Routes {
    /** مقصد ریشه — شامل سه تب اصلی (HorizontalPager) */
    const val TABS = "tabs"
    const val SEND = "send"
    const val RECEIVE = "receive"
}

@Composable
fun FileoRoot() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.TABS,
        // ترنزیشن‌های سریع — به‌جای fade پیش‌فرض ۷۰۰ms که حس لگ می‌دهد
        enterTransition = {
            fadeIn(tween(SNAPPY_TRANSITION_MS)) +
                scaleIn(initialScale = 0.96f, animationSpec = tween(SNAPPY_TRANSITION_MS))
        },
        exitTransition = {
            fadeOut(tween(FAST_TRANSITION_MS))
        },
        popEnterTransition = {
            fadeIn(tween(SNAPPY_TRANSITION_MS))
        },
        popExitTransition = {
            fadeOut(tween(SNAPPY_TRANSITION_MS)) +
                scaleOut(targetScale = 0.96f, animationSpec = tween(SNAPPY_TRANSITION_MS))
        },
    ) {
        composable(Routes.TABS) {
            MainTabsScreen(
                onSend = { navController.navigate(Routes.SEND) },
                onReceive = { navController.navigate(Routes.RECEIVE) },
            )
        }
        composable(Routes.SEND) {
            SendScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.RECEIVE) {
            ReceiveScreen(onBack = { navController.popBackStack() })
        }
    }
}

private const val SNAPPY_TRANSITION_MS = 200
private const val FAST_TRANSITION_MS = 140
