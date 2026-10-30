package com.dxmaster.mic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

class MainActivity : Activity() {

    // ---------- রং ----------
    private val cBg = Color.parseColor("#0A0A14")
    private val cCard = Color.parseColor("#14142A")
    private val cCyan = Color.parseColor("#00E5FF")
    private val cPink = Color.parseColor("#FF2D75")
    private val cGreen = Color.parseColor("#00E676")
    private val cPurple = Color.parseColor("#7C4DFF")

    // ---------- অবস্থা ----------
    @Volatile private var running = false
    @Volatile private var gain = 20f          // 2000% শুরুতে
    @Volatile private var level = 0f
    private var boost = 2000
    private var mic2 = false
    private var worker: Thread? = null

    private var eq: Equalizer? = null
    private var loud: LoudnessEnhancer? = null
    private var bass: BassBoost? = null
    private var loudMb = 1500
    private var bassStrength = 500
    private val eqVals = intArrayOf(50, 50, 50, 50, 50)

    private lateinit var meter: ProgressBar
    private lateinit var boostTxt: TextView
    private lateinit var boostSeek: SeekBar
    private lateinit var micBtn: Button
    private lateinit var settingsPanel: LinearLayout
    private lateinit var modeTxt: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            meter.progress = (level * 100f).toInt().coerceIn(0, 100)
            handler.postDelayed(this, 60)
        }
    }
    private var tone: ToneGenerator? = null

    private fun maxBoost() = if (mic2) 10000 else 7000
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- UI সাহায্যকারী ----------
    private fun roundBg(color: Int, r: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
            if (stroke != 0) setStroke(dp(2), stroke)
        }

    private fun click(v: View) {
        try {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Exception) {
        }
    }

    private fun mkText(t: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun mkBtn(label: String, color: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = roundBg(color, 14)
            setOnClickListener {
                click(it)
                onClick()
            }
        }

    private fun mkSeek(maxV: Int, prog: Int, onChange: (Int) -> Unit): SeekBar =
        SeekBar(this).apply {
            this.max = maxV
            this.progress = prog
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(p)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(cCard, 18, Color.parseColor("#2A2A55"))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) }
    }

    // ---------- onCreate ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            tone = ToneGenerator(AudioManager.STREAM_SYSTEM, 60)
        } catch (_: Exception) {
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }

        // ===== টাইটেল =====
        root.addView(mkText("DX MASTER", 38f, cCyan, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
            setShadowLayer(26f, 0f, 0f, cCyan)
        })
        root.addView(mkText("VOLUME BOOSTER MIC", 13f, cPink, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
        })

        // ===== মাইক বাটন =====
        micBtn = Button(this).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setOnClickListener {
                click(it)
                toggleMic()
            }
        }
        val micWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(22) }
        }
        micWrap.addView(micBtn, LinearLayout.LayoutParams(dp(160), dp(160)))
        root.addView(micWrap)
        updateMicUi()

        modeTxt = mkText("MIC 1 • NORMAL", 12f, cGreen, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(modeTxt)

        // ===== লেভেল মিটার =====
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(cGreen)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(14)
            ).apply { topMargin = dp(12) }
        }
        root.addView(meter)

        // ===== ভলিউম বুস্টার =====
        val boostCard = card()
        boostCard.addView(mkText("VOLUME BOOSTER", 14f, cCyan, true))

        boostTxt = mkText("$boost%", 34f, Color.WHITE, true).apply { gravity = Gravity.CENTER }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minus = mkBtn("−", cPink) { setBoost(boost - 100) }
        val plus = mkBtn("+", cGreen) { setBoost(boost + 100) }
        row.addView(minus, LinearLayout.LayoutParams(dp(70), dp(56)))
        row.addView(boostTxt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(plus, LinearLayout.LayoutParams(dp(70), dp(56)))
        boostCard.addView(row)

        boostSeek = mkSeek(7000, boost) { setBoost(it) }
        boostCard.addView(boostSeek)
        boostCard.addView(mkText("মাইনাস/প্লাস = ১০০% করে কমবে/বাড়বে", 11f, Color.GRAY))
        root.addView(boostCard)

        // ===== ইকুয়ালাইজার =====
        val eqCard = card()
        eqCard.addView(mkText("EQUALIZER", 14f, cPurple, true))
        val names = arrayOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")
        for (i in 0 until 5) {
            eqCard.addView(mkText(names[i], 12f, Color.LTGRAY))
            eqCard.addView(mkSeek(100, 50) {
                eqVals[i] = it
                applyEq()
            })
        }
        root.addView(eqCard)

        // ===== সেটিং বাটন =====
        settingsPanel = buildSettingsPanel()
        root.addView(mkBtn("⚙  SETTINGS", cPurple) {
            settingsPanel.visibility =
                if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(16) }
        })
        root.addView(settingsPanel)

        root.addView(mkText("⚠ হেডফোন বা ব্লুটুথ স্পিকার ব্যবহার করুন, নাহলে ফোনের স্পিকারে শিস (ফিডব্যাক) হবে।", 11f, Color.GRAY).apply {
            setPadding(0, dp(16), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(cBg)
            addView(root)
        })
        handler.post(ticker)
    }

    // ---------- সেটিংস প্যানেল (MIC 2 এখানে) ----------
    private fun buildSettingsPanel(): LinearLayout {
        val p = card()
        p.visibility = View.GONE
        p.addView(mkText("SETTINGS", 14f, cPink, true))

        p.addView(mkText("🎤 MIC 2 — SUPER BOOST (সর্বোচ্চ 10000%)", 13f, Color.WHITE, true).apply {
            setPadding(0, dp(12), 0, 0)
        })
        p.addView(Switch(this).apply {
            text = "নতুন মাইক চালু করুন"
            setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { v, checked ->
                click(v)
                mic2 = checked
                boostSeek.max = maxBoost()
                setBoost(boost)
                modeTxt.text = if (mic2) "MIC 2 • SUPER BOOST" else "MIC 1 • NORMAL"
                modeTxt.setTextColor(if (mic2) cPink else cGreen)
                if (running) {
                    stopEngine()
                    startEngine()
                }
            }
        })

        p.addView(mkText("সাউন্ড ইফেক্ট (Loudness)", 12f, Color.LTGRAY).apply {
            setPadding(0, dp(12), 0, 0)
        })
        p.addView(mkSeek(3000, loudMb) {
            loudMb = it
            applyLoud()
        })

        p.addView(mkText("বেস বুস্ট (Bass)", 12f, Color.LTGRAY))
        p.addView(mkSeek(1000, bassStrength) {
            bassStrength = it
            applyBass()
        })

        p.addView(mkBtn("ফোনের মিডিয়া ভলিউম ফুল করুন", cCyan) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
            ).apply { topMargin = dp(12) }
        })
        return p
    }

    // ---------- কন্ট্রোল ----------
    private fun setBoost(v: Int) {
        boost = v.coerceIn(0, maxBoost())
        gain = boost / 100f
        boostTxt.text = "$boost%"
        boostSeek.progress = boost
    }

    private fun updateMicUi() {
        val color = if (running) cPink else cCyan
        micBtn.text = if (running) "🎤\nSTOP" else "🎤\nSTART"
        micBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (running) Color.parseColor("#3A0F22") else Color.parseColor("#0F2A3A"))
            setStroke(dp(4), color)
        }
    }

    private fun toggleMic() {
        if (running) {
            stopEngine()
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            } else {
                startEngine()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startEngine()
        } else {
            Toast.makeText(this, "মাইক পারমিশন লাগবে", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- ইফেক্ট ----------
    private fun applyEq() {
        try {
            val e = eq ?: return
            val r = e.bandLevelRange
            val n = e.numberOfBands.toInt()
            for (b in 0 until n) {
                val p = eqVals[b * 5 / n]
                val lvl = r[0] + (r[1] - r[0]) * p / 100
                e.setBandLevel(b.toShort(), lvl.toShort())
            }
        } catch (_: Exception) {
        }
    }

    private fun applyLoud() {
        try {
            loud?.setTargetGain(loudMb)
        } catch (_: Exception) {
        }
    }

    private fun applyBass() {
        try {
            val b = bass ?: return
            if (b.strengthSupported) b.setStrength(bassStrength.toShort())
        } catch (_: Exception) {
        }
    }

    // ---------- অডিও ইঞ্জিন ----------
    @SuppressLint("MissingPermission")
    private fun startEngine() {
        if (running) return
        val rate = 44100
        val minIn = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val minOut = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minIn <= 0 || minOut <= 0) {
            Toast.makeText(this, "অডিও চালু করা যাচ্ছে না", Toast.LENGTH_LONG).show()
            return
        }

        fun newRecord(src: Int): AudioRecord? = try {
            AudioRecord(
                src, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minIn * 2
            )
        } catch (_: Exception) {
            null
        }

        var rec = newRecord(
            if (mic2) MediaRecorder.AudioSource.CAMCORDER else MediaRecorder.AudioSource.MIC
        )
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            rec = newRecord(MediaRecorder.AudioSource.MIC)
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            Toast.makeText(this, "মাইক চালু হয়নি", Toast.LENGTH_LONG).show()
            return
        }

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minOut * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (_: Exception) {
            rec.release()
            Toast.makeText(this, "স্পিকার আউটপুট চালু হয়নি", Toast.LENGTH_LONG).show()
            return
        }

        // ইফেক্ট লাগানো
        val sid = track.audioSessionId
        try {
            eq = Equalizer(0, sid).apply { enabled = true }
        } catch (_: Exception) {
            eq = null
        }
        try {
            loud = LoudnessEnhancer(sid).apply { enabled = true }
        } catch (_: Exception) {
            loud = null
        }
        try {
            bass = BassBoost(0, sid).apply { enabled = true }
        } catch (_: Exception) {
            bass = null
        }
        applyEq()
        applyLoud()
        applyBass()

        // সিস্টেম ভলিউম ফুল
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        } catch (_: Exception) {
        }

        val recorder: AudioRecord = rec
        val bufSize = maxOf(256, minIn / 2)
        running = true
        updateMicUi()

        worker = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(bufSize)
            try {
                recorder.startRecording()
                track.play()
                while (running) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    val g = gain
                    var sum = 0.0
                    for (i in 0 until n) {
                        var s = buf[i].toInt()
                        if (abs(s) < 30) s = 0              // হালকা নয়েজ গেট
                        val x = s / 32768f * g
                        val y = tanh(x)                      // সফট লিমিটার: ফেটে/কর্কশ হবে না
                        buf[i] = (y * 32767f).toInt().toShort()
                        sum += (y * y).toDouble()
                    }
                    level = sqrt(sum / n).toFloat()
                    track.write(buf, 0, n)
                }
            } catch (_: Exception) {
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                try { recorder.release() } catch (_: Exception) {}
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
                try { eq?.release() } catch (_: Exception) {}
                try { loud?.release() } catch (_: Exception) {}
                try { bass?.release() } catch (_: Exception) {}
                eq = null
                loud = null
                bass = null
                level = 0f
                running = false
                runOnUiThread { updateMicUi() }
            }
        }.also { it.start() }
    }

    private fun stopEngine() {
        running = false
        try {
            worker?.join(1000)
        } catch (_: Exception) {
        }
        worker = null
        level = 0f
        updateMicUi()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        stopEngine()
        tone?.release()
        super.onDestroy()
    }
}
