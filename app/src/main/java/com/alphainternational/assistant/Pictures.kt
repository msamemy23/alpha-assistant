package com.alphainternational.assistant

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Pictures in answers. A bot downloads a public photo (a product page's main picture, a Wikimedia Commons photo) into its own
 * files and shows it in the chat with ![what it shows](photo-ab12cd.jpg). Nothing is fetched while the chat is drawn,
 * and nothing is saved that isn't a real image.
 */
object Pictures {
    private const val AGENT="Mozilla/5.0 (Linux; Android 16; SM-S921U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
    private const val MAX_BYTES=12_000_000
    private const val MAX_SIDE=1600

    /** A picture reference on its own line of a bot's text: ![alt](photo-ab12cd.jpg). Only files in the bot's own folder count. */
    private val reference=Regex("!\\[([^\\]\\n]{0,160})]\\(([A-Za-z0-9][A-Za-z0-9._ -]{0,99}\\.(?:jpg|jpeg|png|webp|gif))\\)",RegexOption.IGNORE_CASE)
    fun references(text:String):List<Pair<String,String>> = reference.findAll(text).map {it.groupValues[1] to it.groupValues[2]}.toList()
    /** The picture on a line of its own, or null for any other line. */
    fun alone(line:String):Pair<String,String>? = reference.matchEntire(line.trim())?.let {it.groupValues[1] to it.groupValues[2]}
    /** Names of the pictures a text already shows inline, so they aren't shown a second time under it. */
    fun shown(text:String):Set<String> = references(text).map {it.second}.toSet()

    /** Picture addresses on a web page, best first: the page's own share picture, then large-looking <img> tags. At most [limit]. */
    fun imagesIn(html:String,base:String,limit:Int=8):List<String> {
        val found=LinkedHashMap<String,Int>()
        fun add(raw:String,rank:Int) {
            val value=raw.trim().replace("&amp;","&")
            if(value.isBlank() || value.startsWith("data:") || value.startsWith("blob:")) return
            val absolute=runCatching {URI(base).resolve(value).toString()}.getOrNull()?:return
            if(!absolute.startsWith("https://") && !absolute.startsWith("http://")) return
            val lower=absolute.lowercase().substringBefore('?')
            if(lower.endsWith(".svg") || lower.endsWith(".ico") || skip.any {lower.contains(it)}) return
            found.putIfAbsent(HttpsFirst.upgrade(absolute),rank)
        }
        Regex("(?is)<meta\\s[^>]*?(?:property|name)\\s*=\\s*[\"'](?:og:image(?::secure_url|:url)?|twitter:image(?::src)?)[\"'][^>]*>").findAll(html).forEach {m->
            Regex("(?is)content\\s*=\\s*[\"']([^\"']+)[\"']").find(m.value)?.let {add(it.groupValues[1],0)}
        }
        Regex("(?is)<meta\\s[^>]*?content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?(?:property|name)\\s*=\\s*[\"'](?:og:image|twitter:image)[\"'][^>]*>").findAll(html).forEach {add(it.groupValues[1],0)}
        Regex("(?is)<link\\s[^>]*rel\\s*=\\s*[\"']image_src[\"'][^>]*href\\s*=\\s*[\"']([^\"']+)[\"']").findAll(html).forEach {add(it.groupValues[1],1)}
        Regex("(?is)\"image\"\\s*:\\s*\\[?\\s*\"(https?:[^\"]+)\"").findAll(html).forEach {add(it.groupValues[1].replace("\\/","/"),1)}
        Regex("(?is)<img\\s[^>]*>").findAll(html).forEach {tag->
            val t=tag.value
            val width=Regex("(?is)\\bwidth\\s*=\\s*[\"']?(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()
            if(width!=null && width<120) return@forEach
            val source=Regex("(?is)\\b(?:data-src|data-original|data-lazy-src|src)\\s*=\\s*[\"']([^\"']+)[\"']").find(t)?.groupValues?.get(1)
                ?:Regex("(?is)\\bsrcset\\s*=\\s*[\"']([^\"']+)[\"']").find(t)?.groupValues?.get(1)?.split(',')?.lastOrNull()?.trim()?.substringBefore(' ')
            source?.let {add(it,2)}
        }
        return found.entries.sortedBy {it.value}.map {it.key}.take(limit)
    }
    private val skip=listOf("pixel","spacer","tracking","sprite","1x1","blank.","/icons/","icon-","avatar","placeholder","loading.","badge","favicon","/ads/","doubleclick","facebook.com/tr")

    /** Downloads the public image at [url] into [folder] and returns its file name. JPEGs stay JPEGs, PNGs stay PNGs; anything else becomes a JPEG. */
    suspend fun fetch(url:String,folder:File):String=withContext(Dispatchers.IO) {
        var address=HttpsFirst.upgrade(url.trim());var fromHttp=HttpsFirst.isHttp(url.trim())
        require(address.startsWith("https://")) {"Use the picture's full https address."}
        repeat(5) {
            WebReader.publicHost(URI(address).host)
            val connection=URL(address).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects=false;connection.connectTimeout=20_000;connection.readTimeout=40_000
            connection.setRequestProperty("User-Agent",AGENT);connection.setRequestProperty("Accept","image/jpeg,image/png,image/webp,image/*;q=0.8")
            try {
                val code=try {connection.responseCode} catch(e:java.io.IOException) {
                    if(!fromHttp) throw e
                    error(HttpsFirst.refusal(address,if(e is javax.net.ssl.SSLException) HttpsFirst.SECURITY else HttpsFirst.NO_ANSWER))
                }
                if(code in 300..399) {
                    val next=URI(address).resolve(connection.getHeaderField("Location")?:error("The picture's address redirected without a destination.")).toString()
                    val secure=HttpsFirst.upgrade(next)
                    if(secure==address) error(HttpsFirst.refusal(next,HttpsFirst.BOUNCED))
                    fromHttp=HttpsFirst.isHttp(next);address=secure;return@repeat
                }
                if(code !in 200..299) throw ApiFailure(code,"The site returned HTTP $code for that picture. It may not allow downloads; try another picture.")
                val type=connection.contentType.orEmpty().lowercase().substringBefore(';').trim()
                require(type.startsWith("image/") && !type.contains("svg")) {"That address is not a photo ($type). Use a picture address from the page's pictures list."}
                val bytes=ByteArrayOutputStream()
                connection.inputStream.use {input->
                    val buffer=ByteArray(16_384)
                    while(true) {
                        val n=input.read(buffer);if(n<0) break
                        require(bytes.size()+n<=MAX_BYTES) {"That picture is too large (over 12 MB). Try a smaller one."}
                        bytes.write(buffer,0,n)
                    }
                }
                return@withContext save(address,bytes.toByteArray(),type,folder)
            } finally {connection.disconnect()}
        }
        error("The picture's address redirected too many times.")
    }

    private fun save(address:String,bytes:ByteArray,type:String,folder:File):String {
        require(bytes.size>=200) {"That picture came back empty."}
        folder.mkdirs()
        val temp=File(folder,".incoming-"+System.nanoTime())
        try {
            temp.writeBytes(bytes)
            // A real, readable picture: decoded, turned upright, and no bigger than a phone screen needs.
            val bitmap=Attachments.upright(temp,MAX_SIDE)
            val png=type.contains("png")
            val name="photo-"+digest(address)+if(png) ".png" else ".jpg"
            File(folder,name).outputStream().use {bitmap.compress(if(png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,88,it)}
            bitmap.recycle()
            return name
        } finally {temp.delete()}
    }
    private fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(4).joinToString("") {"%02x".format(it)}

    /** A free-to-use stock photo for a website: where to download it, how to describe it, and who to credit when the licence asks for that. */
    data class Stock(val url:String,val alt:String,val credit:String,val width:Int,val height:Int,val source:String) {
        val orientation get()=when {width<=0 || height<=0->"";width.toDouble()/height>1.15->"landscape";height.toDouble()/width>1.15->"portrait";else->"square"}
        fun json()=obj("url" to url,"alt" to alt,"credit" to credit,"width" to width,"height" to height,"source" to source,"orientation" to orientation)
    }

    /**
     * Free stock photos for ANY subject, no account and no key, from Openverse: StockSnap first (public-domain CC0 stock photography, no credit needed),
     * then other photos whose licence allows business use (credit them). [orientation] is landscape, portrait, square or blank for any.
     */
    suspend fun stock(query:String,count:Int=6,orientation:String=""):List<Stock> {
        require(query.isNotBlank()) {"Say what the photo should show, like \"bakery bread display\"."}
        suspend fun ask(extra:String):List<Stock> {
            val aspect=when(orientation.lowercase()) {"landscape"->"&aspect_ratio=wide";"portrait"->"&aspect_ratio=tall";"square"->"&aspect_ratio=square";else->""}
            val url="https://api.openverse.org/v1/images/?q="+URLEncoder.encode(query.take(100),Charsets.UTF_8.name())+"&page_size=20&mature=false&license_type=commercial"+aspect+extra
            return parseStock(Http.json(url,headers=mapOf("User-Agent" to AGENT)))
        }
        val first=runCatching {ask("&source=stocksnap")}.getOrDefault(emptyList())
        val rest=if(first.size>=count) emptyList() else runCatching {ask("&license=cc0,pdm,by")}.getOrDefault(emptyList())
        return (first+rest).distinctBy {it.url}.take(count.coerceIn(1,12))
    }

    /** Photos from an Openverse reply: real pictures at least 800 px wide, not marked mature, with the credit the licence needs. */
    fun parseStock(reply:JSONObject):List<Stock> {
        val rows=reply.optJSONArray("results")?:return emptyList()
        return (0 until rows.length()).mapNotNull {i->
            val r=rows.optJSONObject(i)?:return@mapNotNull null
            val url=r.optString("url").takeIf {it.startsWith("https://")}?:return@mapNotNull null
            if(r.optBoolean("mature")) return@mapNotNull null
            val width=r.optInt("width");val height=r.optInt("height")
            if(width in 1..799) return@mapNotNull null
            val lower=url.lowercase().substringBefore('?')
            if(!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp"))) return@mapNotNull null
            val license=r.optString("license").lowercase()
            val credit=if(license=="cc0" || license=="pdm") "" else (r.optString("creator").ifBlank {"the photographer"}+" ("+r.optString("source").ifBlank {r.optString("provider")}+", CC "+license.uppercase()+")").take(140)
            val title=r.optString("title").replace(Regex("[#_]+")," ").replace(Regex("\\s+")," ").trim().take(110)
            val tags=r.optJSONArray("tags")?.objects()?.mapNotNull {it.optString("name").takeIf {n->n.isNotBlank()}}?.take(4)?.joinToString(", ").orEmpty()
            Stock(url,title.ifBlank {tags}.ifBlank {"Photo"},credit,width,height,r.optString("source").ifBlank {r.optString("provider")})
        }
    }

    /**
     * A web-ready copy of a picture in [folder] (the owner's 12-megapixel phone photos are far too heavy for a page): turned upright, no larger than
     * [maxSide] pixels on its longest side, saved as web-<name>.jpg. Returns the new name and its size.
     */
    suspend fun prepare(folder:File,name:String,maxSide:Int=1400,quality:Int=82):Pair<String,Long> = withContext(Dispatchers.IO) {
        val source=File(folder,Rules.fileName(name));require(source.isFile) {"$name isn't in this bot's files."}
        val bitmap=Attachments.upright(source,maxSide.coerceIn(400,2400))
        val out="web-"+name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9-]+"),"-").trim('-').take(40).ifBlank {"photo"}+".jpg"
        val file=File(folder,out)
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,quality.coerceIn(50,95),it)}
        bitmap.recycle()
        out to file.length()
    }

    /** Free photos from Wikimedia Commons (no account, no key). Each result has a picture address to pass to image_show, and who to credit. */
    suspend fun search(query:String):JSONObject {
        require(query.isNotBlank()) {"Say what to look for, like \"Toyota Camry 2018\"."}
        val url="https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=10&gsrsearch="+
            URLEncoder.encode(query.take(120)+" filetype:bitmap",Charsets.UTF_8.name())+"&prop=imageinfo&iiprop=url|mime|size|extmetadata&iiurlwidth=1000"
        val reply=Http.json(url,headers=mapOf("User-Agent" to "AlphaAssistant/1.0 (an auto shop's assistant app; contact via the shop)"))
        return results(reply)
    }
    fun results(reply:JSONObject):JSONObject {
        val pages=reply.optJSONObject("query")?.optJSONObject("pages")
        val rows=pages?.keys()?.asSequence()?.mapNotNull {pages.optJSONObject(it)}?.sortedBy {it.optInt("index",99)}?.mapNotNull {page->
            val info=page.optJSONArray("imageinfo")?.optJSONObject(0)?:return@mapNotNull null
            val mime=info.optString("mime")
            if(mime!="image/jpeg" && mime!="image/png") return@mapNotNull null
            val meta=info.optJSONObject("extmetadata")
            fun field(key:String)=meta?.optJSONObject(key)?.optString("value").orEmpty().replace(Regex("<[^>]+>"),"").trim().take(80)
            val address=info.optString("thumburl").ifBlank {info.optString("url")}
            if(address.isBlank()) return@mapNotNull null
            obj("title" to page.optString("title").removePrefix("File:").take(100),"picture" to address,"page" to info.optString("descriptionurl"),
                "credit" to field("Artist"),"license" to field("LicenseShortName"))
        }?.take(8)?.toList().orEmpty()
        return obj("photos" to JSONArray(rows),"note" to if(rows.isEmpty()) "No free photos matched. Try other words, or read a product page with web_fetch and use one of its pictures."
            else "Show one with image_show (picture is its address). Free photos: say who took it when the license asks for credit.")
    }
}
