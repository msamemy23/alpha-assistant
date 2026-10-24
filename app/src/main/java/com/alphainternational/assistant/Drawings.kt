package com.alphainternational.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.caverock.androidsvg.SVG
import java.io.File
import java.security.MessageDigest

/**
 * Drawings and charts as pictures in the chat. A bot writes a simple SVG (a logo, a diagram, a floor plan, a sign) or gives
 * chart numbers; Alpha draws it on this phone into a PNG in the bot's files. No account, no internet, nothing leaves the phone.
 */
object Drawings {
    const val MAX_SVG=60_000
    private val refused=listOf("<script","<foreignobject","<!entity","<!doctype","<image","javascript:","onload=","onclick=")

    /** Why [svg] can't be drawn, or null when it can. Scripts, external pictures and entity tricks are never drawn. */
    fun problem(svg:String):String? {
        val text=svg.trim();val lower=text.lowercase()
        return when {
            text.isBlank() || !lower.contains("<svg") -> "Send a complete <svg ...>...</svg> drawing."
            text.length>MAX_SVG -> "That drawing is too big. Keep it under $MAX_SVG characters and simplify it."
            refused.any {lower.contains(it)} -> "Use only shapes, paths and text in a drawing: no scripts, no embedded pictures. Draw the picture with shapes instead."
            else -> null
        }
    }

    /** "Shop Logo (v2)" → "shop-logo-v2" */
    fun slug(value:String):String=value.lowercase().replace(Regex("\\.(png|svg)$"),"").replace(Regex("[^a-z0-9]+"),"-").trim('-').take(40).ifBlank {"drawing"}
    /** A new file name for the drawing, like drawing-shop-logo-3fa2.png; a different drawing never overwrites an earlier one. */
    fun fileName(name:String,svg:String)="drawing-"+slug(name)+"-"+MessageDigest.getInstance("SHA-256").digest(svg.toByteArray()).take(2).joinToString("") {"%02x".format(it)}+".png"

    /** Draws [svgText] on a white card up to [longSide] pixels across, keeping its shape. Throws a plain-words error when the drawing is broken. */
    fun render(svgText:String,longSide:Int=1400):Bitmap {
        problem(svgText)?.let {error(it)}
        val svg=try {SVG.getFromString(svgText.trim())} catch(e:Exception) {error("That drawing isn't valid SVG: "+(e.message?.take(120)?:"couldn't read it")+". Fix it and try again.")}
        val box=svg.documentViewBox
        val declaredW=svg.documentWidth.takeIf {it>0};val declaredH=svg.documentHeight.takeIf {it>0}
        val sourceW=box?.width()?.takeIf {it>0}?:declaredW?:800f
        val sourceH=box?.height()?.takeIf {it>0}?:declaredH?:600f
        // Without a viewBox, the stated width and height become one, so the drawing scales to the card.
        if(box==null) svg.setDocumentViewBox(0f,0f,sourceW,sourceH)
        svg.setDocumentWidth("100%");svg.setDocumentHeight("100%")
        val ratio=(sourceW/sourceH).coerceIn(0.2f,5f)
        val width=if(ratio>=1f) longSide else (longSide*ratio).toInt().coerceAtLeast(120)
        val height=if(ratio>=1f) (longSide/ratio).toInt().coerceAtLeast(120) else longSide
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {drawColor(Color.WHITE);svg.renderToCanvas(this,RectF(0f,0f,width.toFloat(),height.toFloat()))}
        return bitmap
    }

    /**
     * Which version of the chart maker drew a chart picture. Raise it whenever charts get better: a picture from an older version is never handed back
     * to the owner (files_show refuses it and the bot draws it again), so an old, poor chart can't come back as the answer to "get me the chart".
     */
    const val CHART_ENGINE=2
    private const val CHART_KEY="AlphaChart"
    private val png=byteArrayOf(0x89.toByte(),0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A)
    private fun int(bytes:ByteArray,at:Int)=((bytes[at].toInt() and 255) shl 24) or ((bytes[at+1].toInt() and 255) shl 16) or ((bytes[at+2].toInt() and 255) shl 8) or (bytes[at+3].toInt() and 255)

    /** [picture] (a PNG) with a text note after its header saying which chart maker [version] drew it; the picture itself is untouched. */
    fun stamped(picture:ByteArray,version:Int=CHART_ENGINE):ByteArray {
        if(picture.size<33 || !picture.copyOfRange(0,8).contentEquals(png)) return picture
        val at=8+12+int(picture,8)
        if(at<0 || at>picture.size) return picture
        val type="tEXt".toByteArray(Charsets.ISO_8859_1);val data=(CHART_KEY+"\u0000"+version).toByteArray(Charsets.ISO_8859_1)
        val crc=java.util.zip.CRC32().apply {update(type);update(data)}.value.toInt()
        val chunk=java.nio.ByteBuffer.allocate(12+data.size).putInt(data.size).put(type).put(data).putInt(crc).array()
        return picture.copyOfRange(0,at)+chunk+picture.copyOfRange(at,picture.size)
    }

    /** The chart-maker version noted in the first bytes of a PNG, or 0 when it has none (a chart drawn before charts were stamped). */
    fun chartEngine(head:ByteArray):Int {
        var at=8
        while(at+12<=head.size) {
            val length=int(head,at)
            if(length<0) return 0
            val kind=String(head,at+4,4,Charsets.ISO_8859_1)
            if(kind=="IDAT") return 0
            if(kind=="tEXt" && at+8+length<=head.size) {
                val note=String(head,at+8,length,Charsets.ISO_8859_1)
                if(note.startsWith(CHART_KEY+"\u0000")) return note.substringAfter('\u0000').toIntOrNull()?:0
            }
            at+=12+length
        }
        return 0
    }
    fun chartEngine(file:File):Int=runCatching {file.inputStream().use {input->val head=ByteArray(256);chartEngine(head.copyOf(maxOf(input.read(head),0)))}}.getOrDefault(0)
    /** A chart picture a bot made earlier that an older chart maker drew (by its name and what is inside it). */
    fun isOldChart(file:File)=file.name.startsWith("chart-") && file.name.endsWith(".png") && chartEngine(file)<CHART_ENGINE

    /** Saves a chart [bitmap] like [save], stamped with the current chart maker. */
    fun saveChart(bitmap:Bitmap,folder:File,name:String):String {
        folder.mkdirs()
        val bytes=java.io.ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        File(folder,name).writeBytes(stamped(bytes))
        bitmap.recycle()
        require(File(folder,name).length()>200 && chartEngine(File(folder,name))==CHART_ENGINE) {"The picture could not be saved."}
        return name
    }

    /** Saves [bitmap] as a PNG in [folder] under [name]; returns the file name. */
    fun save(bitmap:Bitmap,folder:File,name:String):String {
        folder.mkdirs()
        File(folder,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
        require(File(folder,name).length()>200) {"The picture could not be saved."}
        return name
    }
}
