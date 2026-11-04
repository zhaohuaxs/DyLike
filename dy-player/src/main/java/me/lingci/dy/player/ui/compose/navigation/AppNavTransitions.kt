package me.lingci.dy.player.ui.compose.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

class AppNavTransitions(
    private val topLevelRoutes: Set<String>,
    private val playerRoutes: Set<String>
) {
    fun enter(from: String?, to: String?): EnterTransition = when {
        to in playerRoutes -> fadeIn(tween(220)) +
            scaleIn(initialScale = .94f, animationSpec = tween(320, easing = FastOutSlowInEasing))
        from in playerRoutes -> fadeIn(tween(220))
        isTabSwitch(from, to) -> fadeIn(tween(210, delayMillis = 90)) +
            scaleIn(initialScale = .97f, animationSpec = tween(210, delayMillis = 90, easing = FastOutSlowInEasing))
        else -> slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200))
    }

    fun exit(from: String?, to: String?): ExitTransition = when {
        to in playerRoutes -> fadeOut(tween(160)) +
            scaleOut(targetScale = .96f, animationSpec = tween(300, easing = FastOutSlowInEasing))
        isTabSwitch(from, to) -> fadeOut(tween(90))
        else -> slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 5 } + fadeOut(tween(180))
    }

    fun popEnter(from: String?, to: String?): EnterTransition = when {
        from in playerRoutes -> fadeIn(tween(220)) +
            scaleIn(initialScale = .97f, animationSpec = tween(260, easing = FastOutSlowInEasing))
        isTabSwitch(from, to) -> fadeIn(tween(210, delayMillis = 90)) +
            scaleIn(initialScale = .97f, animationSpec = tween(210, delayMillis = 90, easing = FastOutSlowInEasing))
        else -> slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 5 } + fadeIn(tween(200))
    }

    fun popExit(from: String?, to: String?): ExitTransition = when {
        from in playerRoutes -> fadeOut(tween(180)) +
            scaleOut(targetScale = .96f, animationSpec = tween(260, easing = FastOutSlowInEasing))
        isTabSwitch(from, to) -> fadeOut(tween(90))
        else -> slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(160))
    }

    private fun isTabSwitch(from: String?, to: String?): Boolean =
        from in topLevelRoutes && to in topLevelRoutes
}