package com.gkk.app

import android.Manifest
import android.content.Intent
import android.os.Build
import com.getcapacitor.*
import com.getcapacitor.annotation.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@CapacitorPlugin(
    name = "DualCamRecorder",
    permissions = [
        Permission(strings = [Manifest.permission.CAMERA], alias = "camera"),
        Permission(strings = [Manifest.permission.RECORD_AUDIO], alias = "microphone")
    ]
)
class DualCamRecorderPlugin : Plugin() {

    override fun load() {
        RecordingService.createNotificationChannel(context)
    }

    @PluginMethod
    fun startRecording(call: PluginCall) {
        val camState  = getPermissionState("camera")
        val micState  = getPermissionState("microphone")
        if (camState != PermissionState.GRANTED || micState != PermissionState.GRANTED) {
            requestAllPermissions(call, "permissionsCallback")
            return
        }
        doStart(call)
    }

    @PermissionCallback
    private fun permissionsCallback(call: PluginCall) {
        if (getPermissionState("camera") == PermissionState.GRANTED &&
            getPermissionState("microphone") == PermissionState.GRANTED) {
            doStart(call)
        } else {
            call.reject("Camera and microphone permissions required")
        }
    }

    private fun doStart(call: PluginCall) {
        if (RecordingService.isRunning) {
            call.reject("Already recording")
            return
        }
        val mode = call.getString("mode", "background") // "background" | "screenoff"
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra("mode", mode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        val ret = JSObject()
        ret.put("started", true)
        call.resolve(ret)
    }

    @PluginMethod
    fun stopRecording(call: PluginCall) {
        if (!RecordingService.isRunning) {
            val ret = JSObject()
            ret.put("stopped", false)
            ret.put("message", "Not recording")
            call.resolve(ret)
            return
        }
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)
        // Give recorder 1.5 s to flush and release
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val paths = RecordingService.lastPaths
            val ret = JSObject()
            ret.put("stopped", true)
            ret.put("backPath",  paths?.first  ?: "")
            ret.put("frontPath", paths?.second ?: "")
            call.resolve(ret)
        }, 1500)
    }

    @PluginMethod
    fun isRecording(call: PluginCall) {
        val ret = JSObject()
        ret.put("recording", RecordingService.isRunning)
        ret.put("elapsed",   RecordingService.elapsedSeconds())
        call.resolve(ret)
    }

    @PluginMethod
    fun getRecordings(call: PluginCall) {
        val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "DualCam")
        val arr = JSArray()
        if (dir.exists()) {
            val files = dir.listFiles()
                ?.filter { it.extension == "mp4" }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            // Group by timestamp suffix (everything after first underscore)
            val pairs = linkedMapOf<String, MutableMap<String, File>>()
            files.forEach { f ->
                val name = f.nameWithoutExtension           // e.g. back_20250101_120000
                val underIdx = name.indexOf('_')
                if (underIdx >= 0) {
                    val cam = name.substring(0, underIdx)   // "back" | "front"
                    val ts  = name.substring(underIdx + 1)  // "20250101_120000"
                    pairs.getOrPut(ts) { mutableMapOf() }[cam] = f
                }
            }

            val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            pairs.forEach { (ts, cams) ->
                val backFile  = cams["back"]
                val frontFile = cams["front"]
                val obj = JSObject()
                obj.put("timestamp", ts)
                obj.put("backPath",   backFile?.absolutePath  ?: "")
                obj.put("frontPath",  frontFile?.absolutePath ?: "")
                obj.put("backSize",   backFile?.length()  ?: 0)
                obj.put("frontSize",  frontFile?.length() ?: 0)
                obj.put("totalMB",
                    String.format("%.1f", ((backFile?.length() ?: 0) + (frontFile?.length() ?: 0)).toDouble() / 1_048_576))
                obj.put("date", sdf.format(Date(backFile?.lastModified() ?: frontFile?.lastModified() ?: 0)))
                arr.put(obj)
            }
        }
        val ret = JSObject()
        ret.put("recordings", arr)
        call.resolve(ret)
    }

    @PluginMethod
    fun deleteRecording(call: PluginCall) {
        val ts  = call.getString("timestamp") ?: run { call.reject("timestamp required"); return }
        val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "DualCam")
        var deleted = 0
        dir.listFiles()
            ?.filter { it.nameWithoutExtension.contains(ts) }
            ?.forEach { if (it.delete()) deleted++ }
        val ret = JSObject()
        ret.put("deleted", deleted)
        call.resolve(ret)
    }
}
