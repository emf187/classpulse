package com.classpulse.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.random.Random

/**
 * Single-screen classroom presence tracker.
 *
 * - Room code links all phones (same code = same roster).
 * - BLE gives RSSI -> DistanceEstimator; accelerometer gives steps/moving.
 * - Headcount = self + peers seen within OUT_TIMEOUT_MS.
 * - OUT OF BOUNDS = unseen > 15 s (walked out of range / closed app).
 * - Simulate switch fakes 5 classmates so the UI can be tried with one phone.
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var inputName: EditText
    private lateinit var inputRoom: EditText
    private lateinit var btnCreate: Button
    private lateinit var btnJoin: Button
    private lateinit var btnStartStop: Button
    private lateinit var switchSim: Switch
    private lateinit var txtHeadcount: TextView
    private lateinit var txtNear: TextView
    private lateinit var txtStatus: TextView
    private lateinit var peerList: LinearLayout

    private lateinit var sensorManager: SensorManager
    private var accel: Sensor? = null
    private val stepDetector = StepDetector()
    private val estimators = mutableMapOf<String, DistanceEstimator>()
    private val peers = mutableMapOf<String, Peer>()
    private var selfId = "SELF"
    private var selfName = "Me"
    private var roomCode = ""
    private var tracking = false
    private var simulate = false

    private var ble: ClassRoomBleManager? = null
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (tracking) {
                if (simulate) injectSimulatedPeers()
                refreshStatuses()
                render()
                // Re-broadcast step count every ~5 s so peers see movement.
                if ((System.currentTimeMillis() / 5000) != lastBroadcastBucket) {
                    lastBroadcastBucket = System.currentTimeMillis() / 5000
                    ble?.updateSteps(stepDetector.stepCount)
                }
            }
            ui.postDelayed(this, 1000)
        }
    }
    private var lastBroadcastBucket = -1L
    private var simSeeded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        ui.post(ticker)
    }

    // ---------- UI (built in code so no layout XML is needed) ----------
    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(col, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun title(s: String) = TextView(this).apply {
            text = s; textSize = 22f; gravity = Gravity.START
        }
        fun label(s: String) = TextView(this).apply { text = s; textSize = 14f }

        col.addView(title("ClassPulse — Classroom Presence"))
        col.addView(label("1. Enter name + room code. 2. Create or Join. 3. Start tracking.\n" +
                "NEAR ≤ 1 m (next to you) • IN ROOM ≤ 8 m • OUT after 15 s unseen."))

        inputName = EditText(this).apply { hint = "Your name (e.g. Mike)" }
        inputRoom = EditText(this).apply { hint = "Room code (e.g. A3F9K)"; }
        col.addView(inputName); col.addView(inputRoom)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnCreate = Button(this).apply { text = "Create" }
        btnJoin = Button(this).apply { text = "Join" }
        row.addView(btnCreate, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btnJoin, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        btnStartStop = Button(this).apply { text = "Start tracking"; isEnabled = false }
        switchSim = Switch(this).apply { text = "Simulate 5 classmates (demo with 1 phone)" }
        col.addView(btnStartStop); col.addView(switchSim)

        txtHeadcount = TextView(this).apply { textSize = 30f; text = "IN ROOM: 1" }
        txtNear = TextView(this).apply { textSize = 16f; text = "NEAR (≤1 m): 0" }
        txtStatus = TextView(this).apply { textSize = 13f }
        col.addView(txtHeadcount); col.addView(txtNear); col.addView(txtStatus)

        col.addView(label("— People —"))
        peerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(peerList)
        setContentView(root)

        btnCreate.setOnClickListener {
            roomCode = newRoomCode()
            inputRoom.setText(roomCode)
            toast("Room created: $roomCode — others join with this code")
            btnStartStop.isEnabled = true
        }
        btnJoin.setOnClickListener {
            roomCode = inputRoom.text.toString().trim().uppercase(Locale.US)
            if (roomCode.length < 3) { toast("Enter the room code first"); return@setOnClickListener }
            toast("Joined room $roomCode")
            btnStartStop.isEnabled = true
        }
        btnStartStop.setOnClickListener {
            if (!tracking) startTracking() else stopTracking()
        }
        switchSim.setOnCheckedChangeListener { _, on ->
            simulate = on
            if (on) { simSeeded = false; toast("Simulation ON — 5 fake classmates") }
        }
    }

    private fun newRoomCode(): String {
        val chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return (1..5).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    // ---------- Tracking ----------
    private fun startTracking() {
        selfName = inputName.text.toString().trim().ifEmpty { "Student" }
        roomCode = inputRoom.text.toString().trim().uppercase(Locale.US)
        if (roomCode.length < 3) { toast("Create or Join a room first"); return }
        if (!hasPermissions()) { requestPermissions(); return }
        val btOn = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.isEnabled == true
        if (!btOn && !simulate) toast("Turn Bluetooth ON for real ranging (or enable Simulate)")

        selfId = "SELF"
        peers.clear(); estimators.clear()
        peers[selfId] = Peer(selfId, "$selfName (you)", -50, 0.0, 0, System.currentTimeMillis(), PeerStatus.NEAR)
        stepDetector.reset()

        ble = ClassRoomBleManager(this,
            onPeerSeen = { id, name, rssi, steps -> onBlePeer(id, name, rssi, steps) },
            onError = { msg -> runOnUiThread { txtStatus.text = msg } })
        if (!simulate) ble?.start(roomCode, selfName, 0)

        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        tracking = true
        btnStartStop.text = "Stop"
        toast("Tracking room $roomCode")
    }

    private fun stopTracking() {
        tracking = false
        try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
        ble?.stop(); ble = null
        btnStartStop.text = "Start tracking"
    }

    private fun onBlePeer(deviceId: String, name: String, rssi: Int, steps: Int) {
        val moving = stepDetector.isMoving()
        val est = estimators.getOrPut(deviceId) { DistanceEstimator() }
        // RSSI is always re-measured; the accelerometer gates smoothing strength
        // (still => heavy smoothing, moving => responsive) inside update().
        val d = est.update(rssi, moving)
        val p = peers.getOrPut(deviceId) { Peer(deviceId, name) }
        p.displayName = name; p.rssi = rssi; p.distanceM = d; p.steps = steps
        p.lastSeenMs = System.currentTimeMillis()
    }

    private fun injectSimulatedPeers() {
        val now = System.currentTimeMillis()
        if (!simSeeded) {
            simSeeded = true
            val names = listOf("Ada", "Ben", "Cara", "Dan", "Efe")
            val rssis = listOf(-55, -62, -70, -78, -60) // ~0.6m, 1.4m, 3.5m, 8m, 1.1m
            names.forEachIndexed { i, n ->
                val id = "SIM$i"
                val est = estimators.getOrPut(id) { DistanceEstimator() }
                val d = est.update(rssis[i], false)
                peers[id] = Peer(id, n, rssis[i], d, Random.nextInt(0, 40), now, PeerStatus.IN_ROOM)
            }
        } else {
            // Drift + one student walks out after ~25 s to demo the OUT flag.
            peers.forEach { (id, p) ->
                if (!id.startsWith("SIM")) return@forEach
                val walkOut = id == "SIM3" && now % 60_000 > 25_000
                val target = if (walkOut) -92 else p.rssi + Random.nextInt(-2, 3)
                p.rssi = target.coerceIn(-95, -45)
                val est = estimators.getOrPut(id) { DistanceEstimator() }
                p.distanceM = est.update(p.rssi, true)
                if (!walkOut) p.lastSeenMs = now
                // SIM3 stops being seen while "out": lastSeen goes stale -> OUT_OF_BOUNDS
            }
        }
        // Self stays fresh
        peers[selfId]?.lastSeenMs = now
    }

    private fun refreshStatuses() {
        val now = System.currentTimeMillis()
        // Self: always in room, steps live
        peers[selfId]?.apply {
            steps = stepDetector.stepCount
            lastSeenMs = now
            distanceM = 0.0
            status = PeerStatus.NEAR
        }
        peers.forEach { (id, p) ->
            if (id == selfId) return@forEach
            val ago = now - p.lastSeenMs
            // Out-of-bounds rule: stale sightings, or far + either side moving away.
            p.status = DistanceEstimator.classify(p.distanceM, ago)
        }
        // Drop nothing (keep OUT entries visible as flagged), but cap list at 60.
        if (peers.size > 61) {
            peers.entries.sortedBy { it.value.lastSeenMs }
                .take(peers.size - 61).forEach { peers.remove(it.key) }
        }
    }

    private fun render() {
        val inRoom = peers.values.count { it.status != PeerStatus.OUT_OF_BOUNDS }
        val near = peers.values.count { it.status == PeerStatus.NEAR } - 1 // exclude self
        val out = peers.values.count { it.status == PeerStatus.OUT_OF_BOUNDS }
        txtHeadcount.text = "IN ROOM: $inRoom"
        txtNear.text = "NEAR (≤1 m): ${near.coerceAtLeast(0)}    •    OUT: $out"
        val moving = stepDetector.isMoving()
        txtStatus.text = "Room $roomCode • Steps: ${stepDetector.stepCount} • " +
                (if (moving) "MOVING" else "still") +
                (if (simulate) " • SIMULATION" else " • BLE live")

        peerList.removeAllViews()
        val sorted = peers.values.sortedWith(
            compareBy({ it.status != PeerStatus.NEAR }, { it.status == PeerStatus.OUT_OF_BOUNDS },
                { it.distanceM ?: 99.0 }))
        for (p in sorted) {
            val flag = when (p.status) {
                PeerStatus.NEAR -> "● NEAR"
                PeerStatus.IN_ROOM -> "○ IN ROOM"
                PeerStatus.EDGE -> "◐ EDGE"
                PeerStatus.OUT_OF_BOUNDS -> "⚠ OUT OF BOUNDS"
            }
            val d = if (p.deviceId == selfId) "—" else
                (p.distanceM?.let { String.format(Locale.US, "%.1f m", it) } ?: "?")
            val agoS = (System.currentTimeMillis() - p.lastSeenMs) / 1000
            peerList.addView(TextView(this).apply {
                text = "${p.displayName}  •  $d  •  $flag  •  steps ${p.steps}  •  ${agoS}s ago"
                textSize = 14f
                setPadding(0, 6, 0, 6)
                setTextColor(
                    if (p.status == PeerStatus.OUT_OF_BOUNDS) 0xFFB00020.toInt()
                    else 0xFF222222.toInt())
            })
        }
    }

    // ---------- Sensors ----------
    override fun onSensorChanged(event: SensorEvent) {
        if (!tracking) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            stepDetector.onSample(event.values[0], event.values[1], event.values[2])
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------- Permissions ----------
    private fun neededPermissions(): Array<String> {
        val base = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            base += Manifest.permission.BLUETOOTH_SCAN
            base += Manifest.permission.BLUETOOTH_ADVERTISE
            base += Manifest.permission.BLUETOOTH_CONNECT
        }
        return base.toArray(emptyArray())
    }

    private fun hasPermissions(): Boolean {
        val ctx: Context = this
        return neededPermissions().all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, neededPermissions(), 1001)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 1001 && res.all { it == PackageManager.PERMISSION_GRANTED }) startTracking()
        else if (code == 1001) toast("Bluetooth + Location permissions are required")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
        ble?.stop()
        ui.removeCallbacks(ticker)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
