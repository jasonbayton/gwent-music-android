package org.bayton.gmc.ui

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController

/**
 * Handles user Back only for the current, fully resumed destination. During an exit animation the
 * outgoing screen can still receive taps; an unqualified pop would remove the screen underneath it.
 * Compare entry identity, not route text, because multiple instances can share the same route.
 */
internal fun NavHostController.popFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry === entry && entry.lifecycle.currentState == Lifecycle.State.RESUMED) {
        popBackStack()
    }
}

/**
 * Handles async completion for the originating destination. Unlike a user tap, completion can
 * legitimately arrive while the app is paused, so only entry identity is required here.
 */
internal fun NavHostController.completeFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry === entry) popBackStack()
}

/** Restore a root only when navigation has no destination, never for loading or error UI. */
internal fun NavHostController.recoverEmptyStack(signedIn: Boolean) {
    if (currentBackStackEntry == null) {
        navigate(if (signedIn) "main" else "login") {
            popUpTo(graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}
