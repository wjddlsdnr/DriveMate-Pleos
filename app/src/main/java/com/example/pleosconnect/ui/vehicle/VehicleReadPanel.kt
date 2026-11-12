package com.example.pleosconnect.ui.vehicle

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.pleosconnect.domain.SafeParking
import com.example.pleosconnect.pleos.vehicle.*
import kotlin.math.abs

@Composable
fun VehicleReadPanel(state: VehicleReadUiState, foreground: Boolean, onEnabled: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("실제 차량 상태 조회 · 제어 없음", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { onEnabled(!state.enabled) }, enabled = foreground,
                modifier = Modifier.testTag("vehicle-read-toggle").heightIn(min = 56.dp)) {
                Text(if (state.enabled) "차량 조회 끄기" else "차량 상태 조회하기")
            }
            Text("이 카드에서는 조회만 수행합니다. 공조·창문·좌석 설정을 변경하지 않습니다.")
            if (state.enabled) {
                if (state.busy) Text("차량 정보를 읽는 중입니다.")
                val data = state.snapshot
                Text("기어: ${display(data.gear, state.nowMillis) { when (it) {
                    ReadGear.PARK -> "P"; ReadGear.REVERSE -> "R"; ReadGear.NEUTRAL -> "N"; ReadGear.DRIVE -> "D"; ReadGear.OTHER -> "기타/미확인"
                } }}")
                Text("속도: ${display(data.speedMetersPerSecond, state.nowMillis) { "%.1f km/h".format(abs(it) * 3.6f) }}")
                Text("주차 브레이크: ${display(data.parkingBrake, state.nowMillis) { if (it) "체결" else "해제" }}")
                Text("브레이크 페달: ${display(data.brakePedal, state.nowMillis) { if (it) "누름" else "놓음" }}")
                Text(if (state.parking == SafeParking.NOT_PARKED) "안전 주차: 주행/비주차 신호 감지" else "안전 주차: 미확인",
                    modifier = Modifier.testTag("vehicle-parking-status"))
                Text("P단·속도 0·주차 브레이크만으로 장소의 안전성을 확인할 수 없습니다.")
                Text("공조 설정 온도(℃): ${display(data.climate, state.nowMillis) { areas(it, state.nowMillis) }}")
                Text("공조 전원: ${display(data.climatePower, state.nowMillis) { areas(it, state.nowMillis) }}")
                if (data.climatePower.fresh(state.nowMillis) && data.climatePower.value?.isNotEmpty() == true &&
                    data.climatePower.value.all { it.fresh(state.nowMillis) && it.value == "꺼짐" })
                    Text("공조 전원이 꺼져 있습니다. 차량에 따라 온도값은 전원이 켜진 뒤 제공될 수 있습니다.")
                Text("창문 위치(차량 원시 단위): ${display(data.windows, state.nowMillis) { areas(it, state.nowMillis) }}")
                Text("창문 수치는 차량별 범위입니다. 백분율이나 열림 방향으로 환산하지 않습니다.")
            }
        }
    }
}
private fun <T> display(read: VehicleReading<T>, now: Long, format: (T) -> String): String = when {
    read.problem != null -> problem(read.problem)
    read.value == null -> "미수신"
    !read.fresh(now) -> "오래된 정보 · 미확인"
    else -> format(read.value)
}
private fun problem(value: VehicleReadProblem) = when (value) {
    VehicleReadProblem.UNSUPPORTED -> "미지원/조회 불가"
    VehicleReadProblem.PERMISSION_DENIED -> "조회 권한 거부"
    VehicleReadProblem.SERVICE_UNAVAILABLE -> "서비스 오류/값 미수신"
    VehicleReadProblem.TIMEOUT -> "조회 시간 초과"
    VehicleReadProblem.INVALID_DATA -> "유효하지 않은 값"
}
private fun areas(values: List<VehicleAreaValue>, now: Long) = values.joinToString(" / ") {
    "${areaLabel(it.area)}: ${it.problem?.let(::problem) ?: if (it.fresh(now)) it.value else "오래된 정보 · 미확인"}" +
        if (it.minimum != null && it.maximum != null) " [${it.minimum}~${it.maximum}]" else ""
}
internal fun areaLabel(area: String) = area.split(", ").joinToString("·") { token ->
    val row = Regex("ROW_([1-3])_(LEFT|CENTER|RIGHT)").matchEntire(token)
    if (row != null) "${row.groupValues[1]}열 " + when (row.groupValues[2]) { "LEFT" -> "왼쪽"; "RIGHT" -> "오른쪽"; else -> "가운데" }
    else when (token) { "FRONT_WINDSHIELD" -> "앞 유리"; "REAR_WINDSHIELD" -> "뒷유리";
        "ROOF_TOP_1" -> "지붕 1"; "ROOF_TOP_2" -> "지붕 2"; else -> "기타 영역" }
}
