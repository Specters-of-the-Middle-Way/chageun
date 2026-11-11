package kr.drivestep.tutor.connected

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kr.drivestep.tutor.workflow.InputValidity

data class NavigationTurn(val type: String, val meters: Double, val description: String)
data class LocationNavigationSnapshot(val location: Observed<LocationFix>, val navigation: String, val navigationPosition: Observed<GeoPoint>,
    val destination: Observed<NavigationDestination>, val turn: Observed<NavigationTurn>,
    val canRequest: Boolean, val phase: RequestPhase, val requestMessage: String)

/** Explicitly opted-in, foreground-only SDK inputs. No coordinates/history are persisted. */
class LocationNavigationSource(context: Context, private val onUpdate: (LocationNavigationSnapshot)->Unit) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var fused: FusedLocation? = null
    private var locationListener: FusedLocationListener? = null
    private var navi: NaviHelper? = null
    private var naviListener: NaviHelperEventListener? = null
    private var running = false
    private var generation = 0L
    private var locationSequence = 0L
    private var location = Observed<LocationFix>()
    private var navigationPosition = Observed<GeoPoint>()
    private var destination = Observed<NavigationDestination>()
    private var turn = Observed<NavigationTurn>()
    private var navigation = "연결 전"
    private var naviReady = false
    private var hasRoute: Boolean? = null
    private val request = NavigationRequestState()
    private var interruptedRequest: String? = null
    private var ticks = 0
    private val deadlines = mutableMapOf<String,Runnable>()
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            request.expire(now())
            if (++ticks % 3 == 0 && naviReady) queryNavigation()
            publish(); main.postDelayed(this,1_000)
        }
    }
    val isRunning get() = running
    fun start() {
        if (running) return
        running = true; val token = ++generation
        location = Observed(detail = "위치 응답 대기"); navigation = "내비 연결 중"
        request.reset(); hasRoute = null; ticks = 0
        startLocation(token); startNavigation(token); main.post(ticker)
    }
    private fun startLocation(token: Long) {
        if (app.checkSelfPermission(LOCATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            location = Observed(quality = InputValidity.DENIED,detail = "위치 권한이 꺼져 있어요"); publish(); return
        }
        try {
            val service = FusedLocation(app)
            if (!service.isSupported()) { location = Observed(quality = InputValidity.UNSUPPORTED,detail = "FusedLocation 서비스 미지원"); publish(); return }
            fused = service; service.initialize()
            arm("location",10_000,token) {
                location = Observed(quality = InputValidity.ERROR,detail = "위치 응답 시간 초과 · 다시 연결해 주세요")
                stopLocation()
            }
            val listener = object : FusedLocationListener {
                override fun onChanged(location: LocationData?) = dispatch(token) { if (fused != null) { locationSequence++; receiveLocation(location) } }
                override fun onError(exception: Exception) = dispatch(token) { if (fused != null) locationError(exception) }
            }
            locationListener = listener; service.registerFusedLocationCallback(listener)
            val before = locationSequence
            service.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(location: LocationData?) = dispatch(token) { if (fused != null && locationSequence == before) receiveLocation(location) }
                override fun onError(exception: Exception) = dispatch(token) { if (fused != null && locationSequence == before) locationError(exception) }
            })
        } catch (e: Exception) { locationError(e) }
    }
    private fun receiveLocation(value: LocationData?) {
        clear("location")
        location = locationObservation(value?.let { LocationFix(GeoPoint(it.latitude,it.longitude),it.timestampMillis,it.confidence) },now(),System.currentTimeMillis())
        publish()
    }
    private fun locationError(e: Exception) {
        clear("location"); location = Observed(quality = if (e is SecurityException) InputValidity.DENIED else InputValidity.ERROR,
            detail = if (e is SecurityException) "위치 권한 없음" else "위치 조회 실패 · 개인정보 설정과 서비스를 확인해 주세요")
        stopLocation(); publish()
    }
    private fun startNavigation(token: Long) {
        try {
            val helper = NaviHelper(app); navi = helper
            val listener = object : NaviHelperEventListener {
                override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = navigationEvent(token) {
                    clear("position")
                    if (app.checkSelfPermission(LOCATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                        navigationPosition=Observed(quality=InputValidity.DENIED,detail="위치 권한 없음 · 내비 기준 위치도 표시하지 않아요");publish();return@navigationEvent
                    }
                    val point=GeoPoint(currentLocationInfo.latitude,currentLocationInfo.longitude)
                    navigationPosition = if (point.valid) Observed(point,InputValidity.VALID,now(),"NaviHelper 위치 · 수신 시각 기준")
                        else Observed(quality=InputValidity.ERROR,detail="내비 위치 값 오류")
                    publish()
                }
                override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = navigationEvent(token) {
                    clear("route")
                    // Explicit guiding mode identifies an active route. isRouting can describe
                    // the provider's route-search operation and is not a guidance receipt.
                    hasRoute = routeStateInfo.naviMode in setOf(NaviMode.ROUTE_GUIDING,NaviMode.SIMULATION_GUIDING)
                    request.activeRoute(hasRoute == true,now())
                    navigation = "NaviHelper 수신 · " + when (routeStateInfo.naviMode) {
                        NaviMode.ROUTE_GUIDING -> "경로 안내 중"
                        NaviMode.SIMULATION_GUIDING -> "내비 모의 안내 중"
                        NaviMode.SAFE_GUIDING -> if (routeStateInfo.isRouting) "경로 탐색 중" else "설정된 경로 없음"
                        NaviMode.NOT_WORKING -> "내비 준비 중"
                    }
                    if (hasRoute == false) { clear("destination"); clear("turn"); destination = Observed(detail = "설정된 경로 없음"); turn = Observed(detail = "다음 안내 없음") }
                    publish()
                }
                override fun onDestinationInfo(destinationInfo: DestinationInfo) = navigationEvent(token) {
                    clear("destination")
                    val point = GeoPoint(destinationInfo.latitude,destinationInfo.longitude)
                    if (hasRoute == true) {
                        destination = navigationDestination(destinationInfo.name,point,destinationInfo.remainDistance,destinationInfo.remainTime,now())
                        if (destination.value != null) request.destination(point,now())
                        else request.evidenceUnavailable("내비 목적지 응답이 미확인 상태여서 이전 요청 확인을 해제했어요.")
                    }
                    publish()
                }
                override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = navigationEvent(token) {
                    clear("turn")
                    val next = tbtInfoList.firstOrNull()
                    turn = if (hasRoute == true && next != null && next.distance.isFinite() && next.distance >= 0)
                        Observed(NavigationTurn(next.type.name,next.distance,next.description),InputValidity.VALID,now(),"NaviHelper TBT 수신")
                    else Observed(detail = "다음 안내 없음 · 통행 가능 판단이 아니에요")
                    publish()
                }
                override fun onRouteStarted(routeStartInfo: RouteStartInfo) = navigationEvent(token) { if (routeStartInfo.distance >= 0 && routeStartInfo.duration >= 0) request.routingStarted(now()); queryNavigation(); publish() }
                override fun onRoutingCompleted() = navigationEvent(token) {
                    request.routingCompleted(now()); hasRoute = null; turn = Observed(); queryNavigation(); publish()
                }
                override fun onReRouteEnded() = navigationEvent(token) { invalidate("내비 경로가 재탐색되어 이전 확인을 해제했어요"); queryNavigation() }
                override fun onDestinationChanged() = navigationEvent(token) {
                    if (request.phase == RequestPhase.CONFIRMED) request.fail("목적지 변경 · 이전 요청 확인 해제")
                    destination = Observed(); turn = Observed(); queryNavigation(); publish()
                }
                override fun onRouteCancelled() = navigationEvent(token) {
                    hasRoute = false; destination = Observed(detail = "경로 취소 수신"); turn = Observed(); request.cancelled()
                    navigation = "내비 경로 취소 수신"; publish()
                }
                override fun onError(errorCode: NaviErrorCode) = navigationEvent(token) { invalidate("내비 오류 · ${errorCode.name}") }
            }
            naviListener = listener; helper.addListener(listener)
            arm("connect",10_000,token) { navigation = "내비 연결 시간 초과"; stopNavigation() }
            helper.initialize(onConnected = { navigationEvent(token) {
                clear("connect"); naviReady = true; navigation = "내비 서비스 연결됨 · 실제 정보 대기"; queryNavigation(); publish()
            } },onFailed = { navigationEvent(token) { clear("connect"); invalidate("내비 서비스 연결 실패"); stopNavigation(); publish() } })
        } catch (_: Exception) { navigation = "내비 권한 또는 서비스 연결 오류"; stopNavigation(); publish() }
    }
    private fun queryNavigation() {
        if (!naviReady) return
        fun query(key: String, action: ()->Unit) {
            if (key in deadlines) return
            arm(key,2_500,generation) {
                when (key) {
                    "position" -> navigationPosition = Observed(quality = InputValidity.ERROR,detail = "내비 위치 응답 시간 초과")
                    "destination" -> {
                        destination = Observed(quality = InputValidity.ERROR,detail = "목적지 응답 시간 초과")
                        request.evidenceUnavailable("목적지 응답 시간 초과 · 이전 요청 확인 해제")
                    }
                    "turn" -> turn = Observed(quality = InputValidity.ERROR,detail = "다음 안내 응답 시간 초과")
                    else -> {
                        hasRoute = null; navigation = "내비 경로 상태 응답 시간 초과"
                        request.evidenceUnavailable("경로 상태 응답 시간 초과 · 이전 요청 확인 해제")
                    }
                }
            }
            runCatching(action).onFailure { clear(key); invalidate("내비 정보 요청 실패") }
        }
        if (app.checkSelfPermission(LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            query("position") { navi?.getCurrentLocationInfo() }
        } else navigationPosition=Observed(quality=InputValidity.DENIED,detail="위치 권한 없음 · 내비 기준 위치도 표시하지 않아요")
        query("route") { navi?.getRouteStateInfo() }
        if (hasRoute != false || request.phase == RequestPhase.WAITING) {
            query("destination") { navi?.getDestinationInfo() }
            query("turn") { navi?.getTBTInfo() }
        }
    }
    fun requestDestination(goal: NavigationGoal): Boolean {
        if (!running || !naviReady || !request.begin(goal,now())) return false
        interruptedRequest = null
        destination = Observed(detail = "새 목적지 응답 대기"); turn = Observed()
        try {
            navi?.requestRoute(RouteInfo(latitude = goal.point.latitude,longitude = goal.point.longitude,
                poiName = goal.name,poiId = "",address = "",poiSubId = ""))
        } catch (_: Exception) { request.fail("내비 목적지 요청 실패") }
        publish(); return true
    }
    private fun invalidate(message: String) {
        destination = Observed(detail = message); turn = Observed(detail = message)
        if (request.phase != RequestPhase.IDLE) request.fail(message)
        navigation = message; publish()
    }
    private fun arm(key: String, delay: Long, token: Long, action: ()->Unit) {
        clear(key)
        deadlines[key] = Runnable { if (running && token == generation) { deadlines.remove(key); action(); publish() } }
            .also { main.postDelayed(it,delay) }
    }
    private fun clear(key: String) { deadlines.remove(key)?.let(main::removeCallbacks) }
    private fun dispatch(token: Long, action: ()->Unit) { main.post { if (running && token == generation) action() } }
    private fun navigationEvent(token: Long, action: ()->Unit) = dispatch(token) { if (navi != null) action() }
    private fun now() = SystemClock.elapsedRealtime()
    private fun publish() {
        val elapsed = now()
        request.expire(elapsed)
        onUpdate(LocationNavigationSnapshot(location.value?.let { locationObservation(it,location.receivedAt,System.currentTimeMillis()).at(elapsed,30_000) } ?: location.at(elapsed,30_000),navigation,navigationPosition.at(elapsed,NAVIGATION_FRESHNESS_MS),destination.at(elapsed,NAVIGATION_FRESHNESS_MS),turn.at(elapsed,NAVIGATION_FRESHNESS_MS),
            running && naviReady && request.phase != RequestPhase.WAITING,request.phase,if (request.phase == RequestPhase.IDLE) interruptedRequest ?: request.message else request.message))
    }
    private fun stopLocation() {
        locationListener?.let { listener -> runCatching { fused?.unregisterFusedLocationCallback(listener) } }
        runCatching { fused?.release() }; fused = null; locationListener = null
    }
    private fun stopNavigation() {
        naviReady = false
        request.evidenceUnavailable("내비 연결이 해제되어 이전 요청 확인을 해제했어요.")
        listOf("connect","position","route","destination","turn").forEach(::clear)
        naviListener?.let { listener -> runCatching { navi?.removeListener(listener) } }
        runCatching { navi?.release() }; navi = null; naviListener = null
    }
    fun stop() {
        if (!running) return
        if (request.phase != RequestPhase.IDLE) interruptedRequest = "화면 전환·연결 해제로 요청 확인을 종료했어요. 다시 연결하면 위의 현재 내비 목적지를 확인할 수 있어요."
        running = false; generation++; main.removeCallbacks(ticker)
        deadlines.values.forEach(main::removeCallbacks); deadlines.clear()
        stopLocation(); stopNavigation(); location = Observed(detail = "위치 연결 중지")
        navigationPosition = Observed(detail = "내비 위치 연결 중지"); destination = Observed(detail = "내비 연결 중지"); turn = Observed(); navigation = "내비 연결 중지"; request.reset(); publish()
    }
    override fun close() = stop()
    companion object { const val LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION" }
}
