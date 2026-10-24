package com.alphainternational.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GIFs in texts, free and with no account: bots send Google's animated emoji (Noto Animated Emoji, CC BY 4.0) that fit
 * the moment. Each one is saved in the bot's files and plays with the bot's text. GIFs the owner sends are read like photos.
 */
object Gifs {
    data class Emoji(val code:String,val words:List<String>)
    /** Bots send animated emoji unless the owner turned them off in Settings. */
    fun on(config:JSONObject)=!config.optBoolean("off")

    /** The bundled list (assets/animated-emoji.tsv): code, comma-separated names, category; most popular first. */
    fun catalog(text:String)=text.lineSequence().filter {it.isNotBlank()}.map {line->
        val parts=line.split('\t')
        Emoji(parts[0].trim(),parts.getOrElse(1) {""}.split(',').map {it.trim().lowercase()}.filter {it.isNotBlank()})
    }.toList()

    /** Catalog code for an emoji: 👍 → 1f44d, ❤️ → 2764_fe0f. */
    fun code(emoji:String)=emoji.codePoints().toArray().joinToString("_") {Integer.toHexString(it)}

    /** The first emoji in [text], with its joiners, variation selector and skin tone. */
    fun firstEmoji(text:String):String {
        val points=text.codePoints().toArray()
        fun part(cp:Int)=cp==0x200d || cp==0xfe0f || cp in 0x1f3fb..0x1f3ff
        fun emoji(cp:Int)=cp>=0x2190 && !Character.isLetterOrDigit(cp) && !Character.isWhitespace(cp) && !part(cp)
        val start=points.indexOfFirst {emoji(it)};if(start<0) return ""
        val out=StringBuilder().appendCodePoint(points[start])
        var i=start+1
        while(i<points.size && (part(points[i]) || (points[i-1]==0x200d && emoji(points[i])))) {out.appendCodePoint(points[i]);i++}
        return out.toString()
    }

    /** Picks the animated emoji for [search]: an emoji in it wins, otherwise the best match for its words. */
    fun pick(catalog:List<Emoji>,search:String):Emoji? {
        val byCode=catalog.associateBy {it.code}
        val emoji=firstEmoji(search)
        if(emoji.isNotEmpty()) {
            val full=code(emoji).split('_')
            val plain=full.filter {it!="fe0f" && it.toInt(16) !in 0x1f3fb..0x1f3ff}
            listOf(full,plain,plain+"fe0f",plain.take(1),plain.take(1)+"fe0f").map {it.joinToString("_")}.firstNotNullOfOrNull {byCode[it]}?.let {return it}
        }
        val words=search.lowercase().split(Regex("[^a-z0-9]+")).filter {it.length>1 && it!="gif" && it!="emoji"}
        if(words.isEmpty()) return null
        val phrase=words.joinToString(" ")
        catalog.firstOrNull {phrase in it.words}?.let {return it}
        // Most matching words wins; ties go to the more popular one (the list is in popularity order).
        val best=catalog.maxByOrNull {e->words.count {w->e.words.any {name->w in name.split(' ')}}}?:return null
        return best.takeIf {e->words.any {w->e.words.any {name->w in name.split(' ')}}}
    }

    /** File name for a sent emoji: emoji-party-popper-3fa2.webp */
    fun name(emoji:Emoji)="emoji-"+(emoji.words.firstOrNull()?:"reaction").replace(Regex("[^a-z0-9]+"),"-").trim('-').take(30).ifBlank {"reaction"}+
        "-"+randomUrl(3).filter {it.isLetterOrDigit()}.lowercase().take(4)+".webp"
    /** A bot's animated emoji: shown big and uncropped, like a sticker. */
    fun isEmoji(file:String)=file.startsWith("emoji-") && file.endsWith(".webp")
    /** Files that can move: GIFs, and WebP stickers and emoji. */
    fun plays(file:String)=file.endsWith(".gif",true) || file.endsWith(".webp",true)

    /** Finds the animated emoji for [search] and saves it to [folder]; returns its file name. */
    suspend fun fetch(catalog:List<Emoji>,search:String,folder:File):String=withContext(Dispatchers.IO) {
        require(search.isNotBlank()) {"Say which emoji to send, like 🎉 or 👍."}
        val emoji=pick(catalog,search.take(80))?:error("There's no animated version of that. Put the emoji in your text instead.")
        val connection=URL("https://fonts.gstatic.com/s/e/notoemoji/latest/"+emoji.code+"/512.webp").openConnection() as HttpURLConnection
        connection.connectTimeout=15_000;connection.readTimeout=30_000
        try {
            require(connection.responseCode in 200..299) {"The animated emoji couldn't be downloaded. Put the emoji in your text instead."}
            val bytes=connection.inputStream.use {it.readBytes()}
            require(bytes.size in 100..3_000_000 && String(bytes,0,4)=="RIFF" && String(bytes,8,4)=="WEBP") {"That wasn't an animated emoji."}
            val name=name(emoji);File(folder,name).writeBytes(bytes);name
        } finally {connection.disconnect()}
    }
}
