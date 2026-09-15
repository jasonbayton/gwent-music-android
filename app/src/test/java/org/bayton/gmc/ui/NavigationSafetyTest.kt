package org.bayton.gmc.ui

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the real navigation stack, including entries retained by exit transitions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NavigationSafetyTest {
    private lateinit var nav: NavHostController
    private lateinit var navigator: ComposeNavigator
    private lateinit var owner: LifecycleOwner

    @Before fun setUp() {
        owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
        }
        navigator = ComposeNavigator()
        nav = NavHostController(RuntimeEnvironment.getApplication())
        nav.setLifecycleOwner(owner)
        nav.navigatorProvider.addNavigator(navigator)
        nav.graph = nav.createGraph(startDestination = "main") {
            composable("main") {}
            composable("login") {}
            composable("compose") {}
        }
        completeTransitions()
    }

    private fun completeTransitions() {
        nav.visibleEntries.value.toList().forEach(navigator::onTransitionComplete)
    }

    @Test fun `departing compose back callback cannot pop main on a second tap`() {
        nav.navigate("compose")
        completeTransitions()
        val compose = requireNotNull(nav.currentBackStackEntry)
        assertEquals(Lifecycle.State.RESUMED, compose.lifecycle.currentState)

        nav.popFrom(compose)
        val main = requireNotNull(nav.currentBackStackEntry)
        assertEquals("main", main.destination.route)
        // The old composable can remain drawn and receive taps until its exit transition ends.
        nav.popFrom(compose)
        assertSame(main, nav.currentBackStackEntry)
        completeTransitions()
        nav.popFrom(compose)
        assertSame(main, nav.currentBackStackEntry)
    }

    @Test fun `current entry cannot pop while its enter transition is running`() {
        nav.navigate("compose")
        val compose = requireNotNull(nav.currentBackStackEntry)
        assertEquals(Lifecycle.State.STARTED, compose.lifecycle.currentState)
        nav.popFrom(compose)
        assertSame(compose, nav.currentBackStackEntry)
        completeTransitions()
        nav.popFrom(compose)
        assertEquals("main", nav.currentDestination?.route)
    }

    @Test fun `backgrounded screen cannot act on a back tap`() {
        nav.navigate("compose")
        completeTransitions()
        val compose = requireNotNull(nav.currentBackStackEntry)
        (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.STARTED
        nav.popFrom(compose)
        assertSame(compose, nav.currentBackStackEntry)
    }

    @Test fun `async completion still returns from a paused compose screen`() {
        nav.navigate("compose")
        completeTransitions()
        val compose = requireNotNull(nav.currentBackStackEntry)
        (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.STARTED
        assertEquals(Lifecycle.State.STARTED, compose.lifecycle.currentState)
        nav.completeFrom(compose)
        assertEquals("main", nav.currentDestination?.route)
    }

    @Test fun `stale async completion cannot pop a replacement screen`() {
        nav.navigate("compose")
        completeTransitions()
        val oldCompose = requireNotNull(nav.currentBackStackEntry)
        nav.popFrom(oldCompose)
        nav.navigate("compose")
        completeTransitions()
        val newCompose = requireNotNull(nav.currentBackStackEntry)
        nav.completeFrom(oldCompose)
        assertSame(newCompose, nav.currentBackStackEntry)
    }

    @Test fun `visible entries collector recovers an empty stack to main`() {
        assertCollectorRecovery(signedIn = true, route = "main")
    }

    @Test fun `visible entries collector recovers an empty stack to login`() {
        assertCollectorRecovery(signedIn = false, route = "login")
    }

    private fun assertCollectorRecovery(signedIn: Boolean, route: String) = runBlocking {
        // Mirrors GmcApp's collector. Completion of the last exit transition must emit,
        // including when the stack has no current entry to emit via currentBackStackEntryFlow.
        val collector = launch(Dispatchers.Unconfined) {
            nav.visibleEntries.collect { nav.recoverEmptyStack(signedIn) }
        }
        try {
            nav.popBackStack()
            completeTransitions()
            assertEquals(route, nav.currentDestination?.route)
            completeTransitions()
            val recovered = nav.currentBackStackEntry
            completeTransitions()
            assertSame(recovered, nav.currentBackStackEntry)
        } finally {
            collector.cancel()
        }
    }

    @Test fun `empty signed in stack recovers to a single main destination`() {
        assertRecovery(signedIn = true, route = "main")
    }

    @Test fun `empty signed out stack recovers to a single login destination`() {
        assertRecovery(signedIn = false, route = "login")
    }

    private fun assertRecovery(signedIn: Boolean, route: String) {
        nav.popBackStack()
        assertNull(nav.currentBackStackEntry)
        // Recovery must work even before the removed screen's transition is finished.
        nav.recoverEmptyStack(signedIn)
        assertEquals(route, nav.currentDestination?.route)
        completeTransitions()
        val recovered = nav.currentBackStackEntry
        repeat(3) { nav.recoverEmptyStack(signedIn) }
        assertSame(recovered, nav.currentBackStackEntry)
        nav.popBackStack()
        assertNull("Recovery must not accumulate hidden root destinations", nav.currentBackStackEntry)
    }

    @Test fun `recovery never redirects an existing screen regardless of sign in state`() {
        nav.navigate("compose")
        completeTransitions()
        val compose = nav.currentBackStackEntry
        nav.recoverEmptyStack(signedIn = true)
        nav.recoverEmptyStack(signedIn = false)
        assertSame(compose, nav.currentBackStackEntry)
    }
}
