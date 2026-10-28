package hu.sx4.diagnosztika

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DashboardRenderTest {
    @Test fun rendersTheMenuAndAllDestinationsAtPhoneWidth() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            val dir = File(System.getProperty("sx4.previewDir", "build/reports/screenshots")).apply { mkdirs() }
            fun find(tag: String) = activity.window.decorView.findViewWithTag<View>(tag)
            fun render(name: String) {
                content.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                content.layout(0, 0, 360, 800)
                val bitmap = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)
                content.draw(Canvas(bitmap))
                File(dir, "dashboard-$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
            render("menu")
            for (page in 0..4) {
                val tile = find("dashboard-tab-$page")
                assertTrue("Menu tiles must stay on screen", tile.isShown && tile.bottom <= 800)
                assertTrue("Menu touch targets must be at least 48dp", tile.height >= 48 && tile.width >= 48)
            }
            val lights = find("dashboard-lights")
            assertTrue("Status lights must stay on screen", lights.isShown && lights.bottom <= 800)
            for (page in 0..4) {
                find("dashboard-menu").takeIf { it.isShown }?.performClick()
                find("dashboard-tab-$page").performClick()
                render("$page")
                val back = find("dashboard-menu")
                assertTrue("Menu button must be visible", back.isShown && back.height >= 48)
            }
        } finally { controller.pause().stop().destroy() }
    }
}
