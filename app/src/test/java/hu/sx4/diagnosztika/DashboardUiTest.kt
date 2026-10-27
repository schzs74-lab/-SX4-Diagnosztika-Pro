package hu.sx4.diagnosztika

import android.os.Bundle
import android.os.Looper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class DashboardUiTest {
    private val destinations = 0..4
    private fun view(activity: MainActivity, tag: String): View = activity.window.decorView.findViewWithTag(tag)
    private fun field(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    @Test fun startsOnTheMainMenuAndEveryDestinationIsReachableAndReturnsToIt() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals(View.VISIBLE, view(activity, "dashboard-home").visibility)
            for (page in destinations) assertEquals(View.GONE, view(activity, "dashboard-page-$page").visibility)
            for (selected in destinations) {
                view(activity, "dashboard-tab-$selected").performClick()
                assertEquals(View.GONE, view(activity, "dashboard-home").visibility)
                for (page in destinations) assertEquals(if (page == selected) View.VISIBLE else View.GONE,
                    view(activity, "dashboard-page-$page").visibility)
                assertTrue(view(activity, "dashboard-status").isShown)
                assertTrue(view(activity, "dashboard-tab-$selected").isSelected)
                if (selected % 2 == 0) view(activity, "dashboard-menu").performClick() else activity.onBackPressed()
                assertEquals(View.VISIBLE, view(activity, "dashboard-home").visibility)
                assertFalse(activity.isFinishing)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun selectedDestinationIsRestoredIntoANewActivity() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        view(first.get(), "dashboard-tab-2").performClick()
        val state = Bundle(); first.saveInstanceState(state); first.pause().stop().destroy()
        val restored = Robolectric.buildActivity(MainActivity::class.java).create(state).start().restoreInstanceState(state).resume().visible()
        try {
            assertEquals(View.VISIBLE, view(restored.get(), "dashboard-page-2").visibility)
            assertEquals(View.GONE, view(restored.get(), "dashboard-home").visibility)
            assertTrue(view(restored.get(), "dashboard-tab-2").isSelected)
        } finally { restored.pause().stop().destroy() }
    }

    @Test fun navigatingToSavedFilesDoesNotEndAnActiveDemoRecording() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            view(activity, "dashboard-tab-1").performClick()
            MainActivity::class.java.getDeclaredMethod("startRecording").apply { isAccessible = true }.invoke(activity)
            (field(activity, "worker") as ScheduledExecutorService).submit {}.get(3, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val recording = field(activity, "recording")
            assertNotNull(recording)
            view(activity, "dashboard-menu").performClick()
            view(activity, "dashboard-tab-2").performClick()
            assertSame(recording, field(activity, "recording"))
            assertEquals(true, field(activity, "measuring"))
        } finally { controller.pause().stop().destroy() }
    }
}
