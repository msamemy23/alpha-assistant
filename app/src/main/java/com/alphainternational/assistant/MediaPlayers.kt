package com.alphainternational.assistant

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Saving a video or sound the bots made to the phone's gallery or music folder, so it's in Photos and the Music app too. */
object MediaSaver {
    /** Saves [file] to Movies/Alpha, Music/Alpha or Pictures/Alpha and returns a plain-words result. */
    fun save(context:Context,file:File):String {
        val kind=Attachments.kind(file.name)
        val (collection,folder)=when(kind) {
            "video"->MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies/Alpha"
            "audio"->MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Music/Alpha"
            "image"->MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/Alpha"
            else->return "Only videos, sounds and pictures can be saved to the gallery."
        }
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,file.name);put(MediaStore.MediaColumns.MIME_TYPE,SitePage.mime(file.name))
            put(MediaStore.MediaColumns.RELATIVE_PATH,folder);put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        val resolver=context.contentResolver
        val uri=resolver.insert(collection,values)?:return "Android wouldn't let Alpha save it."
        return try {
            resolver.openOutputStream(uri)?.use {out->file.inputStream().use {it.copyTo(out)}}?:error("no stream")
            resolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            "Saved to "+folder.replace("/"," › ")+" on your phone."
        } catch(e:Exception) {runCatching {resolver.delete(uri,null,null)};"Couldn't save it: "+(e.message?.take(100)?:"unknown error")}
    }
    /** "0:42" for a duration in milliseconds. */
    fun clock(ms:Int):String {val s=(ms/1000).coerceAtLeast(0);return (s/60).toString()+":"+(s%60).toString().padStart(2,'0')}
}

/** A sound in the chat: tap to play or pause. */
@Composable fun AudioChip(file:File,name:String) {
    val context=androidx.compose.ui.platform.LocalContext.current
    var player by remember(file) {mutableStateOf<MediaPlayer?>(null)}
    var playing by remember(file) {mutableStateOf(false)}
    var position by remember(file) {mutableStateOf(0)}
    var length by remember(file) {mutableStateOf(0)}
    LaunchedEffect(file) {length=withContext(Dispatchers.IO) {runCatching {MediaMetadataRetriever().run {setDataSource(file.path);extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()?:0}}.getOrDefault(0)}}
    LaunchedEffect(playing) {while(playing) {position=player?.currentPosition?:0;delay(250)}}
    DisposableEffect(file) {onDispose {runCatching {player?.release()}}}
    Row(Modifier.clip(RoundedCornerShape(18.dp)).background(Raised2).padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Strong).clickable {
            runCatching {
                val p=player?:MediaPlayer().apply {
                    setDataSource(file.path);prepare()
                    setOnCompletionListener {playing=false;position=0;runCatching {seekTo(0)}}
                }.also {player=it;length=it.duration}
                if(playing) {p.pause();playing=false} else {p.start();playing=true}
            }.onFailure {playing=false}
        },contentAlignment=Alignment.Center) {Icon(if(playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(playing) "Pause" else "Play",tint=OnStrong,modifier=Modifier.size(22.dp))}
        Spacer(Modifier.width(12.dp))
        Column(Modifier.widthIn(max=190.dp)) {
            Text(name,color=Ink,fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(MediaSaver.clock(position)+" / "+MediaSaver.clock(length),color=Muted,fontSize=11.sp)
        }
    }
}

/** A video in the chat: a thumbnail with a play button; tap to watch it full screen. */
@Composable fun VideoChip(file:File,name:String,open:()->Unit) {
    val thumb by produceState<Bitmap?>(null,file) {
        value=withContext(Dispatchers.IO) {runCatching {MediaMetadataRetriever().run {setDataSource(file.path);getFrameAtTime(500_000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {b->Bitmap.createScaledBitmap(b,360,(b.height*360f/b.width).toInt().coerceAtLeast(1),true)}}}.getOrNull()}
    }
    Box(Modifier.widthIn(max=240.dp).clip(RoundedCornerShape(16.dp)).background(Raised2).clickable(onClick=open),contentAlignment=Alignment.Center) {
        thumb?.let {Image(it.asImageBitmap(),name,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxWidth())}?:Box(Modifier.size(240.dp,150.dp))
        Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xAA000000)),contentAlignment=Alignment.Center) {Icon(Icons.Outlined.PlayArrow,"Play",tint=Color.White,modifier=Modifier.size(28.dp))}
    }
}

/** Full-screen video player with Share and Save. */
@Composable fun VideoPreview(file:File,name:String,share:()->Unit,close:()->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    var note by remember {mutableStateOf("")}
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=Color.Black) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(name,color=Color.White,fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=1,modifier=Modifier.weight(1f).padding(start=8.dp))
                    TextButton(onClick={note=withContext0 {MediaSaver.save(context,file)}}) {Icon(Icons.Outlined.Download,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text("Save")}
                    TextButton(onClick=share) {Icon(Icons.Outlined.Share,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text("Share")}
                    TextButton(onClick=close) {Text("Close")}
                }
                if(note.isNotBlank()) Text(note,color=Muted,fontSize=12.sp,modifier=Modifier.padding(horizontal=16.dp))
                AndroidView(modifier=Modifier.weight(1f).fillMaxWidth(),factory={ctx->
                    VideoView(ctx).apply {
                        setVideoPath(file.path);setMediaController(MediaController(ctx).also {it.setAnchorView(this)})
                        setOnPreparedListener {it.isLooping=false;start()}
                    }
                },onRelease={it.stopPlayback()})
            }
        }
    }
}

/** Runs quick work in place (a save is small and fast); kept as a function so the click lambda stays readable. */
private fun withContext0(block:()->String):String=block()
