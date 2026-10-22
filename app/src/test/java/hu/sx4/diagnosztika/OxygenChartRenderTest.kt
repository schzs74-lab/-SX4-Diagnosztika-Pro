package hu.sx4.diagnosztika

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OxygenChartRenderTest {
    @Test fun rendersAnExplicitlySimulatedTwoChannelPreview() {
        val context=RuntimeEnvironment.getApplication()
        val chart=OxygenChartView(context)
        chart.start(true,setOf(20,21),0)
        for (at in 0L..60_000 step 400) for (pid in listOf(20,21)) {
            chart.sample(LiveSample.decode(pid,DemoSamples.data(pid,at)),at)
        }
        chart.tick(60_000)
        val density=context.resources.displayMetrics.density
        val height=(280*density).toInt()
        chart.layout(0,0,(360*density).toInt(),height)
        val bitmap=Bitmap.createBitmap(chart.width,height,Bitmap.Config.ARGB_8888)
        chart.draw(Canvas(bitmap))
        val dir=File(System.getProperty("sx4.previewDir", "build/reports/screenshots")).apply { mkdirs() }
        File(dir,"lambda-chart-demo.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        assertTrue(chart.contentDescription.toString().contains("DEMO / SZIMULÁLT"))
        bitmap.recycle()
    }
}
