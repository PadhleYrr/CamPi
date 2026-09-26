package com.gkk.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.camera2.*
import android.media.*
import android.os.*
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {

    companion object {
        const val ACTION_START   = "com.gkk.app.START_RECORDING"
        const val ACTION_STOP    = "com.gkk.app.STOP_RECORDING"
        const val CHANNEL_ID     = "dualcam_rec_channel"
        const val NOTIF_ID       = 9001
        const val TAG            = "DualCamRec"

        @Volatile var isRunning   = false
        @Volatile var lastPaths: Pair<String, String>? = null // back, front
        private var startMs       = 0L

        fun elapsedSeconds(): Long =
            if (isRunning && startMs > 0) (System.currentTimeMillis() - startMs) / 1000 else 0L

        fun createNotificationChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "Dual Camera Recording",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description   = "Live recording status"
                    setShowBadge(false)
                    setSound(null, null)
                }
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(ch)
            }
        }
    }

    // ── Hardware handles ──────────────────────────────────────────────────
    private var cameraManager: CameraManager? = null
    private var backDevice:    CameraDevice?  = null
    private var frontDevice:   CameraDevice?  = null
    private var backSession:   CameraCaptureSession? = null
    private var frontSession:  CameraCaptureSession? = null
    private var backRecorder:  MediaRecorder? = null
    private var frontRecorder: MediaRecorder? = null

    // ── Threading ─────────────────────────────────────────────────────────
    private var camThread: HandlerThread? = null
    private var camHandler: Handler?      = null

    // ── Wake / timer ──────────────────────────────────────────────────────
    private var wakeLock:      PowerManager.WakeLock? = null
    private val mainHandler    = Handler(Looper.getMainLooper())
    private var backPath       = ""
    private var frontPath      = ""
    private var sessionsReady  = 0   // count up to 2 then start recorders

    // ── Timer tick ───────────────────────────────────────────────────────
    private val tickRunnable = object : Runnable {
        override fun run() {
            val s = elapsedSeconds()
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            val label = if (h > 0) "%02d:%02d:%02d".format(h, m, sec)
                        else       "%02d:%02d".format(m, sec)
            pushNotification("🔴 Recording  $label  (both cameras)")
            mainHandler.postDelayed(this, 1000)
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val mode = intent.getStringExtra("mode") ?: "background"
                startRecording(mode)
            }
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    // ── Start ─────────────────────────────────────────────────────────────
    private fun startRecording(mode: String) {
        if (isRunning) return
        isRunning  = true
        lastPaths  = null
        startMs    = System.currentTimeMillis()

        // Partial wake lock = CPU alive, screen can be off
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GKK::DualCamRecord"
        ).also { it.acquire(4 * 3600 * 1000L) }   // max 4 h

        // Put into foreground immediately (required before camera on API 34+)
        val notif = buildNotification("📷 Starting cameras…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }

        // Dedicate a thread for Camera2 callbacks
        camThread  = HandlerThread("DualCamThread").also { it.start() }
        camHandler = Handler(camThread!!.looper)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Build output paths
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "DualCam")
            .also { it.mkdirs() }
        backPath  = File(dir, "back_$ts.mp4").absolutePath
        frontPath = File(dir, "front_$ts.mp4").absolutePath

        openCameras()
        mainHandler.post(tickRunnable)
    }

    // ── Camera2 ───────────────────────────────────────────────────────────
    private fun openCameras() {
        var backId:  String? = null
        var frontId: String? = null
        try {
            for (id in cameraManager!!.cameraIdList) {
                val ch = cameraManager!!.getCameraCharacteristics(id)
                when (ch.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK  -> if (backId  == null) backId  = id
                    CameraCharacteristics.LENS_FACING_FRONT -> if (frontId == null) frontId = id
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "enumerate cameras: ${e.message}")
        }

        // BACK camera — video 1280×720 + audio
        backId?.let { id ->
            try {
                backRecorder = buildRecorder(backPath, withAudio = true,  w = 1280, h = 720)
                cameraManager!!.openCamera(id, makeDeviceCallback(isFront = false), camHandler)
            } catch (e: Exception) { Log.e(TAG, "open back: ${e.message}") }
        }

        // FRONT camera — video 640×480, no audio (MIC already claimed)
        frontId?.let { id ->
            try {
                frontRecorder = buildRecorder(frontPath, withAudio = false, w = 640,  h = 480)
                cameraManager!!.openCamera(id, makeDeviceCallback(isFront = true), camHandler)
            } catch (e: Exception) { Log.e(TAG, "open front: ${e.message}") }
        }
    }

    private fun makeDeviceCallback(isFront: Boolean) = object : CameraDevice.StateCallback() {
        override fun onOpened(cam: CameraDevice) {
            if (isFront) {
                frontDevice = cam
                createSession(cam, frontRecorder!!) { sess -> frontSession = sess; onSessionReady() }
            } else {
                backDevice = cam
                createSession(cam, backRecorder!!)  { sess -> backSession  = sess; onSessionReady() }
            }
        }
        override fun onDisconnected(cam: CameraDevice) { cam.close() }
        override fun onError(cam: CameraDevice, error: Int) {
            Log.e(TAG, "camera error $error"); cam.close()
        }
    }

    private fun createSession(
        cam: CameraDevice, rec: MediaRecorder, onReady: (CameraCaptureSession) -> Unit
    ) {
        val surface = rec.surface
        @Suppress("DEPRECATION")
        cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(sess: CameraCaptureSession) {
                onReady(sess)
                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(15, 30))
                }.build()
                sess.setRepeatingRequest(req, null, camHandler)
            }
            override fun onConfigureFailed(sess: CameraCaptureSession) {
                Log.e(TAG, "session configure failed")
            }
        }, camHandler)
    }

    @Synchronized
    private fun onSessionReady() {
        sessionsReady++
        // Start both recorders once both sessions are live
        if (sessionsReady >= 2) {
            camHandler?.post {
                try { backRecorder?.start()  } catch (e: Exception) { Log.e(TAG, "back start: ${e.message}") }
                try { frontRecorder?.start() } catch (e: Exception) { Log.e(TAG, "front start: ${e.message}") }
            }
        }
    }

    // ── MediaRecorder builder ─────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun buildRecorder(path: String, withAudio: Boolean, w: Int, h: Int): MediaRecorder {
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                     MediaRecorder(this) else MediaRecorder()
        if (withAudio) mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setOutputFile(path)
        mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mr.setVideoSize(w, h)
        mr.setVideoFrameRate(30)
        mr.setVideoEncodingBitRate(if (withAudio) 2_500_000 else 1_200_000)
        if (withAudio) {
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44100)
            mr.setAudioEncodingBitRate(128_000)
        }
        mr.prepare()
        return mr
    }

    // ── Stop ──────────────────────────────────────────────────────────────
    private fun stopRecording() {
        if (!isRunning) return
        isRunning = false
        mainHandler.removeCallbacks(tickRunnable)

        safeClose { backSession?.stopRepeating() }
        safeClose { frontSession?.stopRepeating() }
        safeClose { backSession?.close() }
        safeClose { frontSession?.close() }
        safeClose { backDevice?.close() }
        safeClose { frontDevice?.close() }

        Thread.sleep(200)   // let camera release surface before stopping recorder

        safeClose { backRecorder?.stop();  backRecorder?.release() }
        safeClose { frontRecorder?.stop(); frontRecorder?.release() }

        camThread?.quitSafely()
        wakeLock?.let { if (it.isHeld) it.release() }
        sessionsReady = 0

        lastPaths = Pair(backPath, frontPath)

        // Publish to Downloads (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToDownloads(backPath)
            publishToDownloads(frontPath)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Recording stopped. back=$backPath front=$frontPath")
    }

    private fun publishToDownloads(srcPath: String) {
        try {
            val f = File(srcPath)
            if (!f.exists()) return
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE,    "video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH,"Download/DualCam")
                put(MediaStore.Downloads.IS_PENDING,    1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out ->
                    f.inputStream().use { inp -> inp.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(it, values, null, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "publishToDownloads: ${e.message}")
        }
    }

    // ── Notification helpers ──────────────────────────────────────────────
    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GKK Dual Camera Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun pushNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private inline fun safeClose(block: () -> Unit) {
        try { block() } catch (_: Exception) {}
    }
}
