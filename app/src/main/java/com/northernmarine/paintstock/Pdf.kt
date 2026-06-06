package com.northernmarine.paintstock

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.roundToInt

object Saver {
    fun savePdf(ctx: Context, doc: PdfDocument, fileName: String): Pair<Uri?, String> {
        var uri: Uri? = null; var label = ""; var os: OutputStream? = null
        try {
            val folder = Prefs.folderUri(ctx)
            if (folder != null) {
                try {
                    val tree = DocumentFile.fromTreeUri(ctx, folder)
                    if (tree != null && tree.canWrite()) {
                        tree.findFile(fileName)?.delete()
                        val f = tree.createFile("application/pdf", fileName)
                        if (f != null) { uri = f.uri; label = tree.name ?: "selected folder"; os = ctx.contentResolver.openOutputStream(uri!!) }
                    }
                } catch (_: Exception) {}
            }
            if (os == null) {
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/PaintStock")
                    }
                    uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), cv)
                    if (uri != null) { os = ctx.contentResolver.openOutputStream(uri!!); label = "Documents/PaintStock" }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "PaintStock"); dir.mkdirs()
                    val out = File(dir, fileName); uri = Uri.fromFile(out); os = FileOutputStream(out); label = dir.absolutePath
                }
            }
            if (os == null) {
                val out = File(ctx.cacheDir, fileName); os = FileOutputStream(out)
                uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", out); label = "app cache"
            }
            doc.writeTo(os)
            return Pair(uri, label)
        } catch (e: Exception) {
            return Pair(null, e.message ?: "error")
        } finally {
            try { os?.close() } catch (_: Exception) {}
            try { doc.close() } catch (_: Exception) {}
        }
    }
}

object Pdf {
    private const val PW = 595; private const val PH = 842; private const val M = 40f

    private fun p(size: Float, bold: Boolean = false, col: Int = Color.rgb(20, 20, 20)) =
        Paint().apply { color = col; textSize = size; isFakeBoldText = bold; isAntiAlias = true }

    // ---------- QR LABELS ---------- (entries = item + can serial)
    fun qrLabels(entries: List<Pair<PaintItem, Int>>): PdfDocument {
        val doc = PdfDocument()
        val title = p(15f, true, Color.rgb(15, 33, 64))
        val codeP = p(12f, true, Color.BLACK)
        val nameP = p(9f, false, Color.rgb(40, 40, 40))
        val applP = p(7.2f, false, Color.rgb(70, 90, 120))
        val smallP = p(7.2f, false, Color.rgb(120, 120, 120))
        val cut = Paint().apply { color = Color.rgb(180, 180, 180); strokeWidth = 0.5f; style = Paint.Style.STROKE }
        val cols = 2; val rows = 4; val per = cols * rows
        val cellW = (PW - 2 * M) / cols; val cellH = (PH - 2 * M - 24f) / rows
        val qr = minOf(cellW, cellH) - 92f
        val writer = QRCodeWriter()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, pageNo).create()); var c = page.canvas
        c.drawText("Paint Stock — product QR labels (print & stick on cans)", M, 26f, title)
        for ((idx, e) in entries.withIndex()) {
            val it = e.first
            val full = it.code + "-" + e.second.toString().padStart(3, '0')
            val onPage = idx % per
            if (idx > 0 && onPage == 0) {
                doc.finishPage(page); pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, pageNo).create()); c = page.canvas
                c.drawText("Paint Stock — QR labels", M, 26f, title)
            }
            val col = onPage % cols; val row = onPage / cols
            val cx = M + col * cellW; val cy = 36f + row * cellH
            c.drawRect(cx, cy, cx + cellW, cy + cellH, cut)
            val matrix = writer.encode(full, BarcodeFormat.QR_CODE, qr.toInt(), qr.toInt())
            val bmp = Bitmap.createBitmap(qr.toInt(), qr.toInt(), Bitmap.Config.ARGB_8888)
            for (x in 0 until qr.toInt()) for (yy in 0 until qr.toInt())
                bmp.setPixel(x, yy, if (matrix.get(x, yy)) Color.BLACK else Color.WHITE)
            c.drawBitmap(bmp, cx + (cellW - qr) / 2, cy + 8f, null); bmp.recycle()
            val x12 = cx + 12f
            c.drawText(full, x12, cy + cellH - 52f, codeP)
            c.drawText(it.title(), x12, cy + cellH - 39f, nameP)
            val lines = wrap(it.area, applP, cellW - 26f, 2)
            var ly = cy + cellH - 27f
            for (ln in lines) { c.drawText(ln, x12, ly, applP); ly += 9f }
            c.drawText("Can ${trim(it.canVol)} L · ${it.category}", x12, cy + cellH - 7f, smallP)
        }
        doc.finishPage(page); return doc
    }

    private fun wrap(text: String, paint: Paint, maxW: Float, maxLines: Int): List<String> {
        if (text.isBlank()) return emptyList()
        val words = text.split(" "); val out = ArrayList<String>(); var cur = ""
        for (w in words) {
            val t = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(t) <= maxW) cur = t
            else { if (cur.isNotEmpty()) out.add(cur); cur = w; if (out.size == maxLines - 1) break }
        }
        if (cur.isNotEmpty() && out.size < maxLines) out.add(cur)
        return out
    }

    // ---------- INVENTORY REPORT ----------
    fun inventory(ctx: Context, items: List<PaintItem>): PdfDocument {
        val doc = PdfDocument()
        val title = p(16f, true, Color.rgb(15, 33, 64))
        val lbl = p(8f, false, Color.rgb(120, 120, 120))
        val hd = p(9f, true, Color.rgb(40, 40, 40))
        val use = p(9f, true, Color.rgb(30, 60, 110))
        val txt = p(9f, false)
        val small = p(8f, false, Color.rgb(90, 90, 90))
        val line = Paint().apply { color = Color.rgb(200, 200, 200); strokeWidth = 0.6f }
        val accent = Paint().apply { color = Color.rgb(15, 33, 64) }
        val logo = try { android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_pdf) } catch (_: Throwable) { null }
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, pageNo).create()); var c = page.canvas
        fun footer() { c.drawLine(M, PH - 28f, PW - M, PH - 28f, line); c.drawText("ASPS Paint Stock · ${Prefs.vessel(ctx)}", M, PH - 16f, small); c.drawText("Page $pageNo", PW - M - 40f, PH - 16f, small) }
        fun newPage() { footer(); doc.finishPage(page); pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, pageNo).create()); c = page.canvas }

        c.drawRect(0f, 0f, PW.toFloat(), 4f, accent)
        if (logo != null) c.drawBitmap(Bitmap.createScaledBitmap(logo, 48, 48, true), PW - M - 48f, 24f, null)
        c.drawText("PAINT & THINNER INVENTORY", M, 40f, title)
        c.drawText("${Prefs.vessel(ctx)}   ·   ${Stock.dmy()}", M, 56f, small)
        var y = 82f
        val xCode = M; val xName = M + 52f; val xCan = M + 260f; val xQty = M + 320f; val xRob = M + 390f; val xMin = M + 470f
        fun colHead() {
            c.drawText("CODE", xCode, y, lbl); c.drawText("COLOUR", xName, y, lbl)
            c.drawText("CAN L", xCan, y, lbl); c.drawText("CANS", xQty, y, lbl); c.drawText("ROB L", xRob, y, lbl); c.drawText("MIN", xMin, y, lbl)
            y += 5f; c.drawLine(M, y, PW - M, y, line); y += 13f
        }
        var totPaint = 0.0; var totThin = 0.0
        for (cat in listOf("PAINT", "HARDENER", "THINNER")) {
            val catItems = items.filter { it.category == cat }
            if (catItems.isEmpty()) continue
            if (y > PH - 90f) { newPage(); y = 50f }
            c.drawText(cat, M, y, hd); y += 14f; colHead()
            val products = ArrayList<String>(); for (it in catItems) if (!products.contains(it.product)) products.add(it.product)
            for (prod in products) {
                val g = catItems.filter { it.product == prod }
                if (y > PH - 60f) { newPage(); y = 50f; colHead() }
                val u = Catalog.generalUse(prod)
                c.drawText(prod + (if (u.isBlank()) "" else "  —  $u"), M, y, use); y += 13f
                for (it in g) {
                    if (y > PH - 50f) { newPage(); y = 50f; colHead() }
                    val low = it.minCans > 0 && it.qtyCans <= it.minCans
                    c.drawText(it.code, xCode, y, txt)
                    c.drawText((it.color) + (if (it.colorCode.isBlank()) "" else " ${it.colorCode}"), xName, y, if (low) hd else txt)
                    c.drawText(trim(it.canVol), xCan, y, txt)
                    c.drawText(it.qtyCans.toString() + (if (low) " ⚠" else ""), xQty, y, if (low) hd else txt)
                    c.drawText(trim(it.robLitres()), xRob, y, txt)
                    c.drawText(if (it.minCans > 0) it.minCans.toString() else "-", xMin, y, small)
                    y += 13f
                    if (cat == "THINNER") totThin += it.robLitres() else totPaint += it.robLitres()
                }
                y += 5f
            }
            y += 6f
        }
        if (y > PH - 70f) { newPage(); y = 50f }
        c.drawLine(M, y, PW - M, y, line); y += 16f
        c.drawText("TOTAL PAINT + HARDENER:  ${trim(totPaint)} L", M, y, hd); y += 14f
        c.drawText("TOTAL THINNER:  ${trim(totThin)} L", M, y, hd)
        y += 30f
        c.drawLine(PW - M - 160f, y, PW - M, y, line); c.drawText("Chief Officer", PW - M - 150f, y + 14f, small)
        footer(); doc.finishPage(page); return doc
    }

    // ---------- QUOTATION / REQUISITION ----------
    fun quotation(ctx: Context, lines: List<Pair<PaintItem, Int>>): PdfDocument {
        val doc = PdfDocument()
        val title = p(16f, true, Color.rgb(15, 33, 64))
        val lbl = p(8f, false, Color.rgb(120, 120, 120))
        val hd = p(9f, true, Color.rgb(40, 40, 40))
        val txt = p(9f, false)
        val small = p(8f, false, Color.rgb(90, 90, 90))
        val line = Paint().apply { color = Color.rgb(200, 200, 200); strokeWidth = 0.6f }
        val accent = Paint().apply { color = Color.rgb(15, 33, 64) }
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, pageNo).create()); var c = page.canvas
        fun footer() { c.drawLine(M, PH - 28f, PW - M, PH - 28f, line); c.drawText("ASPS Paint Stock · ${Prefs.vessel(ctx)}", M, PH - 16f, small); c.drawText("Page $pageNo", PW - M - 40f, PH - 16f, small) }
        c.drawRect(0f, 0f, PW.toFloat(), 4f, accent)
        val qlogo = try { android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_pdf) } catch (_: Throwable) { null }
        if (qlogo != null) c.drawBitmap(Bitmap.createScaledBitmap(qlogo, 48, 48, true), PW - M - 48f, 24f, null)
        c.drawText("PAINT REQUISITION / QUOTATION", M, 36f, title)
        c.drawText("${Prefs.vessel(ctx)}   ·   ${Stock.dmy()}   ·   Supplier: ${Prefs.supplier(ctx)}", M, 52f, small)
        var y = 80f
        val xCode = M; val xName = M + 52f; val xCan = M + 300f; val xReq = M + 360f; val xPr = M + 430f; val xTot = M + 500f
        c.drawText("CODE", xCode, y, lbl); c.drawText("PRODUCT / COLOUR", xName, y, lbl); c.drawText("CAN L", xCan, y, lbl)
        c.drawText("CANS", xReq, y, lbl); c.drawText("PRICE", xPr, y, lbl); c.drawText("TOTAL", xTot, y, lbl)
        y += 6f; c.drawLine(M, y, PW - M, y, line); y += 14f
        var grand = 0.0; var anyPrice = false
        for ((it, qty) in lines) {
            if (qty <= 0) continue
            if (y > PH - 60f) { footer(); doc.finishPage(page); pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, pageNo).create()); c = page.canvas; y = 50f }
            c.drawText(it.code, xCode, y, txt); c.drawText(it.title(), xName, y, txt)
            c.drawText(trim(it.canVol), xCan, y, txt); c.drawText(qty.toString(), xReq, y, hd)
            if (it.pricePerCan > 0) { anyPrice = true; val lineTot = it.pricePerCan * qty; grand += lineTot
                c.drawText(trim(it.pricePerCan), xPr, y, txt); c.drawText(trim(lineTot), xTot, y, txt) }
            else { c.drawText("-", xPr, y, small); c.drawText("-", xTot, y, small) }
            y += 14f
        }
        y += 6f; c.drawLine(M, y, PW - M, y, line); y += 16f
        if (anyPrice) c.drawText("GRAND TOTAL:  ${trim(grand)}", M, y, hd)
        else c.drawText("Quantities only (no prices set).", M, y, small)
        y += 30f
        c.drawLine(PW - M - 160f, y, PW - M, y, line); c.drawText("Chief Officer", PW - M - 150f, y + 14f, small)
        footer(); doc.finishPage(page); return doc
    }

    private fun trim(d: Double): String { val r = (d * 10).roundToInt() / 10.0; return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString() }
}
