package hu.sx4.diagnosztika

import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35])
@LooperMode(LooperMode.Mode.PAUSED)
class DriveServiceTest {
    private fun get(target:Any,name:String)=target.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(target)
    private fun settle(s:DriveService){(get(s,"worker") as ScheduledExecutorService).submit{}.get(3,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()}
    private fun tick(s:DriveService){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));(get(s,"poll") as ScheduledFuture<*>).get(3,TimeUnit.SECONDS);settle(s)}
    private fun begin(s:DriveService){s.onStartCommand(Intent(s,DriveService::class.java).setAction(DriveService.START).putExtra("demo",true).putExtra("oxygen",true),0,1);settle(s)}
    private fun stop(s:DriveService){s.onStartCommand(Intent(s,DriveService::class.java).setAction(DriveService.STOP),0,2);settle(s)}
    @Test fun backgroundedAndRecreatedActivityDoesNotOwnTheRecorder() {
        val c=Robolectric.buildService(DriveService::class.java).create();val s=c.get()
        try {
            begin(s);tick(s)
            val id=DriveService.state.id
            val activity=Robolectric.buildActivity(MainActivity::class.java).setup()
            activity.pause().stop().destroy()
            tick(s)
            assertTrue(DriveService.state.active);assertEquals(id,DriveService.state.id)
            assertTrue(DriveService.state.events.isNotEmpty())
            val restored=Robolectric.buildActivity(MainActivity::class.java).setup()
            try {shadowOf(Looper.getMainLooper()).idle();assertTrue((get(restored.get(),"drivingInfo") as TextView).text.contains("DEMO"))}
            finally{restored.pause().stop().destroy()}
            stop(s)
            assertFalse(DriveService.state.active)
            val file=MeasurementLog.saved(File(s.filesDir,"measurements")).single()
            assertTrue(file.readText().contains("\"DEMO\",\"DEMO\""));assertTrue(file.readText().contains("\"END\""))
        } finally {c.destroy()}
    }
    @Test fun notificationStopClosesFileAndReleasesWakeLockWithoutRestarting() {
        val c=Robolectric.buildService(DriveService::class.java).create();val s=c.get()
        try {
            begin(s);val wake=get(s,"wake") as PowerManager.WakeLock
            assertTrue(wake.isHeld);assertNotNull(shadowOf(s).lastForegroundNotification)
            assertEquals(android.app.Service.START_NOT_STICKY,s.onStartCommand(null,0,3))
            assertTrue(DriveService.state.active)
            stop(s);assertFalse(wake.isHeld);assertFalse(DriveService.state.active)
            assertEquals(1,MeasurementLog.saved(File(s.filesDir,"measurements")).size)
        } finally {c.destroy()}
    }
    @Test fun liveModeKeepsMeasuringAcrossActivityLifecycleWithoutWritingCsv() {
        val c=Robolectric.buildService(DriveService::class.java).create();val s=c.get()
        try {
            s.onStartCommand(Intent(s,DriveService::class.java).setAction(DriveService.START).putExtra("demo",true)
                .putExtra("oxygen",false).putExtra("record",false),0,1);settle(s)
            repeat(6){tick(s)}
            val state=DriveService.state
            assertTrue(state.active);assertFalse(state.recording)
            assertTrue(0x0C in state.sequence);assertTrue(state.events.any{it.pid==0x0C && it.sample!=null})
            assertTrue(state.avgPollMs() > 0)
            // Screen-off/backgrounding destroys only the activity; the service keeps polling.
            val activity=Robolectric.buildActivity(MainActivity::class.java).setup();activity.pause().stop().destroy()
            val before=DriveService.state.events.size;tick(s)
            assertTrue(DriveService.state.active);assertTrue(DriveService.state.events.size >= before)
            stop(s)
            assertFalse(DriveService.state.active)
            assertTrue(MeasurementLog.saved(File(s.filesDir,"measurements")).isEmpty())
            assertTrue(DriveService.state.message.contains("nem készült CSV"))
        } finally {c.destroy()}
    }
    @Test fun repeatedStartAndStopNeverCreateDuplicateFilesOrLoseFinalization() {
        val c=Robolectric.buildService(DriveService::class.java).create();val s=c.get()
        try {
            begin(s);val id=DriveService.state.id;begin(s);assertEquals(id,DriveService.state.id)
            stop(s);stop(s)
            assertEquals(1,MeasurementLog.saved(File(s.filesDir,"measurements")).size)
        } finally {c.destroy()}
    }
}
