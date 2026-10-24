package com.alphainternational.assistant

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Something a bot made for the owner: a website, an app, a game, a document, a chart, a video or a sound. */
class MadeThing(val bot:String,val name:String,val kind:String,val modified:Long,val title:String="")

object Library {
    /** A page's own title (from its first few thousand characters), or "" when it has none worth showing. */
    fun pageTitle(file:File):String = runCatching {
        val head=file.inputStream().use {s->val buffer=ByteArray(6_000);val n=s.read(buffer);if(n<=0) "" else String(buffer,0,n,Charsets.UTF_8)}
        Regex("(?is)<title[^>]*>(.*?)</title>").find(head)?.groupValues?.get(1)?.replace(Regex("\\s+")," ")?.trim().orEmpty()
            .replace("&amp;","&").replace("&#39;","'").replace("&quot;","\"").take(80)
    }.getOrDefault("").takeIf {it.length>=3 && !it.equals("index",true) && !it.equals("document",true)}.orEmpty()

    /** What counts as "made": pages, documents, charts and drawings, videos and sounds. The owner's own photos and the pictures a bot found are not. */
    fun kindOf(name:String):String? {
        val lower=name.lowercase()
        return when {
            lower.endsWith(".html") && !lower.startsWith("_") -> "page"
            lower.endsWith(".pdf") && !lower.startsWith("fetched-") -> "document"
            Office.isOffice(lower) -> Office.kindOf(lower)
            Attachments.kind(lower)=="video" -> "video"
            Attachments.kind(lower)=="audio" -> "sound"
            Attachments.kind(lower)=="image" && (lower.startsWith("chart-") || lower.startsWith("draw") || lower.startsWith("picture-") || lower.startsWith("art-")) -> "picture"
            lower.endsWith(".csv") -> "sheet"
            else -> null
        }
    }
    fun label(kind:String)=when(kind) {"page"->"Website or app";"document"->"Document";"word","excel","slides"->Office.label(kind);"video"->"Video";"sound"->"Sound";"picture"->"Picture";"photo"->"Photo";"note"->"Note";"file"->"File";else->"Spreadsheet"}
    fun icon(kind:String):ImageVector=when(kind) {"page"->Icons.Outlined.Language;"document"->Icons.Outlined.PictureAsPdf;"word"->Icons.Outlined.Description;"slides"->Icons.Outlined.Slideshow;"excel"->Icons.Outlined.TableChart;"video"->Icons.Outlined.Movie;"sound"->Icons.Outlined.GraphicEq;
        "picture","photo"->Icons.Outlined.Image;"note"->Icons.Outlined.Description;"file"->Icons.Outlined.InsertDriveFile;else->Icons.Outlined.TableChart}

    /**
     * What the rest of a bot's folder is, so the Library can be the one place for every file: a photo, a note or a plain file. Null for the bot's own working files
     * (page pictures it took for itself, half-finished uploads, anything hidden or starting with an underscore).
     */
    fun otherKind(name:String):String? {
        val lower=name.lowercase()
        if(lower.startsWith(".") || lower.startsWith("_") || lower.startsWith("shot-") || lower.startsWith("sheet-")) return null
        return when {
            Attachments.kind(lower)=="image" -> "photo"
            lower.substringAfterLast('.',"") in setOf("txt","md","json","xml","css","js","svg","webmanifest") -> "note"
            else -> "file"
        }
    }

    private val gate=Mutex()
    private val memory=android.util.LruCache<String,ImageBitmap>(64)
    private fun cache(context:Context)=File(context.filesDir,"thumbs").also {it.mkdirs()}

    /**
     * A small picture of a made thing: charts and drawings as themselves, a website or app as it looks on a phone. Null for files with nothing to show
     * (a sound, a spreadsheet) or when the page wouldn't draw. Call off the main thread; pages are photographed one at a time and remembered.
     */
    suspend fun thumb(context:Context,file:File,bot:String):ImageBitmap? = withContext(Dispatchers.IO) {
        if(!file.isFile) return@withContext null
        val key=bot.lowercase()+"-"+file.name+"-"+file.lastModified()
        memory.get(key)?.let {return@withContext it}
        val kind=kindOf(file.name)?:if(Attachments.kind(file.name)=="image") "picture" else return@withContext null
        val made:ImageBitmap? = when(kind) {
            "picture"->runCatching {Attachments.upright(file,420).asImageBitmap()}.getOrNull()
            "page"-> {
                val saved=File(cache(context),Integer.toHexString(key.hashCode()).padStart(8,'0')+".jpg")
                if(saved.isFile) runCatching {android.graphics.BitmapFactory.decodeFile(saved.path)?.asImageBitmap()}.getOrNull()
                else gate.withLock {
                    // Leaving the Library cancels this job; that must stop here, not fall through to the next way of drawing the page.
                    val result=try {PageShot.take(context.applicationContext,file.parentFile!!,file.name,"phone",1,null)}
                        catch(e:kotlinx.coroutines.CancellationException) {throw e} catch(e:Exception) {null}
                    val thumb=result?.thumb
                    result?.picture?.recycle()
                    if(thumb==null) null else {
                        runCatching {saved.outputStream().use {thumb.compress(Bitmap.CompressFormat.JPEG,78,it)}}
                        thumb.asImageBitmap()
                    }
                }
            }
            else->null
        }
        if(made!=null) memory.put(key,made)
        made
    }

    /** Keeps the saved page pictures from piling up: the oldest go first. */
    fun tidy(context:Context) {
        val files=cache(context).listFiles()?.sortedBy {it.lastModified()}?:return
        files.dropLast(120).forEach {it.delete()}
    }
}

/** A made thing's picture: the website as it looks, the chart itself, or a quiet icon while it loads or when there is nothing to show. */
@Composable fun ThingPicture(c:Controller,bot:String,name:String,modifier:Modifier) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val kind=Library.kindOf(name)?:Library.otherKind(name)?:"picture"
    val file=remember(bot,name) {c.libraryFile(bot,name)}
    val picture by produceState<ImageBitmap?>(null,bot,name,file.lastModified()) {value=Library.thumb(context,file,bot)}
    Box(modifier.background(Raised2),contentAlignment=Alignment.Center) {
        val image=picture
        if(image!=null) Image(image,name,contentScale=ContentScale.Crop,alignment=Alignment.TopCenter,modifier=Modifier.fillMaxSize())
        else Icon(Library.icon(kind),null,tint=Muted,modifier=Modifier.size(34.dp))
    }
}

/**
 * Every file in every bot's folder, newest first: what the bots made (pages, documents, charts, videos, sounds, spreadsheets) and the rest
 * (photos, notes, other files). This is the one list for "everything made", so Computer no longer keeps a second one.
 */
fun Controller.libraryAll():List<MadeThing> = bots.flatMap {bot->
    java.io.File(workspaceDir,bot.name.lowercase()).listFiles().orEmpty().filter {it.isFile}.mapNotNull {f->
        val kind=Library.kindOf(f.name)?:Library.otherKind(f.name)?:return@mapNotNull null
        MadeThing(bot.name,f.name,kind,f.lastModified(),if(kind=="page") Library.pageTitle(f) else "")
    }
}.sortedByDescending {it.modified}

/**
 * Copies a file into the phone's Downloads folder (Downloads/Alpha), so it shows in the Files app and other apps can pick it up.
 * Android 10 and newer let an app add to Downloads without any permission. Returns the saved name, or null when it didn't work.
 */
suspend fun saveToDownloads(context:Context,file:File):String? = withContext(Dispatchers.IO) {
    runCatching {
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,file.name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE,SitePage.mime(file.name))
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,android.os.Environment.DIRECTORY_DOWNLOADS+"/Alpha")
            put(android.provider.MediaStore.Downloads.IS_PENDING,1)
        }
        val resolver=context.contentResolver
        val target=resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("no place to save")
        try {
            resolver.openOutputStream(target)?.use {out->file.inputStream().use {it.copyTo(out)}}?:error("could not write")
            resolver.update(target,android.content.ContentValues().apply {put(android.provider.MediaStore.Downloads.IS_PENDING,0)},null,null)
        } catch(e:Exception) {runCatching {resolver.delete(target,null,null)};throw e}
        file.name
    }.getOrNull()
}

/**
 * Everything your bots made or kept, newest first, as cards with a picture of each. Tap a card to open it; each one also has Share and Save to phone.
 * [activity] gives Share and Save; without it (a preview) the cards only open. [newFile] starts a new note.
 */
@Composable fun LibraryScreen(c:Controller,open:(String,String)->Unit,activity:MainActivity?=null,newFile:()->Unit={}) {
    val things=remember(c.tasks.size,c.files,c.workspace,c.version) {c.libraryAll()}
    var filter by remember {mutableStateOf("")}
    val scope=rememberCoroutineScope()
    val context=androidx.compose.ui.platform.LocalContext.current
    val kinds=things.map {it.kind}.distinct()
    val shown=things.filter {filter.isBlank() || it.kind==filter}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=6.dp,bottom=30.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("Everything your bots made or kept, newest first.",style=Type.small,modifier=Modifier.padding(bottom=6.dp))
            if(activity!=null) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf<Pair<String,()->Unit>>("New note" to newFile,"Add a photo" to {activity.pickPhoto()},"Import a document" to {activity.pickDocument()}).forEach {(label,action)->
                    Text(label,style=Type.chip,modifier=Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(999.dp)).border(1.dp,Outline,RoundedCornerShape(999.dp))
                        .clickable(onClick=action).padding(horizontal=14.dp,vertical=8.dp))
                }
            }
            if(kinds.size>1) Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("" to "All").plus(kinds.map {it to Library.label(it)}).forEach {(kind,label)->
                    val on=filter==kind
                    Text(label,style=Type.sub.copy(fontSize=13.sp,color=if(on) OnAccent else Ink),
                        modifier=Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(999.dp)).background(if(on) Accent else Raised2).clickable {filter=kind}.padding(horizontal=14.dp,vertical=7.dp))
                }
            }
        }
        if(shown.isEmpty()) item {
            Text("Nothing here yet. Ask a bot to make a website, a document, a chart or a video and it will show up here.",style=Type.body.copy(color=Muted),modifier=Modifier.padding(vertical=30.dp))
        }
        items(shown,key={it.bot+"/"+it.name}) {thing->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).border(1.dp,Line,RoundedCornerShape(16.dp))) {
                Row(Modifier.fillMaxWidth().clickable {open(thing.bot,thing.name)}.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                    ThingPicture(c,thing.bot,thing.name,Modifier.size(92.dp,116.dp).clip(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(thing.title.ifBlank {thing.name.substringBeforeLast('.').replace('-',' ').replace('_',' ').replaceFirstChar {it.uppercase()}},style=Type.sub,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(Library.label(thing.kind)+" · by "+thing.bot,style=Type.small)
                        Text(stampOf(thing.modified),style=Type.small.copy(fontSize=12.sp))
                    }
                    Icon(Icons.Outlined.ChevronRight,null,tint=Muted)
                }
                // A sound has nothing to open, so it plays right here.
                if(thing.kind=="sound") Box(Modifier.padding(horizontal=10.dp,vertical=4.dp)) {AudioChip(c.libraryFile(thing.bot,thing.name),thing.name)}
                if(activity!=null) Row(Modifier.fillMaxWidth().padding(start=6.dp,end=6.dp,bottom=4.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                    LibraryAction(Icons.AutoMirrored.Outlined.OpenInNew,"Open") {open(thing.bot,thing.name)}
                    LibraryAction(Icons.Outlined.Share,"Share") {runCatching {activity.share(c.libraryFile(thing.bot,thing.name))}.onFailure {c.showNotice(plainError(it))}}
                    LibraryAction(Icons.Outlined.Download,"Save to phone") {
                        scope.launch {
                            val saved=saveToDownloads(context,c.libraryFile(thing.bot,thing.name))
                            c.showNotice(if(saved!=null) "Saved to your phone: Downloads, Alpha, "+saved else "Couldn't save that. Check that the phone has room, then try again.")
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun LibraryAction(icon:ImageVector,label:String,onClick:()->Unit) {
    TextButton(onClick=onClick,contentPadding=PaddingValues(horizontal=10.dp)) {
        Icon(icon,null,tint=Muted,modifier=Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text(label,style=Type.small.copy(color=Ink,fontSize=13.sp))
    }
}

private fun stampOf(time:Long):String {
    val moment=Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    return if(moment.toLocalDate()==LocalDate.now()) "Today "+DateTimeFormatter.ofPattern("h:mm a").format(moment) else DateTimeFormatter.ofPattern("MMM d, h:mm a").format(moment)
}
