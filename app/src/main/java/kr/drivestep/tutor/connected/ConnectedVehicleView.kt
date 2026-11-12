package kr.drivestep.tutor.connected

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
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
import kr.drivestep.tutor.design.showReadable
import kr.drivestep.tutor.journey.PreparationColumns
import kr.drivestep.tutor.journey.PreparationStyle
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
    private val send = button("내비로 목적지 보내기","connected_destination_send") { reviewDestination() }
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
        addView(ScrollView(context).apply { isFillViewport = true; addView(body) },LayoutParams(-1,0,1f))
        body.addView(label("차량에서 받은 상태와 위치를 확인하고, 원하는 목적지를 내비로 보내요.","connected_title",21),full())
        val columns = PreparationColumns(context, breakpoint = 1_100); body.addView(columns,full())
        val vehicle = card(); columns.addView(vehicle)
        vehicle.addView(heading("출발 전 차량 상태"),full())
        vehicle.addView(label("앞좌석 왼쪽의 설정값이에요. 자세와 계기판은 직접 확인해 주세요.",size=19),full())
        vehicle.addView(seats,full()); vehicle.addView(belt,full()); vehicle.addView(warnings,full())
        vehicle.addView(button("차량 정보 연결 설정","connected_vehicle_settings",onVehicleSettings),full())
        vehicle.addView(button("차량 상태 다시 연결","connected_vehicle_retry",onReconnect),full())
        val position = card(); columns.addView(position)
        position.addView(heading("내 위치와 내비 안내"),full())
        val consent = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        consent.addView(label("현재 위치·내비 정보 이용",size=22),LayoutParams(0,-2,1f).apply { marginEnd=dp(16) })
        consent.addView(locationToggle,LayoutParams(dp(80),dp(64))); position.addView(consent,full())
        position.addView(label("좌표와 목적지는 앱 기록에 저장하지 않아요.",size=18),full())
        position.addView(location,full()); position.addView(navigation,full())
        position.addView(button("위치·내비 다시 연결","connected_navigation_retry") { permissionAsked = false; source.stop(); synchronize() },full())
        val destination = card(); body.addView(destination,full())
        destination.addView(heading("목적지 직접 입력"),full())
        destination.addView(label("장소 이름과 좌표를 입력해 내비로 보내세요. 내비에 설정된 기존 목적지가 바뀔 수 있어요.",size=20),full())
        destination.addView(name,full())
        val coordinates = PreparationColumns(context, breakpoint = 1_100); destination.addView(coordinates,full())
        coordinates.addView(latitude); coordinates.addView(longitude)
        destination.addView(send,full()); destination.addView(requestStatus,full())
        body.addView(label("시트와 미러는 차량에서 직접 조절해 주세요.",size=18),full())
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
                "${axis.title}: " + (item.value?.let { "설정값 ${it.raw} · 조절 범위 ${it.min}~${it.max}" } ?: "${quality(item.quality)} · ${item.detail}")
            }
        val beltMessage = when (value.belt.value) { true -> "채워짐 · 벨트가 꼬이지 않았는지도 확인해 주세요"; false -> "채워지지 않음 · 출발 전에 안전벨트를 채워 주세요"; null -> "${quality(value.belt.quality)} · ${value.belt.detail}" }
        belt.text = context.getString(R.string.connected_belt_state,beltMessage)
        val rows = value.warnings.value?.filter { it.active }
        warnings.text = when {
            rows == null -> "차량 경고: ${quality(value.warnings.quality)} · ${value.warnings.detail}"
            rows.isEmpty() -> "현재 전달된 차량 경고가 없어요. 출발 전 주변도 직접 확인해 주세요."
            else -> "차량에 경고 표시가 있어요. 계기판의 안내를 확인해 주세요." + if (value.speechBlocked) "\n차량 경고를 확인할 수 있도록 연습 음성을 멈췄어요." else ""
        }
    }
    private fun acceptNavigation(value: LocationNavigationSnapshot) {
        snapshot = value
        location.text = value.location.value?.let { fix ->
            String.format(Locale.KOREA,"내 위치: 위도 %.5f · 경도 %.5f\n마지막 확인 %s",fix.point.latitude,fix.point.longitude,
                SimpleDateFormat("HH:mm:ss",Locale.KOREA).format(Date(fix.timestampMillis)))
        } ?: "현재 위치: ${quality(value.location.quality)} · ${value.location.detail}"
        val destination = value.destination.value
        val turn = value.turn.value
        val destinationText = destination?.let { context.getString(R.string.connected_destination_state,it.name,it.remainingMeters?.let { m -> "$m m" } ?: "남은 거리 미확인") } ?: value.destination.detail
        val turnText = turn?.let { context.getString(R.string.connected_turn_state,it.description.ifBlank { "내비에서 방향을 확인해 주세요" },it.meters.toString()) } ?: value.turn.detail
        val navigationPositionText = value.navigationPosition.value?.let { String.format(Locale.KOREA,"내비가 확인한 위치: 위도 %.5f · 경도 %.5f",it.latitude,it.longitude) } ?: "내비 위치: ${value.navigationPosition.detail}"
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
            .setMessage("${goal.name}\n위도 ${goal.point.latitude} · 경도 ${goal.point.longitude}\n이 위치를 차량 내비의 목적지로 보내요. 화면 속 연습 코스에는 적용되지 않아요.")
            .setNegativeButton("취소",null).setPositiveButton("목적지 보내기") { _,_ ->
                if (active && locationToggle.isChecked && snapshot?.canRequest == true) source.requestDestination(goal)
            }.showReadable()
    }
    fun close() { closed = true; dialog?.dismiss(); source.close() }
    private fun quality(value: InputValidity) = when (value) { InputValidity.VALID -> "확인됨"; InputValidity.STALE -> "다시 확인 필요"; InputValidity.UNSUPPORTED -> "이 차량에서 확인 불가"; InputValidity.DENIED -> "이용 권한 필요"; InputValidity.ERROR -> "연결 확인 필요"; InputValidity.UNKNOWN -> "확인 전" }
    private fun card() = LinearLayout(context).apply {
        orientation=VERTICAL; background=PreparationStyle.surface(context); setPadding(dp(20),dp(18),dp(20),dp(18))
    }
    private fun heading(value: String) = label(value,size=25).apply { setTypeface(typeface,Typeface.BOLD) }
    private fun label(value: String,key: String = "",size: Int = 21) = TextView(context).apply {
        text = value; textSize = size.toFloat(); contentDescription = key; setTextColor(PleosUi.TEXT); setPadding(dp(8),dp(8),dp(8),dp(8))
        setLineSpacing(dp(3).toFloat(),1f)
    }
    private fun input(hintText: String,key: String,numeric: Boolean) = EditText(context).apply {
        hint = hintText; contentDescription = key; textSize = 21f; setTextColor(PleosUi.TEXT); setHintTextColor(PleosUi.SECONDARY)
        minHeight=dp(64); setPadding(dp(16),dp(14),dp(16),dp(14)); background=PreparationStyle.surface(context, PleosUi.BACKGROUND, true)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT
        isSingleLine = true; importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
    }
    private fun button(value: String,key: String,action: ()->Unit) = PracticeButton(context).apply {
        text = value; textSize = 20f; isAllCaps = false; contentDescription = key; PreparationStyle.button(this,key=="connected_destination_send")
        minHeight=dp(64); minimumHeight=dp(64); minWidth=0; minimumWidth=0; setPadding(dp(18),dp(12),dp(18),dp(12))
        setOnClickListener { if (active && !closed) action() }
    }
    private fun full(height: Int = -2) = LayoutParams(-1,if (height < 0) height else dp(height)).apply { bottomMargin = dp(8) }
    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
}
