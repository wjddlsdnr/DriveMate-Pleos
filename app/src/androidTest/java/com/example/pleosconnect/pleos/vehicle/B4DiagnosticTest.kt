package com.example.pleosconnect.pleos.vehicle

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.HvacTemperatureConfigArray
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.HvacTemperatureValueListener
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Read-only diagnostics; these tests contain no actuator setters or synthetic consent. */
@RunWith(AndroidJUnit4::class)
class B4DiagnosticTest {
    private suspend fun <T> read(label: String, call: ((T?) -> Unit, (Exception) -> Unit) -> Unit): T? {
        val result = CompletableDeferred<T?>()
        return try {
            call({ result.complete(it) }, { error ->
                val code = Regex("ERROR_[A-Z_]{1,60}").find(error.message.orEmpty())?.value ?: "not-exposed"
                val metadata = error.message.orEmpty().replace(Regex("[A-Za-z0-9_./+=-]{24,}"), "[redacted]")
                    .replace(Regex("\\d+(\\.\\d+)?"), "#").replace(Regex("[\\r\\n]"), " ").take(180)
                Log.i("DriveMateB4Diagnostic", "$label failureClass=${error.javaClass.name},code=$code,metadata=$metadata")
                result.complete(null)
            })
            withTimeoutOrNull(3_000) { result.await() }
        } finally { result.cancel() }
    }
    @Test fun compareTemperatureReadPathsWithoutWriting() = runBlocking(Dispatchers.Main.immediate) {
        val vehicle = Vehicle(InstrumentationRegistry.getInstrumentation().targetContext)
        val listeners = mutableListOf<Pair<SeatArea, HvacTemperatureValueListener>>()
        val values = AtomicInteger(); val errors = AtomicInteger()
        try {
            vehicle.initialize()
            val hvac = vehicle.getHVAC()
            val capability = read<Triple<CapabilityConfig, Map<SeatArea, AreaBounds<Float>>, HvacTemperatureConfigArray?>>("capability") { ok, fail ->
                hvac.checkHvacTemperatureCapabilities({ config, areas, units -> ok(Triple(config, areas, units)) }, fail)
            }
            if (capability == null) { Log.i("DriveMateB4Diagnostic", "temperatureCapability=false"); return@runBlocking }
            Log.i("DriveMateB4Diagnostic", "temperatureAreas=${capability.second.size},incrementCelsiusValid=${capability.third?.incrementCelsius?.let { it > 0 } == true}")
            for ((index, area) in capability.second.keys.withIndex()) {
                val celsius = read<Float>("set-celsius-$index") { ok, fail -> hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, ok, fail) }
                val fahrenheit = read<Float>("set-fahrenheit-$index") { ok, fail -> hvac.getHvacTemperature(area, TemperatureUnit.FAHRENHEIT, ok, fail) }
                Log.i("DriveMateB4Diagnostic", "temperatureArea=$index,celsiusReceived=${celsius != null},fahrenheitReceived=${fahrenheit != null}")
                val listener = object : HvacTemperatureValueListener {
                    override fun onHvacTemperatureUpdated(celsius: Float?, fahrenheit: Float?) {
                        if (celsius?.isFinite() == true && fahrenheit?.isFinite() == true) values.incrementAndGet()
                        val bounds = capability.second.getValue(area)
                        Log.i("DriveMateB4Diagnostic", "temperatureArea=$index,listenerCelsiusInBounds=${celsius?.let { it.isFinite() && it in bounds.minValue..bounds.maxValue } == true},listenerBothUnits=${celsius != null && fahrenheit != null}")
                    }
                    override fun onFailed(e: Exception) { errors.incrementAndGet(); Log.i("DriveMateB4Diagnostic", "temperatureArea=$index,listenerFailureClass=${e.javaClass.name}") }
                }
                hvac.registerHvacTemperature(area, listener); listeners += area to listener
            }
            delay(800)
            Log.i("DriveMateB4Diagnostic", "temperatureEvents=${values.get()},temperatureListenerErrors=${errors.get()},nativeWrites=0")
        } finally {
            listeners.forEach { (area, listener) -> runCatching { vehicle.getHVAC().unregisterHvacTemperature(area, listener) } }
            vehicle.release()
        }
    }
    @Test fun readOnlyConcreteEmulatorPlan() = runBlocking {
        val backend = PleosVehicleSettingsBackend(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val inspection = withTimeout(15_000) { backend.inspect() }
            Log.i("DriveMateB4Plan", "hardware=${Build.HARDWARE},gear=${inspection.signals.gear.value},parkingBrake=${inspection.signals.parkingBrake.value},speedProblem=${inspection.signals.speedMetersPerSecond.problem}")
            for (kind in VehicleSettingKind.entries) {
                val setting = inspection.settings.firstOrNull { it.key.kind == kind } ?: continue
                val target = if (kind == VehicleSettingKind.CLIMATE_POWER) 1.0 - setting.value else
                    (setting.value + 1).takeIf(setting::accepts) ?: (setting.value - 1).takeIf(setting::accepts)
                Log.i("DriveMateB4Plan", "kind=$kind,area=${setting.key.area},original=${setting.value},proposed=$target,restoration=${setting.value},nativeWrites=0")
            }
        } finally { backend.close() }
    }
}
