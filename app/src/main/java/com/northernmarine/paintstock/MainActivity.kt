package com.northernmarine.paintstock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import android.graphics.pdf.PdfDocument
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    enum class Screen { HOME, SCAN, ITEM, LIST, ADD, REPORTS, REQ, LABELS, SETTINGS }

    private lateinit var preview: PreviewView
    private lateinit var scanOverlay: LinearLayout
    private val panels = HashMap<Screen, android.view.View>()

    private var items = mutableListOf<PaintItem>()
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var analysisExec: ExecutorService
    private val scanner = BarcodeScanning.getClient()
    private var qrEnabled = false
    private var lastScan = 0L
    private var current: PaintItem? = null     // item being viewed/moved
    private var editing: PaintItem? = null     // item being edited (null = new)
    private val reqInputs = HashMap<String, EditText>()
    private val lblChecks = HashMap<String, android.widget.CheckBox>()
    private var countMode = true
    private val countSet = HashSet<String>()
    private val countTally = LinkedHashMap<String, Int>()

    private val folderPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            Prefs.setFolderUri(this, uri); updateFolderLabel(); toast("Reports folder set")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crash = App.readCrash(this)
        if (crash != null) { showCrash(crash); return }
        try { buildUi() } catch (e: Throwable) {
            val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
            val t = "CRASH (onCreate)\n$e\n\n$sw"
            try { java.io.File(filesDir, "last_crash.txt").writeText(t) } catch (_: Throwable) {}
            showCrash(t)
        }
    }

    private fun showCrash(text: String) {
        val sv = android.widget.ScrollView(this); val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.setPadding(28, 48, 28, 28); col.setBackgroundColor(0xFF12161B.toInt())
        val ti = TextView(this); ti.text = "App error — screenshot this"; ti.setTextColor(0xFFFF6B6B.toInt()); ti.textSize = 16f; ti.setPadding(0,0,0,16)
        val b = Button(this); b.text = "Clear & retry"; b.setOnClickListener { App.clearCrash(this); recreate() }
        val tv = TextView(this); tv.text = text; tv.setTextColor(0xFFE0E0E0.toInt()); tv.textSize = 11f; tv.setTextIsSelectable(true)
        col.addView(ti); col.addView(b); col.addView(tv); sv.addView(col); setContentView(sv)
    }

    private fun buildUi() {
        setContentView(R.layout.activity_main)
        analysisExec = Executors.newSingleThreadExecutor()
        items = Stock.load(this)

        preview = findViewById(R.id.preview)
        scanOverlay = findViewById(R.id.scanOverlay)
        panels[Screen.HOME] = findViewById(R.id.panelHome)
        panels[Screen.ITEM] = findViewById(R.id.panelItem)
        panels[Screen.LIST] = findViewById(R.id.panelList)
        panels[Screen.ADD] = findViewById(R.id.panelAdd)
        panels[Screen.REPORTS] = findViewById(R.id.panelReports)
        panels[Screen.REQ] = findViewById(R.id.panelReq)
        panels[Screen.LABELS] = findViewById(R.id.panelLabels)
        panels[Screen.SETTINGS] = findViewById(R.id.panelSettings)

        findViewById<TextView>(R.id.tvVersion).text = "native v1.7 · Paint Inventory"

        findViewById<Button>(R.id.btnScan).setOnClickListener { show(Screen.SCAN) }
        findViewById<Button>(R.id.btnList).setOnClickListener { show(Screen.LIST) }
        findViewById<Button>(R.id.btnReports).setOnClickListener { show(Screen.REPORTS) }
        findViewById<Button>(R.id.btnLabels).setOnClickListener { show(Screen.LABELS) }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { editing = null; show(Screen.ADD) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { show(Screen.SETTINGS) }
        findViewById<Button>(R.id.btnScanBack).setOnClickListener { show(Screen.HOME) }
        findViewById<Button>(R.id.btnModeMove).setOnClickListener { setMode(false) }
        findViewById<Button>(R.id.btnModeCount).setOnClickListener { setMode(true) }
        findViewById<Button>(R.id.btnCountFinish).setOnClickListener { finishCount() }
        findViewById<Button>(R.id.btnCountReset).setOnClickListener { resetCount() }

        findViewById<Button>(R.id.btnSet).setOnClickListener { applySet() }
        findViewById<Button>(R.id.btnRcvd).setOnClickListener { applyMove(true) }
        findViewById<Button>(R.id.btnSpent).setOnClickListener { applyMove(false) }
        findViewById<Button>(R.id.btnEditItem).setOnClickListener { editing = current; show(Screen.ADD) }
        findViewById<Button>(R.id.btnItemBack).setOnClickListener { show(Screen.SCAN) }

        findViewById<Button>(R.id.btnListBack).setOnClickListener { show(Screen.HOME) }
        findViewById<Button>(R.id.btnSaveItem).setOnClickListener { saveItem() }
        findViewById<Button>(R.id.btnDeleteItem).setOnClickListener { deleteItem() }
        findViewById<Button>(R.id.btnAddBack).setOnClickListener { show(if (editing != null) Screen.ITEM else Screen.HOME) }

        findViewById<Button>(R.id.btnInvOpen).setOnClickListener { exportPdf(Pdf.inventory(this, items), "PaintInventory_${Stock.stamp()}.pdf", false) }
        findViewById<Button>(R.id.btnInvShare).setOnClickListener { exportPdf(Pdf.inventory(this, items), "PaintInventory_${Stock.stamp()}.pdf", true) }
        findViewById<Button>(R.id.btnConsOpen).setOnClickListener { openConsumption(false) }
        findViewById<Button>(R.id.btnConsShare).setOnClickListener { openConsumption(true) }
        findViewById<Button>(R.id.btnQuoAuto).setOnClickListener { quoteAuto() }
        findViewById<Button>(R.id.btnQuoManual).setOnClickListener { show(Screen.REQ) }
        findViewById<Button>(R.id.btnLabOpen).setOnClickListener { show(Screen.LABELS) }
        findViewById<Button>(R.id.btnLabShare).setOnClickListener { show(Screen.LABELS) }
        findViewById<Button>(R.id.btnReportsBack).setOnClickListener { show(Screen.HOME) }

        findViewById<Button>(R.id.btnLblAll).setOnClickListener { lblSelect("all") }
        findViewById<Button>(R.id.btnLblNone).setOnClickListener { lblSelect("none") }
        findViewById<Button>(R.id.btnLblStock).setOnClickListener { lblSelect("stock") }
        findViewById<Button>(R.id.btnLblLow).setOnClickListener { lblSelect("low") }
        findViewById<Button>(R.id.btnLblGen).setOnClickListener { askCopiesAndGenerate() }
        findViewById<Button>(R.id.btnLblBack).setOnClickListener { show(Screen.HOME) }

        findViewById<Button>(R.id.btnReqGen).setOnClickListener { quoteManual() }
        findViewById<Button>(R.id.btnReqBack).setOnClickListener { show(Screen.REPORTS) }

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            Prefs.setVessel(this, findViewById<EditText>(R.id.etVessel).text.toString().trim().ifBlank { "Navigator Copernico" })
            Prefs.setSupplier(this, findViewById<EditText>(R.id.etSupplier).text.toString().trim().ifBlank { "PPG / Sigma Coatings" })
            Prefs.setEmail(this, findViewById<EditText>(R.id.etEmail).text.toString().trim())
            toast("Settings saved")
        }
        findViewById<Button>(R.id.btnChooseFolder).setOnClickListener { try { folderPicker.launch(null) } catch (e: Exception) { toast("Cannot open picker") } }
        findViewById<Button>(R.id.btnResetSeed).setOnClickListener {
            AlertDialog.Builder(this).setMessage("Reset stock to the factory list (loses changes)?")
                .setPositiveButton("Reset") { _, _ -> items = Catalog.seed(); Stock.save(this, items); toast("Reset done"); show(Screen.HOME) }
                .setNegativeButton("Cancel", null).show()
        }
        findViewById<Button>(R.id.btnSettingsBack).setOnClickListener { show(Screen.HOME) }

        // category spinner
        val sp = findViewById<Spinner>(R.id.spCategory)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("PAINT", "HARDENER", "THINNER"))

        show(Screen.HOME)
    }

    override fun onDestroy() { super.onDestroy(); try { analysisExec.shutdown() } catch (_: Exception) {}; try { scanner.close() } catch (_: Exception) {} }

    private fun show(s: Screen) {
        for ((k, v) in panels) v.visibility = if (k == s) android.view.View.VISIBLE else android.view.View.GONE
        val scan = s == Screen.SCAN
        scanOverlay.visibility = if (scan) android.view.View.VISIBLE else android.view.View.GONE
        preview.visibility = if (scan) android.view.View.VISIBLE else android.view.View.GONE
        when (s) {
            Screen.HOME -> renderHome()
            Screen.SCAN -> { ensureCamera(); qrEnabled = true; setMode(countMode); findViewById<TextView>(R.id.tvScanHint).text = "Scanning…" }
            Screen.ITEM -> renderItem()
            Screen.LIST -> renderList()
            Screen.ADD -> renderAdd()
            Screen.REQ -> renderReq()
            Screen.LABELS -> renderLabels()
            Screen.SETTINGS -> loadSettings()
            else -> {}
        }
    }

    private fun renderHome() {
        findViewById<TextView>(R.id.tvHomeVessel).text = Prefs.vessel(this).uppercase()
        var paint = 0.0; var thin = 0.0
        for (it in items) if (it.category == "THINNER") thin += it.robLitres() else paint += it.robLitres()
        findViewById<TextView>(R.id.tvHomeTotals).text = "On board: ${trim(paint)} L paint · ${trim(thin)} L thinner · ${items.size} items"
    }

    // ---------- scanning ----------
    private fun ensureCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101); return
        }
        if (cameraProvider != null) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ try { cameraProvider = future.get(); bindCamera() } catch (e: Exception) { toast("Camera error: ${e.message}") } }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(markerClass = [ExperimentalGetImage::class])
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        provider.unbindAll()
        val prev = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetResolution(Size(1280, 720)).build()
        analysis.setAnalyzer(analysisExec) { proxy: ImageProxy ->
            val media = proxy.image
            if (media != null && qrEnabled) {
                val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                scanner.process(input)
                    .addOnSuccessListener { list -> val v = list.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue; if (v != null) runOnUiThread { onQr(v) } }
                    .addOnCompleteListener { proxy.close() }
            } else proxy.close()
        }
        try { provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, analysis) }
        catch (e: Exception) { toast("Camera bind failed: ${e.message}") }
    }

    private fun onQr(raw: String) {
        if (!qrEnabled) return
        val now = System.currentTimeMillis(); if (now - lastScan < 1200) return; lastScan = now
        val code = raw.trim()
        val base = code.substringBefore("-")
        val item = items.firstOrNull { it.code.equals(base, true) }
        if (item == null) { toast("Unknown label: $code"); return }
        if (countMode) {
            if (countSet.contains(code)) { toast("Already counted: $code"); return }
            countSet.add(code); countTally[base] = (countTally[base] ?: 0) + 1
            renderCount(); toast("+1 $base = ${countTally[base]}")
        } else {
            qrEnabled = false
            current = item
            show(Screen.ITEM)
        }
    }

    private fun setMode(on: Boolean) {
        countMode = on
        findViewById<Button>(R.id.btnModeMove).setBackgroundResource(if (on) R.drawable.btn_dark else R.drawable.btn_blue)
        findViewById<Button>(R.id.btnModeCount).setBackgroundResource(if (on) R.drawable.btn_blue else R.drawable.btn_dark)
        findViewById<LinearLayout>(R.id.countPanel).visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<TextView>(R.id.tvScanInfo).text = if (on) "COUNT mode: scan each can once — duplicates are ignored" else "Point at a paint can QR label"
        if (on) renderCount()
    }

    private fun renderCount() {
        val box = findViewById<LinearLayout>(R.id.countList); box.removeAllViews()
        if (countTally.isEmpty()) {
            val tv = TextView(this); tv.text = "Nothing scanned yet."; tv.setTextColor(0xFF9FC0E8.toInt()); tv.textSize = 13f; box.addView(tv); return
        }
        for ((base, n) in countTally) {
            val it = items.firstOrNull { it.code == base }
            val row = TextView(this)
            row.text = "$base  ${it?.title() ?: ""}    =  $n"
            row.setTextColor(0xFFFFFFFF.toInt()); row.textSize = 13f; row.setPadding(0, 4, 0, 4)
            box.addView(row)
        }
    }

    private fun resetCount() { countSet.clear(); countTally.clear(); renderCount(); toast("Count reset") }

    private fun finishCount() {
        if (countTally.isEmpty()) { toast("Nothing scanned"); return }
        val counted = countTally.entries.joinToString("\n") { "${it.key} = ${it.value}" }
        val zeroed = Stock.notScanned(items, countTally)
        var msg = "Save this as the new inventory (current stock)?\n\nCounted:\n$counted"
        if (zeroed.isNotEmpty()) msg += "\n\nNOT scanned → set to 0:\n" + zeroed.joinToString("\n") { "${it.code} ${it.title()} (${it.qtyCans}→0)" }
        AlertDialog.Builder(this).setTitle("Finish inventory").setMessage(msg)
            .setPositiveButton("Save inventory") { _, _ ->
                Stock.finishInventory(this, items, HashMap(countTally))
                resetCount(); toast("Inventory saved"); show(Screen.HOME)
            }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) { if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) ensureCamera() else toast("Camera permission required") }
    }

    // ---------- item movement ----------
    private fun renderItem() {
        val it = current ?: return
        findViewById<TextView>(R.id.tvItemTitle).text = "${it.code}  ${it.title()}"
        val th = if (it.thinner.isBlank()) "" else " · thinner ${it.thinner}"
        findViewById<TextView>(R.id.tvItemMeta).text = "${it.category} · can ${trim(it.canVol)} L$th\n${it.area}"
        findViewById<TextView>(R.id.tvItemRob).text = "On board: ${it.qtyCans} cans  =  ${trim(it.robLitres())} L" +
            (if (it.minCans > 0 && it.qtyCans <= it.minCans) "   ⚠ below min (${it.minCans})" else "")
        findViewById<EditText>(R.id.etSet).setText(it.qtyCans.toString())
        findViewById<EditText>(R.id.etMove).setText("")
    }

    private fun applySet() {
        val it = current ?: return
        val n = findViewById<EditText>(R.id.etSet).text.toString().toIntOrNull() ?: return toast("Enter a number")
        if (n < 0) return toast("Invalid")
        it.qtyCans = n; Stock.save(this, items); toast("Set to $n cans"); show(Screen.SCAN)
    }

    private fun applyMove(received: Boolean) {
        val it = current ?: return
        val n = findViewById<EditText>(R.id.etMove).text.toString().toIntOrNull() ?: return toast("Enter cans")
        if (n <= 0) return toast("Enter cans")
        it.qtyCans = (it.qtyCans + (if (received) n else -n)).coerceAtLeast(0)
        Stock.save(this, items); toast(if (received) "+$n received" else "-$n spent"); show(Screen.SCAN)
    }

    // ---------- list ----------
    private fun renderList() {
        val box = findViewById<LinearLayout>(R.id.listContainer); box.removeAllViews()
        for (cat in listOf("PAINT", "HARDENER", "THINNER")) {
            val catItems = items.filter { it.category == cat }; if (catItems.isEmpty()) continue
            val head = TextView(this); head.text = cat; head.setTextColor(0xFF6E8AAE.toInt()); head.textSize = 12f; head.setPadding(6, 16, 6, 6)
            box.addView(head)
            val products = ArrayList<String>(); for (it in catItems) if (!products.contains(it.product)) products.add(it.product)
            for (prod in products) {
                val u = Catalog.generalUse(prod)
                val ph = TextView(this); ph.text = prod + (if (u.isBlank()) "" else "\n$u")
                ph.setTextColor(0xFFCFE3FF.toInt()); ph.textSize = 13f; ph.setPadding(6, 10, 6, 4); box.addView(ph)
                for (it in catItems.filter { it.product == prod }) {
                    val item = it
                    val b = Button(this)
                    val low = item.minCans > 0 && item.qtyCans <= item.minCans
                    val col = (if (item.color.isBlank()) item.product else item.color) + (if (item.colorCode.isBlank()) "" else " ${item.colorCode}")
                    b.text = "${item.code}  $col\n${item.qtyCans} cans · ${trim(item.robLitres())} L" + (if (low) "  ⚠" else "")
                    b.isAllCaps = false; b.setTextColor(0xFFFFFFFF.toInt()); b.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    b.setBackgroundResource(if (low) R.drawable.btn_red else R.drawable.btn_dark)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = 8; b.layoutParams = lp
                    b.setPadding(28, 18, 20, 18)
                    b.setOnClickListener { current = item; show(Screen.ITEM) }
                    box.addView(b)
                }
            }
        }
    }

    // ---------- labels selection ----------
    private fun renderLabels() {
        val box = findViewById<LinearLayout>(R.id.lblContainer); box.removeAllViews(); lblChecks.clear()
        for (cat in listOf("PAINT", "HARDENER", "THINNER")) {
            val catItems = items.filter { it.category == cat }; if (catItems.isEmpty()) continue
            val head = TextView(this); head.text = cat; head.setTextColor(0xFF6E8AAE.toInt()); head.textSize = 12f; head.setPadding(2, 14, 2, 4); box.addView(head)
            for (it in catItems) {
                val cb = android.widget.CheckBox(this)
                cb.text = "${it.code}  ${it.title()}\n${it.area}"
                cb.setTextColor(0xFFFFFFFF.toInt()); cb.textSize = 14f; cb.setPadding(12, 10, 8, 10)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = 4; cb.layoutParams = lp
                lblChecks[it.code] = cb; box.addView(cb)
            }
        }
    }

    private fun lblSelect(mode: String) {
        for (it in items) {
            val cb = lblChecks[it.code] ?: continue
            cb.isChecked = when (mode) {
                "all" -> true; "none" -> false; "stock" -> it.qtyCans > 0
                else -> it.minCans > 0 && it.qtyCans <= it.minCans
            }
        }
    }

    private fun askCopiesAndGenerate() {
        val selected = items.filter { lblChecks[it.code]?.isChecked == true }
        if (selected.isEmpty()) { toast("Select at least one"); return }
        val scroll = android.widget.ScrollView(this)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.setPadding(40, 20, 40, 8)
        val info = TextView(this); info.text = "How many labels to print for each paint?"; info.setTextColor(0xFF9FC0E8.toInt()); info.textSize = 13f; info.setPadding(0, 0, 0, 10); col.addView(info)
        val inputs = HashMap<String, EditText>()
        for (it in selected) {
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; row.gravity = android.view.Gravity.CENTER_VERTICAL; row.setPadding(0, 6, 0, 6)
            val tv = TextView(this); tv.text = "${it.code}  ${it.title()}"; tv.textSize = 13f
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val et = EditText(this); et.inputType = android.text.InputType.TYPE_CLASS_NUMBER; et.setText("1"); et.setEms(3)
            inputs[it.code] = et
            row.addView(tv); row.addView(et); col.addView(row)
        }
        scroll.addView(col)
        AlertDialog.Builder(this).setTitle("Print labels").setView(scroll)
            .setPositiveButton("Generate") { _, _ ->
                val entries = ArrayList<Pair<PaintItem, Int>>()
                for (sel in selected) {
                    val n = inputs[sel.code]?.text?.toString()?.toIntOrNull() ?: 0
                    repeat(n.coerceAtLeast(0)) { sel.serial = (sel.serial % 200) + 1; entries.add(Pair(sel, sel.serial)) }
                }
                if (entries.isEmpty()) { toast("Enter at least one copy") }
                else { Stock.save(this, items); exportPdf(Pdf.qrLabels(entries), "PaintStock_QR_labels_${Stock.stamp()}.pdf", false) }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- add / edit ----------
    private fun renderAdd() {
        val e = editing
        findViewById<TextView>(R.id.tvAddTitle).text = if (e == null) "Add paint" else "Edit paint"
        findViewById<Button>(R.id.btnDeleteItem).visibility = if (e == null) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<EditText>(R.id.etProduct).setText(e?.product ?: "")
        findViewById<EditText>(R.id.etColor).setText(e?.color ?: "")
        findViewById<EditText>(R.id.etColorCode).setText(e?.colorCode ?: "")
        findViewById<EditText>(R.id.etCanVol).setText(if (e != null) trim(e.canVol) else "")
        findViewById<EditText>(R.id.etThinner).setText(e?.thinner ?: "")
        findViewById<EditText>(R.id.etArea).setText(e?.area ?: "")
        findViewById<EditText>(R.id.etQty).setText(if (e != null) e.qtyCans.toString() else "0")
        findViewById<EditText>(R.id.etMin).setText(if (e != null) e.minCans.toString() else "0")
        findViewById<EditText>(R.id.etPrice).setText(if (e != null && e.pricePerCan > 0) trim(e.pricePerCan) else "")
        val sp = findViewById<Spinner>(R.id.spCategory)
        sp.setSelection(when (e?.category) { "HARDENER" -> 1; "THINNER" -> 2; else -> 0 })
    }

    private fun saveItem() {
        val product = findViewById<EditText>(R.id.etProduct).text.toString().trim()
        if (product.isBlank()) return toast("Product required")
        val canVol = findViewById<EditText>(R.id.etCanVol).text.toString().toDoubleOrNull() ?: return toast("Can volume required")
        val cat = (findViewById<Spinner>(R.id.spCategory).selectedItem as? String) ?: "PAINT"
        val e = editing
        if (e == null) {
            items.add(PaintItem(Stock.nextCode(items), product,
                findViewById<EditText>(R.id.etColor).text.toString().trim(),
                findViewById<EditText>(R.id.etColorCode).text.toString().trim(), cat, canVol,
                findViewById<EditText>(R.id.etThinner).text.toString().trim(),
                findViewById<EditText>(R.id.etArea).text.toString().trim(),
                findViewById<EditText>(R.id.etQty).text.toString().toIntOrNull() ?: 0,
                findViewById<EditText>(R.id.etMin).text.toString().toIntOrNull() ?: 0,
                findViewById<EditText>(R.id.etPrice).text.toString().toDoubleOrNull() ?: 0.0))
            toast("Added")
        } else {
            e.product = product; e.color = findViewById<EditText>(R.id.etColor).text.toString().trim()
            e.colorCode = findViewById<EditText>(R.id.etColorCode).text.toString().trim(); e.category = cat
            e.canVol = canVol; e.thinner = findViewById<EditText>(R.id.etThinner).text.toString().trim()
            e.area = findViewById<EditText>(R.id.etArea).text.toString().trim()
            e.qtyCans = findViewById<EditText>(R.id.etQty).text.toString().toIntOrNull() ?: e.qtyCans
            e.minCans = findViewById<EditText>(R.id.etMin).text.toString().toIntOrNull() ?: 0
            e.pricePerCan = findViewById<EditText>(R.id.etPrice).text.toString().toDoubleOrNull() ?: 0.0
            toast("Saved")
        }
        Stock.save(this, items); show(Screen.HOME)
    }

    private fun deleteItem() {
        val e = editing ?: return
        AlertDialog.Builder(this).setMessage("Delete ${e.title()}?")
            .setPositiveButton("Delete") { _, _ -> items.remove(e); Stock.save(this, items); toast("Deleted"); show(Screen.HOME) }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- requisition ----------
    private fun renderReq() {
        val box = findViewById<LinearLayout>(R.id.reqContainer); box.removeAllViews(); reqInputs.clear()
        for (it in items) {
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; row.setPadding(0, 6, 0, 6)
            val tv = TextView(this); tv.text = "${it.code}  ${it.title()}\n${trim(it.robLitres())} L on board"
            tv.setTextColor(0xFFCFE3FF.toInt()); tv.textSize = 13f
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val et = EditText(this); et.hint = "litres"; et.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            et.setBackgroundResource(R.drawable.field); et.setTextColor(0xFFFFFFFF.toInt()); et.setPadding(20, 16, 20, 16)
            et.layoutParams = LinearLayout.LayoutParams(180, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (it.minCans > 0 && it.qtyCans < it.minCans) et.setText(trim((it.minCans - it.qtyCans) * it.canVol))
            reqInputs[it.code] = et
            row.addView(tv); row.addView(et); box.addView(row)
        }
    }

    private fun quoteManual() {
        val lines = ArrayList<Pair<PaintItem, Double>>()
        for (it in items) { val l = reqInputs[it.code]?.text?.toString()?.toDoubleOrNull() ?: 0.0; if (l > 0) lines.add(Pair(it, l)) }
        if (lines.isEmpty()) return toast("Enter litres first")
        exportPdf(Pdf.quotation(this, lines), "PaintQuotation_${Stock.stamp()}.pdf", false)
    }

    private fun openConsumption(share: Boolean) {
        val state = Stock.consumption(this)
        if (state == null) { toast("Do at least two inventories first"); return }
        exportPdf(Pdf.consumption(this, state), "PaintConsumption_${Stock.stamp()}.pdf", share)
    }

    private fun quoteAuto() {
        val lines = ArrayList<Pair<PaintItem, Double>>()
        for (it in items) if (it.minCans > 0 && it.qtyCans < it.minCans) lines.add(Pair(it, (it.minCans - it.qtyCans) * it.canVol))
        if (lines.isEmpty()) return toast("No items below minimum (set MIN on items first)")
        exportPdf(Pdf.quotation(this, lines), "PaintQuotation_${Stock.stamp()}.pdf", false)
    }

    // ---------- settings ----------
    private fun loadSettings() {
        findViewById<EditText>(R.id.etVessel).setText(Prefs.vessel(this))
        findViewById<EditText>(R.id.etSupplier).setText(Prefs.supplier(this))
        findViewById<EditText>(R.id.etEmail).setText(Prefs.email(this))
        updateFolderLabel()
    }
    private fun updateFolderLabel() {
        val u = Prefs.folderUri(this)
        findViewById<TextView>(R.id.tvFolder).text = if (u == null) "Default: Documents/PaintStock" else "Selected: " + (u.lastPathSegment ?: u.toString())
    }

    // ---------- pdf export ----------
    private fun exportPdf(doc: PdfDocument, name: String, share: Boolean) {
        toast("Building PDF…")
        Thread {
            val (uri, where) = Saver.savePdf(this, doc, name)
            runOnUiThread {
                if (uri == null) { toast("Could not create PDF ($where)"); return@runOnUiThread }
                if (share) {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Paint stock — ${Prefs.vessel(this@MainActivity)}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val email = Prefs.email(this)
                    if (email.isNotBlank()) { val r = email.split(Regex("[,;\\s]+")).filter { it.contains("@") }.toTypedArray(); if (r.isNotEmpty()) i.putExtra(Intent.EXTRA_EMAIL, r) }
                    startActivity(Intent.createChooser(i, "Send"))
                } else {
                    val i = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    try { startActivity(i) } catch (e: Exception) { toast("No PDF viewer. Saved: $where") }
                }
            }
        }.start()
    }

    private fun trim(d: Double): String { val r = (d * 10).roundToInt() / 10.0; return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString() }
    private fun toast(m: String) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
}
