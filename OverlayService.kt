package com.example.micboost

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.*
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.TextView

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var btn: TextView
    @Volatile private var boosting = false
    private var worker: Thread? = null

    // ভয়েস কতগুণ জোরালো হবে (2.0 - 6.0 এর মধ্যে রাখুন)
    @Volatile private var gain = 4.0f

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addFloatingButton()
    }

    private fun startAsForeground() {
        val chId = "micboost"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(chId, "Mic Boost", NotificationManager.IMPORTANCE_LOW)
                )
        }
        val n = Notification.Builder(this).apply {
            if (Build.VERSION.SDK_INT >= 26) setChannelId(chId)
            setContentTitle("Mic Boost চালু আছে")
            setSmallIcon(android.R.drawable.ic_btn_speak_now)
        }.build()

        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    private fun addFloatingButton() {
        btn = TextView(this).apply {
            text = "🎤"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(24, 24, 24, 24)
        }
        setColor(false)

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            160, 160, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }

        // ড্র্যাগ + ট্যাপ
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        btn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    lp.x = sx + dx; lp.y = sy + dy
                    wm.updateViewLayout(btn, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (e.eventTime - e.downTime > 600) cycleGain() else toggle()
                }
            }
            true
        }
        wm.addView(btn, lp)
    }

    private fun setColor(on: Boolean) {
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) Color.parseColor("#2E7D32") else Color.parseColor("#B71C1C"))
        }
    }

    // বাটন চেপে ধরলে গেইন বদলাবে: 2x -> 4x -> 6x -> 8x
    private fun cycleGain() {
        gain = when (gain) { 2f -> 4f; 4f -> 6f; 6f -> 8f; else -> 2f }
        btn.text = "🎤${gain.toInt()}x"
        btn.textSize = 16f
    }

    private fun toggle() {
        boosting = !boosting
        setColor(boosting)
        if (boosting) startBoost()
    }

    private fun startBoost() {
        worker = Thread {
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(min * 2)
                .setTransferMode(AudioTrack.MODE_STREAM).build()

            // আউটপুট জোর করে ফোনের লাউডস্পিকারে
            getSystemService(AudioManager::class.java)
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { track.preferredDevice = it }

            val buf = ShortArray(min)
            rec.startRecording(); track.play()
            while (boosting) {
                val n = rec.read(buf, 0, buf.size)
                for (i in 0 until n) {
                    // সফট লিমিটার: বেশি গেইনেও আওয়াজ ফাটবে না
                    val x = buf[i] * gain / 32767.0
                    buf[i] = (32767 * Math.tanh(x)).toInt().toShort()
                }
                track.write(buf, 0, n)
            }
            rec.stop(); rec.release(); track.stop(); track.release()
        }.also { it.start() }
    }

    override fun onDestroy() {
        boosting = false
        if (::btn.isInitialized) wm.removeView(btn)
        super.onDestroy()
    }
}
