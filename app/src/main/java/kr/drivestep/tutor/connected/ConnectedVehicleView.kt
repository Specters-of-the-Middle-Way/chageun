package kr.drivestep.tutor.connected

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kr.drivestep.tutor.R
import kr.drivestep.tutor.design.PleosSwitch
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.design.PracticeButton
import kr.drivestep.tutor.workflow.InputValidity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Created in code with required host consent/permission callbacks; never inflated from XML. */
@SuppressLint("ViewConstructor")
class ConnectedVehicleView(context: Context, private val vehicleEnabled: ()->Boolean,
    private val onVehicleSettings: ()->Unit, private val onReconnect: ()->Unit,
    private val onLocationPermission: ()->Unit) : LinearLayout(context) {
    private val prefs = context.getSharedPreferences("connected_ui_v1",Context.MODE_PRIVATE)
    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val seats = label("차량 상태 연결 전","connected_seats")
    private val belt = label("버클 상태 미확인","connected_belt")
    private val warnings = label("경고 미확인","connected_warnings")
    private val location = label("위치 연결 꺼짐","connected_location")
    private val navigation = label("내비 연결 꺼짐","connected_navigation")
    private val requestStatus = label("목적지 요청 전","connected_request_status")
    private val name = input("목적지 이름","connected_destination_name",false)
    private val latitude = input("목적지 위도 (-90~90)","connected_destination_latitude",true)
    private val longitude = input("목적지 경도 (-180~180)","connected_destination_longitude",true)
    private val send = button("입력한 목적지를 내비로 보내기","connected_destination_send") { reviewDestination() }
    private var active = false
    private var closed = false
    private var permissionAsked = false
    private var dialog: AlertDialog? = null
    private var snapshot: LocationNavigationSnapshot? = null
    private var inputError: String? = null
    private val source = LocationNavigationSource(context,::acceptNavigation)
    private val locationToggle = PleosSwitch(context).apply {
        contentDescription = "connected_location_consent"
        isChecked = prefs.getBoolean("location_consent",false)
        setOnCheckedChangeListener { _,checked ->
            prefs.edit().putBoolean("location_consent",checked).apply()
            permissionAsked = false
            if (!checked) { dialog?.dismiss(); source.stop() }
            synchronize()
        }
    }
    init {
        orientation = VERTICAL
        addView(ScrollView(context).apply { addView(body) },LayoutParams(-1,-1))
        body.addView(label("차량 상태 · 위치와 내비","connected_title",26),full())
        body.addView(label("출처: Pleos SDK 수신값 · 에뮬레이터에서는 가상 콘솔의 설정값이에요. 실차 검증 결과가 아니에요."),full())
        body.addView(label("확인 좌석: 앞좌석 왼쪽 · 위치 값은 SDK 단위이며 자세의 적합성을 평가하지 않아요."),full())
        body.addView(seats,full()); body.addView(belt,full()); body.addView(warnings,full())
        body.addView(button("차량 신호 연결 설정","connected_vehicle_settings",onVehicleSettings),full())
        body.addView(button("차량 상태 다시 연결","connected_vehicle_retry",onReconnect),full())
        val consent = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        consent.addView(label("현재 위치·내비 정보 이용\n좌표와 목적지를 앱 기록에 저장하지 않아요. 각 SDK의 에뮬레이터 위치 설정은 서로 다를 수 있어요."),LayoutParams(0,-2,1f))
        consent.addView(locationToggle,LayoutParams(dp(80),dp(56))); body.addView(consent,full())
        body.addView(location,full()); body.addView(navigation,full())
        body.addView(button("위치·내비 다시 연결","connected_navigation_retry") { permissionAsked = false; source.stop(); synchronize() },full())
        body.addView(label("직접 지정한 목적지를 내비에 요청해요. 기존 내비 목적지가 바뀔 수 있어요. 초보자 코스 자동 추천은 아직 제공하지 않아요."),full())
        body.addView(name,full(56)); body.addView(latitude,full(56)); body.addView(longitude,full(56))
        body.addView(send,full(60)); body.addView(requestStatus,full())
        body.addView(label("향후 계획: ADAS SDK(공모전 사용 불가), 차량 카메라 원본·주변 객체·실제 거리 센서(공개 연결 경로 미확보), 미러 각도 자동 조절. 가상 연습과 별도로 표시해요."),full())
        send.isEnabled = false
    }
    val hasLocationConnection get() = source.isRunning
    fun setActive(value: Boolean) {
        if (active == value || closed) return
        active = value
        locationToggle.isEnabled = value
        if (!value) { dialog?.dismiss(); dialog = null; source.stop(); name.text.clear(); latitude.text.clear(); longitude.text.clear() }
        else synchronize()
    }
    fun permissionResult() { if (active) { source.stop(); synchronize(requestPermission = false) } }
    private fun synchronize(requestPermission: Boolean = true) {
        if (!active || !locationToggle.isChecked) {
            source.stop(); location.text = "위치·내비 연결 꺼짐 · 선택 동의 후 이용할 수 있어요"; send.isEnabled = false; return
        }
        if (context.checkSelfPermission(LocationNavigationSource.LOCATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            location.text = "위치 권한이 필요해요. 거부해도 가상 연습은 이용할 수 있어요."
            if (requestPermission && !permissionAsked) { permissionAsked = true; onLocationPermission() }
            // Navigation has its own normal permission; its data must still be explicitly opted in.
        }
        source.start()
    }
    fun acceptVehicle(value: ReadinessSnapshot) {
        seats.text = if (!vehicleEnabled()) "차량 신호 연결 동의 꺼짐 · 운전자 설정에서 선택할 수 있어요."
            else SeatAxis.entries.joinToString("\n") { axis ->
                val item = value.seats.getValue(axis).at(SystemClock.elapsedRealtime())
                "${axis.title}: " + (item.value?.let { "${it.raw} (SDK 범위 ${it.min}~${it.max})" } ?: "${quality(item.quality)} · ${item.detail}")
            }
        val beltMessage = when (value.belt.value) { true -> "체결 신호 수신 · 벨트 꼬임·착용 자세는 직접 확인"; false -> "미체결 신호 수신 · 준비 항목을 다시 확인해 주세요"; null -> "${quality(value.belt.quality)} · ${value.belt.detail}" }
        belt.text = context.getString(R.string.connected_belt_state,beltMessage)
        val rows = value.warnings.value?.filter { it.active }
        warnings.text = when {
            rows == null -> "차량 경고: ${quality(value.warnings.quality)} · ${value.warnings.detail}"
            rows.isEmpty() -> "차량 경고: 이번 SDK 응답에 켜진 표시 없음 · 주변 안전을 뜻하지 않아요"
            else -> "켜진 차량 표시: " + rows.joinToString { it.type } + if (value.speechBlocked) "\n차량 경고 우선 · 교육 음성을 중지했어요" else ""
        }
    }
    private fun acceptNavigation(value: LocationNavigationSnapshot) {
        snapshot = value
        location.text = value.location.value?.let { fix ->
            String.format(Locale.KOREA,"FusedLocation 수신: %.6f, %.6f\n출처 시각 %s · 신뢰도 원본 %.2f",fix.point.latitude,fix.point.longitude,
                SimpleDateFormat("HH:mm:ss",Locale.KOREA).format(Date(fix.timestampMillis)),fix.confidence)
        } ?: "현재 위치: ${quality(value.location.quality)} · ${value.location.detail}"
        val destination = value.destination.value
        val turn = value.turn.value
        val destinationText = destination?.let { context.getString(R.string.connected_destination_state,it.name,it.remainingMeters?.let { m -> "$m m" } ?: "남은 거리 미확인") } ?: value.destination.detail
        val turnText = turn?.let { context.getString(R.string.connected_turn_state,it.type,it.meters.toString(),it.description) } ?: value.turn.detail
        val navigationPositionText = value.navigationPosition.value?.let { String.format(Locale.KOREA,"내비 기준 위치: %.6f, %.6f · 수신 시각 기준",it.latitude,it.longitude) } ?: "내비 기준 위치: ${value.navigationPosition.detail}"
        navigation.text = context.getString(R.string.connected_navigation_state,listOf(value.navigation,navigationPositionText).joinToString("\n"),destinationText,turnText)
        requestStatus.text = inputError ?: value.requestMessage
        send.isEnabled = active && locationToggle.isChecked && value.canRequest
    }
    private fun reviewDestination() {
        val goal = NavigationGoal(name.text.toString().trim(),GeoPoint(latitude.text.toString().toDoubleOrNull() ?: Double.NaN,longitude.text.toString().toDoubleOrNull() ?: Double.NaN))
        if (!goal.valid) { inputError = "목적지 이름과 올바른 위도·경도를 입력해 주세요."; requestStatus.text = inputError; return }
        inputError = null
        if (!active || snapshot?.canRequest != true) return
        dialog = AlertDialog.Builder(context).setTitle("내비 목적지를 변경할까요?")
            .setMessage("${goal.name}\n위도 ${goal.point.latitude} · 경도 ${goal.point.longitude}\n직접 입력한 좌표를 차량 내비에 전달해요. 가상 추천 코스와는 별개예요.")
            .setNegativeButton("취소",null).setPositiveButton("목적지 요청") { _,_ ->
                if (active && locationToggle.isChecked && snapshot?.canRequest == true) source.requestDestination(goal)
            }.show()
    }
    fun close() { closed = true; dialog?.dismiss(); source.close() }
    private fun quality(value: InputValidity) = when (value) { InputValidity.VALID -> "수신됨"; InputValidity.STALE -> "오래됨"; InputValidity.UNSUPPORTED -> "미지원"; InputValidity.DENIED -> "권한 없음"; InputValidity.ERROR -> "오류"; InputValidity.UNKNOWN -> "미확인" }
    private fun label(value: String,key: String = "",size: Int = 18) = TextView(context).apply {
        text = value; textSize = size.toFloat(); contentDescription = key; setTextColor(PleosUi.TEXT); setPadding(dp(8),dp(8),dp(8),dp(8))
    }
    private fun input(hintText: String,key: String,numeric: Boolean) = EditText(context).apply {
        hint = hintText; contentDescription = key; textSize = 18f; setTextColor(PleosUi.TEXT); setHintTextColor(PleosUi.SECONDARY)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT
        isSingleLine = true; importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
    }
    private fun button(value: String,key: String,action: ()->Unit) = PracticeButton(context).apply {
        text = value; textSize = 18f; isAllCaps = false; contentDescription = key; PleosUi.decorate(this,false)
        setOnClickListener { if (active && !closed) action() }
    }
    private fun full(height: Int = -2) = LayoutParams(-1,if (height < 0) height else dp(height)).apply { bottomMargin = dp(8) }
    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
}
