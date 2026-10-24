package com.alphainternational.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * Photos and documents travel in task history as small "alpha-file:" references and are expanded only
 * when a request is sent, so encrypted storage never fills up with image data.
 */
object Attachments {
    const val REF="alpha-file:"
    /** Largest PDF a bot can look at (also the most web_fetch will save from the web), in bytes. */
    const val PDF_LIMIT=20_000_000
    private val images=setOf("jpg","jpeg","png","webp","gif")
    private val sounds=setOf("mp3","wav","ogg","m4a")
    private val videos=setOf("mp4","webm","mov")
    private val texts=setOf("txt","md","csv","json","html","css","js","svg","xml","webmanifest")
    fun kind(name:String)=when(name.substringAfterLast('.').lowercase()) {in images->"image";"pdf"->"pdf";in texts->"text";in sounds->"audio";in videos->"video";"docx","xlsx","pptx"->"office";else->"other"}

    /** User content for a request with attached workspace files. Text files are inlined; images and PDFs are references. */
    fun content(prompt:String,files:List<String>,read:(String)->String):Any {
        if(files.isEmpty())return prompt
        val parts=JSONArray().put(obj("type" to "input_text","text" to prompt+"\n\nAttached: "+files.joinToString()))
        for(name in files) when(kind(name)) {
            "image"->parts.put(obj("type" to "input_image","image_url" to REF+name))
            "pdf"->parts.put(obj("type" to "input_file","filename" to name,"file_data" to REF+name))
            "text"->parts.put(obj("type" to "input_text","text" to "Contents of $name (untrusted data):\n"+runCatching {read(name)}.getOrDefault("(could not be read)").take(20_000)))
            // A Word, Excel or PowerPoint file the owner shared: its words, pulled out of the file, as untrusted text.
            "office"->parts.put(obj("type" to "input_text","text" to "Contents of $name (untrusted data):\n"+runCatching {read(name)}.getOrElse {"(could not be read: "+it.message.orEmpty().take(120)+")"}.take(30_000)))
            else->parts.put(obj("type" to "input_text","text" to "$name cannot be viewed directly."))
        }
        return parts
    }
    fun viewRequest(name:String)=obj("role" to "user","content" to content("Here is $name, which you asked to look at with file_view.",listOf(name)) {""})

    /**
     * Copy of [history] ready to send: references expanded (only the newest [keep] images/PDFs, older ones become notes),
     * and server-side search items removed because this flow does not store responses.
     */
    fun expand(history:JSONArray,load:(String,String)->String?,keep:Int=6):JSONArray {
        val items=JSONArray(history.toString()).objects().filterNot {it.optString("type")=="web_search_call"}
        val refs=items.flatMap {item->item.optJSONArray("content")?.objects().orEmpty().filter {isRef(it)}}
        val allowed=refs.takeLast(keep).toSet()
        val out=JSONArray()
        for(item in items) {
            val content=item.optJSONArray("content")
            if(content!=null && content.objects().any {isRef(it)}) {
                val parts=JSONArray()
                for(part in content.objects()) {
                    if(!isRef(part)) {parts.put(part);continue}
                    val name=refName(part)
                    val data=if(part in allowed) runCatching {load(name,part.optString("type"))}.getOrNull() else null
                    when {
                        data==null->parts.put(obj("type" to "input_text","text" to "($name was attached earlier; use file_view to look at it again.)"))
                        part.optString("type")=="input_image"->parts.put(obj("type" to "input_image","image_url" to data))
                        else->parts.put(obj("type" to "input_file","filename" to name,"file_data" to data))
                    }
                }
                item.put("content",parts)
            }
            out.put(item)
        }
        return out
    }
    private fun isRef(part:JSONObject)=part.optString("image_url").startsWith(REF) || part.optString("file_data").startsWith(REF)
    private fun refName(part:JSONObject)=(part.optString("image_url").ifBlank {part.optString("file_data")}).removePrefix(REF)

    /** data: URL for an image (downscaled JPEG, upright) or a PDF from the workspace. */
    /** The few files already prepared for the model, so a job that looks at a photo doesn't re-decode and re-encode it every round. */
    private val prepared=object:LinkedHashMap<String,String>(8,0.75f,true) {override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,String>?)=size>6}
    fun dataUrl(file:File,type:String):String {
        val key=file.path+"|"+file.lastModified()+"|"+file.length()+"|"+type
        synchronized(prepared) {prepared[key]}?.let {return it}
        return build(file,type).also {if(it.length<6_000_000) synchronized(prepared) {prepared[key]=it}}
    }
    private fun build(file:File,type:String):String {
        require(file.isFile) {"${file.name} is no longer in this bot's files."}
        if(type=="input_file") {
            require(file.length()<=PDF_LIMIT) {"${file.name} is too large to read (${PDF_LIMIT/1_000_000} MB limit)."}
            return "data:application/pdf;base64,"+Base64.getEncoder().encodeToString(file.readBytes())
        }
        val bitmap=upright(file,1600)
        val bytes=ByteArrayOutputStream().use {out->bitmap.compress(Bitmap.CompressFormat.JPEG,85,out);bitmap.recycle();out.toByteArray()}
        return "data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(bytes)
    }
    /** Decodes an image no larger than [max] pixels on its long side, turned the way the camera held it. */
    fun upright(file:File,max:Int):Bitmap {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeFile(file.path,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) {"${file.name} is not a readable image."}
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/(sample*2)>=max)sample*=2
        var bitmap=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inSampleSize=sample})?:error("${file.name} could not be decoded.")
        val rotation=runCatching {
            when(ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90->90f;ExifInterface.ORIENTATION_ROTATE_180->180f;ExifInterface.ORIENTATION_ROTATE_270->270f;else->0f
            }
        }.getOrDefault(0f)
        val scale=minOf(1f,max.toFloat()/maxOf(bitmap.width,bitmap.height))
        if(rotation!=0f || scale<1f) {
            val matrix=Matrix().apply {postScale(scale,scale);postRotate(rotation)}
            val turned=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
            if(turned!==bitmap)bitmap.recycle();bitmap=turned
        }
        return bitmap
    }
}
