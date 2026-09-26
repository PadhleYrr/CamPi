package com.gkk.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.camera2.*
import android.media.*
import android.os.*
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.gkk.app.START_RECORDING"
        const val ACTION_STOP  = "com.gkk.app.STOP_RECORDING"
        const val CHANNEL_ID   = "dualcam_rec_channel"
        const val NOTIF_ID     = 9001
        const val TAG          = "DualCamRec"

        @Volatile var isRunning: Boolean = false
        @Volatile var lastPaths: Pair<String, String>? = null
        private var startMs = 0L

        fun elapsedSeconds(): Long =
            if (isRunning && startMs > 0) (System.currentTimeMillis() - startMs) / 1000 else 0L

        fun createNotificationChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Dual Camera Recording",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Live recording status"
                    setShowBadge(false)
                    setSound(null, null)
                }
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(ch)
            }
        }
    }

    private var cameraManager: CameraManager? = null
    private var backDevice:    CameraDevice?  = null
    private var frontDevice:   CameraDevice?  = null
    private var backSession:   CameraCaptureSession? = null
    private var frontSession:  CameraCaptureSession? = null
    private var backRecorder:  MediaRecorder? = null
    private var frontRecorder: MediaRecorder? = null
    private var camThread:     HandlerThread? = null
    private var camHandler:    Handler?       = null
    private var wakeLock:      PowerManager.WakeLock? = null
    private var outputDir:     File?          = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        outputDir = File(
            getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),
            "DualCam"
        ).also { it.mkdirs() }
        camThread = HandlerThread("CamThread").also { it.start() }
        camHandler = Handler(camThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val mode = intent.getStringExtra("mode") ?: "background"
                startForegroundWithNotif()
                acquireWakeLock(mode)
                startRecording()
            }
            ACTION_STOP -> stopRecordingAndSelf()
        }
        return START_STICKY
    }

    private fun startForegroundWithNotif() {
        createNotificationChannel(this)

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("🔴 Recording")
                .setContentText("Both cameras active — tap Stop to end")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .addAction(
                    Notification.Action.Builder(
                        null, "⏹ Stop", stopIntent
                    ).build()
                )
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("🔴 Recording")
                .setContentText("Both cameras active")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun acquireWakeLock(mode: String) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GKK:DualCamWake")
        wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4 hours max
    }

    private fun startRecording() {
        isRunning = true
        startMs   = System.currentTimeMillis()
        val ts    = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        val backFile  = File(outputDir, "back_$ts.mp4")
        val frontFile = File(outputDir, "front_$ts.mp4")
        lastPaths = Pair(backFile.absolutePath, frontFile.absolutePath)

        openCamera("back",  backFile,  isBack = true)
        openCamera("front", frontFile, isBack = false)
    }

    private fun openCamera(label: String, outFile: File, isBack: Boolean) {
        try {
            val camId = pickCamera(isBack)
            if (camId == null) {
                Log.w(TAG, "No ${if (isBack) "back" else "front"} camera found")
                return
            }
            val recorder = buildRecorder(outFile, isBack)
            if (isBack) backRecorder = recorder else frontRecorder = recorder

            @Suppress("MissingPermission")
            cameraManager?.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (isBack) backDevice = camera else frontDevice = camera
                    startCaptureSession(camera, recorder, isBack)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "$label camera error $error")
                    camera.close()
                }
            }, camHandler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera $label failed", e)
        }
    }

    private fun pickCamera(isBack: Boolean): String? {
        return cameraManager?.cameraIdList?.firstOrNull { id ->
            val chars = cameraManager?.getCameraCharacteristics(id)
            val facing = chars?.get(CameraCharacteristics.LENS_FACING)
            if (isBack) facing == CameraCharacteristics.LENS_FACING_BACK
            else        facing == CameraCharacteristics.LENS_FACING_FRONT
        }
    }

    private fun buildRecorder(outFile: File, isBack: Boolean): MediaRecorder {
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()

        mr.apply {
            if (isBack) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (isBack) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(if (isBack) 1280 else 640, if (isBack) 720 else 480)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(if (isBack) 3_000_000 else 1_000_000)
            setOutputFile(outFile.absolutePath)
            prepare()
        }
        return mr
    }

    private fun startCaptureSession(
        camera: CameraDevice,
        recorder: MediaRecorder,
        isBack: Boolean
    ) {
        try {
            val surface = recorder.surface
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (isBack) backSession = session else frontSession = session
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                        }.build()
                        session.setRepeatingRequest(req, null, camHandler)
                        recorder.start()
                        Log.i(TAG, "${if (isBack) "Back" else "Front"} camera recording started")
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session configure failed for ${if (isBack) "back" else "front"}")
                    }
                },
                camHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "startCaptureSession failed", e)
        }
    }

    private fun stopRecordingAndSelf() {
        isRunning = false
        listOf(backRecorder to backSession, frontRecorder to frontSession).forEach { (rec, sess) ->
            try { sess?.stopRepeating() } catch (_: Exception) {}
            try { sess?.close() }         catch (_: Exception) {}
            try { rec?.stop() }           catch (_: Exception) {}
            try { rec?.release() }        catch (_: Exception) {}
        }
        try { backDevice?.close() }  catch (_: Exception) {}
        try { frontDevice?.close() } catch (_: Exception) {}
        backRecorder  = null; frontRecorder  = null
        backSession   = null; frontSession   = null
        backDevice    = null; frontDevice    = null
        wakeLock?.release(); wakeLock = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning) stopRecordingAndSelf()
        camThread?.quitSafely()
    }
}
