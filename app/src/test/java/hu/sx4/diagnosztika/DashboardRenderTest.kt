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
    @Test fun rendersAllFourDestinationsAtPhoneWidth() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            val dir = File(System.getProperty("sx4.previewDir", "build/reports/screenshots")).apply { mkdirs() }
            for (page in 0..3) {
                activity.window.decorView.findViewWithTag<View>("dashboard-tab-$page").performClick()
                content.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                content.layout(0, 0, 360, 800)
                val tab = activity.window.decorView.findViewWithTag<View>("dashboard-tab-$page")
                assertTrue("Navigation must remain on screen", tab.bottom <= 800)
                assertTrue("Navigation touch targets must be at least 48dp", tab.height >= 48)
                val bitmap = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)
                content.draw(Canvas(bitmap))
                File(dir, "dashboard-$page.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
        } finally { controller.pause().stop().destroy() }
    }
}
