package com.example.pleosconnect.pleos.vehicle

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Native inspect/subscribe/release only. Never calls write(). */
@RunWith(AndroidJUnit4::class)
class VehicleControlInspectionSmokeTest {
    @Test fun inspectSupportedSettingsAndSubscribeWithoutChangingAnything() = runBlocking {
        val backend = PleosVehicleSettingsBackend(InstrumentationRegistry.getInstrumentation().targetContext)
        val events = AtomicInteger(); val failures = AtomicInteger()
        try {
            val inspection = withTimeout(15_000) { backend.inspect() }
            backend.monitor(inspection.settings) { events.incrementAndGet(); if (it.value == null) failures.incrementAndGet() }
            delay(500)
            Log.i("DriveMateControlProbe", "powerSettings=${inspection.settings.count { it.key.kind == VehicleSettingKind.CLIMATE_POWER }}," +
                "windowSettings=${inspection.settings.count { it.key.kind == VehicleSettingKind.WINDOW_POSITION }}," +
                "monitorEvents=${events.get()},monitorFailures=${failures.get()},nativeWrites=0")
            assertTrue("At least one READ_WRITE setting with a readable original must exist", inspection.settings.isNotEmpty())
            assertEquals(0, failures.get())
        } finally { backend.close() }
    }
}
