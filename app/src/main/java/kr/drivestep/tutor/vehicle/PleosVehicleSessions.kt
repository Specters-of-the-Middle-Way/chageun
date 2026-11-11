package kr.drivestep.tutor.vehicle

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.os.Looper

/** Share Vehicle's capability handshake across the app's independent read-only adapters. */
object PleosVehicleSessions {
    private var pool: SessionLeasePool<Vehicle>? = null
    fun acquire(context: Context): SessionLease<Vehicle> {
        check(Looper.myLooper() == Looper.getMainLooper())
        val shared = pool ?: SessionLeasePool({ Vehicle(context.applicationContext).also { it.initialize() } },Vehicle::release).also { pool = it }
        return shared.acquire()
    }
}
