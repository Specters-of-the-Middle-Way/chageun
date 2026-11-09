# Skill 04 — Pleos Vehicle SDK 2.3.2 & 2-Tier Timeout Policy

## 1. Hybrid Vehicle Signal Architecture (AAOS + Pleos SDK 2.3.2)

Always follow the established API source priority in `kr.drivestep.tutor.vehicle`:

| Signal Domain | Authoritative API Source | Notes & Prohibitions |
|---|---|---|
| **Gear Selection (`P/R/N/D`)** | AAOS `CarPropertyManager` (`VehiclePropertyIds.GEAR_SELECTION`) | **Never** use Pleos SDK's deprecated `Gear` API. |
| **Vehicle Speed (`m/s`)** | AAOS `CarPropertyManager` (`VehiclePropertyIds.PERF_VEHICLE_SPEED`) | Requires `Car.PERMISSION_SPEED`. **Never** substitute cruise-control target speed for actual vehicle speed. Preserve `CarPropertyValue.timestamp` (elapsedRealtimeNanos). |
| **Parking Brake** | AAOS `CarPropertyManager` (`VehiclePropertyIds.PARKING_BRAKE_ON`) | Optional channel; if unsupported, degrade gracefully via `disableOptional(channel)`. |
| **Turn Signal (`OFF/LEFT/RIGHT`)** | Pleos SDK `ai.pleos.playground.vehicle.api.TurnSignal` | `TurnSignalValue` has only `OFF`, `LEFT`, `RIGHT`. Map `null` to `SignalLamp.UNKNOWN`, never `OFF`. |
| **Front-Wheel Steering Angle** | Pleos SDK `ai.pleos.playground.vehicle.api.SteeringWheel` | Reports front-wheel/bicycle-model steering angle in degrees (left is negative), **not** steering wheel hand rotations. |
| **Brake Pedal Pressed** | Pleos SDK `ai.pleos.playground.vehicle.api.Brake` | Optional channel (`BrakePedalStatusListener`). |

---

## 2. Two-Tier Vehicle I/O Timeout Policy (`VehicleTimeoutPolicy.kt`)

Vehicle I/O timeouts are strictly separated into two tiers based on whether the user is waiting for initial service binding while parked or actively performing a driving/parking/practice maneuver (`kr.drivestep.tutor.vehicle.VehicleTimeoutPolicy`):

### Tier 1 — Parked (`P`) Initial Service Connection & Capability Lookup
- **`PARKED_CONNECT_TIMEOUT_MS = 10_000L` (10.0 seconds)**
- **Where used:**
  - `PleosTurnSignalSource.start()` during `Vehicle.initialize()` + `checkTurnSignalStateCapabilities(...)`.
  - `PleosParkingSignalSource.start()` startup deadline waiting for required channels (`GEAR`, `STEERING`, `SPEED`) to report capabilities and initial values while parked.
- **Rationale:** Cold binder initialization on the emulator or head unit while parked in `P` can take several seconds; a 10s ceiling prevents premature connection failures before the user starts an exercise.

### Tier 2 — Active Practice, Parking & Driving Maneuver Responsiveness
- **`ACTIVE_READ_TIMEOUT_MS = 2_500L` (2.5 seconds)**
  - Applied to `PleosTurnSignalSource.readSnapshot()` and `PleosParkingSignalSource.requestRead()` during active practice so a stalled sensor read fails fast instead of leaving the driver waiting mid-action.
- **`ACTIVE_HEARTBEAT_MS = 1_000L` (1.0 second)** & **`TURN_SIGNAL_HEARTBEAT_MS = 3_000L` (3.0 seconds)**
  - Periodic health-check intervals while a session is active.
- **`ACTIVE_PRACTICE_FRESHNESS_MS = 3_000L` (3.0 seconds)** (and `5_000L` default in `SteeringPracticeConfig` for unit-test compatibility)
  - Maximum allowed age of required signals (`GEAR`, `SPEED`, `STEERING`) before `checkFreshness()` interrupts the session with `SIGNAL_STALE_*`.
- **`DRIVING_SITUATION_FRESHNESS_MS = 1_500L` (1.5 seconds)**
  - Maximum age of `speedAtMillis`, `signalAtMillis`, and `warningAtMillis` in `SituationCoachEngine`.

---

## 3. Lifecycle, Threading & `isInitial` Snapshot Rules

1. **Generation Token Isolation (`generation++`)**
   - Every `start()` and `closeSession()` increments `generation`.
   - All asynchronous SDK callbacks and `Handler` runnables must check `if (token == generation && vehicle != null)` on the main thread so late Binder callbacks after `stop()`/`release()` are ignored.
2. **Initial Snapshot (`isInitial = true`) vs. Live Subscription (`isInitial = false`)**
   - Values obtained via `getCurrent*()` GET calls or the very first subscription callback establish the baseline display/readiness (`isInitial = true`) and **MUST NEVER advance or complete a user practice step**.
   - Only genuine subsequent subscription state changes (`isInitial = false`, `isSubscription = true`) can advance `PracticeSession` or `SteeringPracticeSession`.
   - A delayed GET callback (`updatesAtRequest != updateSequence`) must never overwrite a newer subscription event.
