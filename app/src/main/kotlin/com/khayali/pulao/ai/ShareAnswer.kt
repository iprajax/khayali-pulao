package com.khayali.pulao.ai

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import com.khayali.pulao.Kit
import com.khayali.pulao.Pal
import com.khayali.pulao.R
import java.io.File

/**
 * Share one answer (its result card + text) as a branded image to WhatsApp, X, Instagram…
 * Rendered on the phone; the image goes only where the user chooses.
 */
object ShareAnswer {
    const val AUTHORITY = "com.khayali.pulao.share"

    fun share(ctx: Context, answer: View, question: String) {
        val k = Kit(ctx)
        val pad = k.dp(28); val head = k.dp(76); val foot = k.dp(64)
        val w = answer.width; val h = answer.height
        if (w <= 0 || h <= 0) return
        val bmp = Bitmap.createBitmap(w + pad * 2, h + head + foot + pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Pal.BG)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Header: icon + question
        (ctx.getDrawable(R.drawable.bubble_icon) as? BitmapDrawable)?.bitmap?.let {
            c.drawBitmap(Bitmap.createScaledBitmap(it, k.dp(36), k.dp(36), true), pad.toFloat(), k.dpf(22), p)
        }
        p.color = Pal.TEXT; p.textSize = k.dpf(16); p.isFakeBoldText = true
        c.drawText("Khayali Pulao", pad + k.dpf(46), k.dpf(38), p)
        p.isFakeBoldText = false; p.color = Pal.MUTED; p.textSize = k.dpf(13)
        val q = if (question.length > 70) question.take(67) + "…" else question
        c.drawText("“$q”", pad + k.dpf(46), k.dpf(58), p)
        // Body
        c.save(); c.translate(pad.toFloat(), head.toFloat()); answer.draw(c); c.restore()
        // Footer
        p.color = Pal.LINE; c.drawRect(pad.toFloat(), (head + h + k.dp(10)).toFloat(), (w + pad).toFloat(), (head + h + k.dp(11)).toFloat(), p)
        p.color = Pal.DIM; p.textSize = k.dpf(12)
        c.drawText("Daydreamed on-device with Khayali Pulao · not investment advice", pad.toFloat(), (head + h + k.dp(36)).toFloat(), p)
        p.color = Pal.BLUE
        c.drawText("github.com/iprajax/khayali-pulao", pad.toFloat(), (head + h + k.dp(54)).toFloat(), p)

        val dir = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, "khayali-pulao-${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val uri = Uri.parse("content://$AUTHORITY/${f.name}")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Daydreaming with Khayali Pulao 🍚 — https://iprajax.github.io/khayali-pulao/")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share this daydream").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Minimal read-only provider for the share image (no androidx needed). Serves only files in cache/share. */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val dir = File(context!!.cacheDir, "share")
        val f = File(dir, uri.lastPathSegment ?: "")
        require(f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "Bad path" }
        return f
    }
    override fun getType(uri: Uri) = "image/png"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "read-only" }
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<out String>?) = 0
}
