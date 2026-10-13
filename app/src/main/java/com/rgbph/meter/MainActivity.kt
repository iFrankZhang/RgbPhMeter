package com.rgbph.meter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var frozenView: ImageView
    private lateinit var overlay: SelectionOverlay
    private lateinit var modeTag: TextView
    private lateinit var swatch: View
    private lateinit var tvRgb: TextView
    private lateinit var tvPix: TextView
    private lateinit var tvPh: TextView
    private lateinit var tvPhF: TextView
    private lateinit var tvBac: TextView
    private lateinit var tvBacF: TextView
    private lateinit var tvWarn: TextView
    private lateinit var etPa: EditText
    private lateinit var etPb: EditText
    private lateinit var etBa: EditText
    private lateinit var etBb: EditText
    private lateinit var logList: LinearLayout
    private lateinit var tvLogEmpty: TextView
    private lateinit var btnFreeze: TextView

    private val prefs by lazy { getSharedPreferences("meter", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    /** 冻结 / 相册图片；为 null 时表示实时取景 */
    private var frozen: Bitmap? = null
    private var last: Reading? = null
    private val records = mutableListOf<Reading>()

    data class Reading(
        val r: Double, val g: Double, val b: Double,
        val ph: Double, val bac: Double, val time: String
    )

    // 实时刷新：每 150ms 取一次预览画面计算选区平均色
    private val tick = object : Runnable {
        override fun run() {
            if (frozen == null) preview.bitmap?.let { measure(it) }
            handler.postDelayed(this, 150)
        }
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera()
        else tvPix.text = "没有相机权限。可在系统设置里开启，或用「从相册选图」。"
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { loadImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        preview.scaleType = PreviewView.ScaleType.FILL_CENTER
        frozenView = findViewById(R.id.frozen)
        overlay = findViewById(R.id.overlay)
        modeTag = findViewById(R.id.modeTag)
        swatch = findViewById(R.id.swatch)
        tvRgb = findViewById(R.id.tvRgb)
        tvPix = findViewById(R.id.tvPix)
        tvPh = findViewById(R.id.tvPh)
        tvPhF = findViewById(R.id.tvPhF)
        tvBac = findViewById(R.id.tvBac)
        tvBacF = findViewById(R.id.tvBacF)
        tvWarn = findViewById(R.id.tvWarn)
        etPa = findViewById(R.id.etPa)
        etPb = findViewById(R.id.etPb)
        etBa = findViewById(R.id.etBa)
        etBb = findViewById(R.id.etBb)
        logList = findViewById(R.id.logList)
        tvLogEmpty = findViewById(R.id.tvLogEmpty)
        btnFreeze = findViewById(R.id.btnFreeze)

        // 公式系数（默认 pH = 0.1R + 2，细菌 = 0.2R - 1）
        etPa.setText(prefs.getString("pa", "0.1"))
        etPb.setText(prefs.getString("pb", "2"))
        etBa.setText(prefs.getString("ba", "0.2"))
        etBb.setText(prefs.getString("bb", "-1"))
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit()
                    .putString("pa", etPa.text.toString()).putString("pb", etPb.text.toString())
                    .putString("ba", etBa.text.toString()).putString("bb", etBb.text.toString())
                    .apply()
                frozen?.let { measure(it) }
            }
        }
        listOf(etPa, etPb, etBa, etBb).forEach { it.addTextChangedListener(watcher) }

        overlay.onChanged = { frozen?.let { measure(it) } }

        btnFreeze.setOnClickListener { toggleFreeze() }
        findViewById<View>(R.id.btnGallery).setOnClickListener { pickImage.launch("image/*") }
        findViewById<View>(R.id.btnRecord).setOnClickListener { record() }
        findViewById<View>(R.id.btnShare).setOnClickListener { shareCsv() }
        findViewById<View>(R.id.btnClear).setOnClickListener { confirmClear() }

        loadRecords()
        renderRecords()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p)
            } catch (e: Exception) {
                tvPix.text = "相机启动失败：${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- 冻结 / 相册 ----------

    private fun toggleFreeze() {
        if (frozen != null) {
            frozen = null
            frozenView.visibility = View.GONE
            modeTag.text = "实时"
            btnFreeze.text = "冻结画面"
        } else {
            val bmp = preview.bitmap ?: run { toast("画面还没准备好"); return }
            showFrozen(bmp, "已冻结")
        }
    }

    private fun showFrozen(bmp: Bitmap, tag: String) {
        frozen = bmp
        frozenView.setImageBitmap(bmp)
        frozenView.visibility = View.VISIBLE
        modeTag.text = tag
        btnFreeze.text = "回到实时"
        measure(bmp)
    }

    /** 把相册图片按“完整显示”缩放到取景区大小，保证选框坐标与像素一一对应 */
    private fun loadImage(uri: Uri) {
        val w = overlay.width; val h = overlay.height
        if (w == 0 || h == 0) return
        val src = try {
            contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) { null }
        if (src == null) { toast("这张图片打不开，换一张试试"); return }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.parseColor("#E1F1F3"))
        val s = min(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = src.width * s; val dh = src.height * s
        val dst = RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
        c.drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        showFrozen(out, "相册图片")
    }

    // ---------- 计算 ----------

    private fun coef(et: EditText, def: Double) = et.text.toString().toDoubleOrNull() ?: def

    private fun measure(bmp: Bitmap) {
        if (overlay.width == 0) return
        // 预览截图与叠加层尺寸可能略有差异，按比例换算
        val sx = bmp.width.toFloat() / overlay.width
        val sy = bmp.height.toFloat() / overlay.height
        val r0 = overlay.rect
        val x = max(0, (r0.left * sx).toInt())
        val y = max(0, (r0.top * sy).toInt())
        val w = max(1, min(bmp.width - x, (r0.width() * sx).toInt()))
        val h = max(1, min(bmp.height - y, (r0.height() * sy).toInt()))
        if (w <= 0 || h <= 0) return

        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x, y, w, h)
        var rs = 0L; var gs = 0L; var bs = 0L
        for (c in px) { rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF }
        val n = px.size.toDouble()
        val r = rs / n; val g = gs / n; val b = bs / n

        val pa = coef(etPa, 0.1); val pb = coef(etPb, 2.0)
        val ba = coef(etBa, 0.2); val bb = coef(etBb, -1.0)
        val ph = pa * r + pb
        val bac = ba * r + bb

        last = Reading(r, g, b, ph, bac, "")
        (swatch.background.mutate() as? GradientDrawable)
            ?.setColor(Color.rgb(r.toInt(), g.toInt(), b.toInt()))
        tvRgb.text = "R %.1f   G %.1f   B %.1f".format(r, g, b)
        tvPix.text = "选区 ${w}×${h} 像素，共 ${px.size} 个点取平均"
        tvPh.text = "%.2f".format(ph)
        tvBac.text = "%.2f".format(bac)
        tvPhF.text = "= $pa × %.1f + $pb".format(r)
        tvBacF.text = "= $ba × %.1f + $bb".format(r)

        val warns = mutableListOf<String>()
        if (ph < 0 || ph > 14) warns += "pH 超出 0–14"
        if (bac < 0) warns += "细菌数量为负"
        if (warns.isEmpty()) tvWarn.visibility = View.GONE
        else { tvWarn.text = "⚠ " + warns.joinToString("，") + "，检查公式系数"; tvWarn.visibility = View.VISIBLE }
    }

    // ---------- 记录 ----------

    private fun record() {
        val l = last ?: return
        val t = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        records += l.copy(time = t)
        saveRecords(); renderRecords()
        toast("已记录第 ${records.size} 条")
    }

    private fun renderRecords() {
        logList.removeAllViews()
        records.forEachIndexed { i, r ->
            val row = TextView(this).apply {
                text = "%2d  R%.0f G%.0f B%.0f  pH %.2f  菌 %.2f  %s".format(i + 1, r.r, r.g, r.b, r.ph, r.bac, r.time)
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.fg))
                setPadding(0, 6, 0, 6)
            }
            logList.addView(row)
        }
        tvLogEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun saveRecords() {
        val arr = JSONArray()
        records.forEach {
            arr.put(JSONObject().put("r", it.r).put("g", it.g).put("b", it.b)
                .put("ph", it.ph).put("bac", it.bac).put("t", it.time))
        }
        prefs.edit().putString("records", arr.toString()).apply()
    }

    private fun loadRecords() {
        try {
            val arr = JSONArray(prefs.getString("records", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                records += Reading(o.getDouble("r"), o.getDouble("g"), o.getDouble("b"),
                    o.getDouble("ph"), o.getDouble("bac"), o.optString("t"))
            }
        } catch (_: Exception) {}
    }

    private fun shareCsv() {
        if (records.isEmpty()) { toast("还没有记录"); return }
        val sb = StringBuilder("序号,R,G,B,pH,细菌数量,时间\n")
        records.forEachIndexed { i, r ->
            sb.append("${i + 1},%.1f,%.1f,%.1f,%.2f,%.2f,%s\n".format(r.r, r.g, r.b, r.ph, r.bac, r.time))
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "比色读数记录")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(send, "导出读数记录"))
    }

    private var clearArmed = false
    private fun confirmClear() {
        if (records.isEmpty()) return
        if (!clearArmed) {
            clearArmed = true
            toast("再点一次「清空记录」确认")
            handler.postDelayed({ clearArmed = false }, 3000)
            return
        }
        clearArmed = false
        records.clear(); saveRecords(); renderRecords()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
