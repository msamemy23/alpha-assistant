package com.alphainternational.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import com.alphainternational.assistant.media.PcmDecoder
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Makes a video from a checked edit list (MediaSpec) with Android's own hardware video tools (Media3 Transformer): trims, joins, speeds, fits to a
 * vertical or wide frame, puts text, captions and pictures on top, mixes in music, and saves an MP4. Runs on the phone, free, no account.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object MediaEdit {
    class Done(val file:File,val seconds:Double,val bytes:Long,val note:String="")
    private const val TAG="AlphaMedia"

    // ---- Effects that Media3 doesn't ship ----

    /** A picture clip that slowly zooms in, so a still photo feels alive. */
    private class SlowZoom(private val durationUs:Long,private val end:Float=1.12f):MatrixTransformation {
        override fun getMatrix(presentationTimeUs:Long):Matrix {
            val t=(presentationTimeUs.toFloat()/durationUs.coerceAtLeast(1)).coerceIn(0f,1f);val s=1f+(end-1f)*t
            return Matrix().apply {postScale(s,s)}
        }
    }
    private fun constantSpeed(speed:Float)=object:SpeedProvider {
        override fun getSpeed(timeUs:Long)=speed
        override fun getNextSpeedChangeTimeUs(timeUs:Long)=C.TIME_UNSET
    }
    private fun styled(text:String,size:Int,color:Int,background:Int):SpannableString {
        val s=SpannableString(text)
        s.setSpan(AbsoluteSizeSpan(size,false),0,s.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(color),0,s.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(BackgroundColorSpan(background),0,s.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),0,s.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }
    private fun anchorY(position:String)=when(position) {"top"->0.72f;"center"->0f;else->-0.68f}
    private fun parse(c:String)=Color.parseColor(c)

    /** Text shown between two moments, near the top, middle or bottom. */
    private class TimedText(private val spec:MediaSpec.Text,private val width:Int):TextOverlay() {
        private val start=(spec.start*1_000_000).toLong();private val end=(spec.end*1_000_000).toLong()
        private val shown=styled(MediaSpec.wrap(spec.text,width,spec.size),spec.size,parse(spec.color),parse(spec.background))
        private val hidden=styled(" ",1,Color.TRANSPARENT,Color.TRANSPARENT)
        override fun getText(presentationTimeUs:Long):SpannableString=if(presentationTimeUs in start until end) shown else hidden
        override fun getOverlaySettings(presentationTimeUs:Long):OverlaySettings=StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f,anchorY(spec.position)).setAlphaScale(if(presentationTimeUs in start until end) 1f else 0f).build()
    }
    /** All the captions as one overlay that shows the line for the current moment. */
    private class Captions(private val captions:List<MediaSpec.Caption>,private val width:Int,private val size:Int):TextOverlay() {
        private val hidden=styled(" ",1,Color.TRANSPARENT,Color.TRANSPARENT)
        private val cache=HashMap<MediaSpec.Caption,SpannableString>()
        override fun getText(presentationTimeUs:Long):SpannableString {
            val c=MediaSpec.captionAt(captions,presentationTimeUs/1_000_000.0)?:return hidden
            return cache.getOrPut(c) {styled(MediaSpec.wrap(c.text,width,size),size,Color.WHITE,Color.argb(170,0,0,0))}
        }
        override fun getOverlaySettings(presentationTimeUs:Long):OverlaySettings=StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f,-0.62f).setAlphaScale(if(MediaSpec.captionAt(captions,presentationTimeUs/1_000_000.0)!=null) 1f else 0f).build()
    }
    /** A picture (a logo, say) in a corner for part of the video. */
    private class TimedPicture(private val bitmap:Bitmap,private val spec:MediaSpec.Picture,private val frameWidth:Int):BitmapOverlay() {
        private val start=(spec.start*1_000_000).toLong();private val end=(spec.end*1_000_000).toLong()
        override fun getBitmap(presentationTimeUs:Long):Bitmap=bitmap
        override fun getOverlaySettings(presentationTimeUs:Long):OverlaySettings {
            val (ox,oy,bx,by)=when(spec.position) {
                "top-left"->listOf(-1f,1f,-0.94f,0.94f);"bottom-left"->listOf(-1f,-1f,-0.94f,-0.94f);"bottom-right"->listOf(1f,-1f,0.94f,-0.94f)
                "center"->listOf(0f,0f,0f,0f);else->listOf(1f,1f,0.94f,0.94f)
            }
            val scale=(frameWidth*spec.scale/bitmap.width).toFloat()
            return StaticOverlaySettings.Builder().setOverlayFrameAnchor(ox,oy).setBackgroundFrameAnchor(bx,by).setScale(scale,scale)
                .setAlphaScale(if(presentationTimeUs in start until end) 1f else 0f).build()
        }
    }

    private fun toBitmap(file:File,maxSide:Int):Bitmap=Attachments.upright(file,maxSide)

    /**
     * Builds and runs the export. Must be called from a coroutine; the work itself happens on the phone's video hardware.
     * [progress] gets 0..100. Cancelling the coroutine stops the export.
     */
    suspend fun render(context:Context,dir:File,spec:MediaSpec.Spec,progress:(Int)->Unit):Done {
        if(spec.sounds.isEmpty()) return export(context,dir,spec,null,progress)
        // The music is mixed, faded and written as one plain WAV first (MusicBed), so the video tool only has to lay one ordinary sound under the pictures.
        val bed=File(dir,".bed-"+System.nanoTime()+".wav")
        try {
            withContext(Dispatchers.Default) {makeBed(context,dir,spec,bed)}
            return try {export(context,dir,spec,bed,progress)}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {
                // A video without music beats no video, as long as the bot is told plainly.
                Log.w(TAG,"export with music failed; trying again without it",e)
                val plain=export(context,dir,spec,null,progress)
                Done(plain.file,plain.seconds,plain.bytes,"The music could not be added ("+(e.message?.take(200)?:"unknown reason")+"), so this video has NO music. Tell the owner that plainly.")
            }
        } finally {bed.delete()}
    }

    private fun makeBed(context:Context,dir:File,spec:MediaSpec.Spec,bed:File) {
        val tracks=spec.sounds.map {s->
            val f=File(dir,s.file)
            require(f.isFile) {s.file+" isn't in this bot's files."}
            val clip=try {PcmDecoder.decode(context,Uri.fromFile(f))} catch(e:Exception) {throw IllegalArgumentException("This phone couldn't read the music file "+s.file+" ("+(e.message?.take(100)?:"unknown")+").")}
            MusicBed.Track(clip,s.volume,s.loop)
        }
        MusicBed.writeWav(bed,MusicBed.mix(tracks,spec.total,spec.sounds.maxOf {it.fadeIn},spec.sounds.maxOf {it.fadeOut}))
    }

    private suspend fun export(context:Context,dir:File,spec:MediaSpec.Spec,bed:File?,progress:(Int)->Unit):Done=withContext(Dispatchers.Main) {
        val out=File(dir,spec.output);val temp=File(dir,".render-"+System.nanoTime()+".mp4")
        val total=spec.total
        fun uri(name:String)=Uri.fromFile(File(dir,name))
        fun presentation(fit:String)=Presentation.createForWidthAndHeight(spec.width,spec.height,if(fit=="fit") Presentation.LAYOUT_SCALE_TO_FIT else Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)

        val items=spec.clips.map {c->
            require(File(dir,c.file).isFile) {c.file+" isn't in this bot's files."}
            val media=if(c.image) MediaItem.Builder().setUri(uri(c.file)).setImageDurationMs((c.seconds*1000).toLong()).build()
                else MediaItem.Builder().setUri(uri(c.file)).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs((c.start*1000).toLong()).setEndPositionMs((c.end*1000).toLong()).build()).build()
            val audio=mutableListOf<AudioProcessor>();val video=mutableListOf<Effect>()
            if(!c.image && c.speed!=1.0) {val pair=Effects.createExperimentalSpeedChangingEffect(constantSpeed(c.speed.toFloat()));audio+=pair.first;video+=pair.second}
            if(c.image && c.zoom) video+=SlowZoom((c.seconds*1_000_000).toLong())
            video+=presentation(c.fit)
            EditedMediaItem.Builder(media).setEffects(Effects(audio,video)).setRemoveAudio(c.mute || c.image).setFrameRate(spec.fps).also {b->
                if(c.image) b.setDurationUs((c.seconds*1_000_000).toLong())
            }.build()
        }
        val sequences=mutableListOf(EditedMediaItemSequence.Builder(items).experimentalSetForceAudioTrack(true).build())
        if(bed!=null) sequences+=EditedMediaItemSequence.Builder(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(bed))).setRemoveVideo(true).build()).build()
        val overlays=mutableListOf<TextureOverlay>()
        spec.texts.forEach {overlays+=TimedText(it,spec.width)}
        if(spec.captions.isNotEmpty()) overlays+=Captions(spec.captions,spec.width,spec.captionSize)
        spec.pictures.forEach {p->
            require(File(dir,p.file).isFile) {p.file+" isn't in this bot's files."}
            overlays+=TimedPicture(toBitmap(File(dir,p.file),512),p,spec.width)
        }
        val composition=Composition.Builder(sequences).setEffects(Effects(emptyList<AudioProcessor>(),if(overlays.isEmpty()) emptyList<Effect>() else listOf<Effect>(OverlayEffect(overlays)))).build()
        Log.i(TAG,"export start: ${spec.clips.size} clips, ${spec.texts.size} texts, ${spec.captions.size} captions, ${spec.pictures.size} pictures, music=${bed!=null}, ${spec.width}x${spec.height}, ${"%.1f".format(total)} s")

        val finished=CompletableDeferred<ExportResult>()
        val transformer=Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object:Transformer.Listener {
                override fun onCompleted(composition:Composition,exportResult:ExportResult) {finished.complete(exportResult)}
                override fun onError(composition:Composition,exportResult:ExportResult,exportException:ExportException) {finished.completeExceptionally(exportException)}
            }).build()
        try {
            transformer.start(composition,temp.path)
            val holder=ProgressHolder()
            while(!finished.isCompleted) {
                delay(500)
                if(transformer.getProgress(holder)==Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress)
            }
            val result=try {finished.await()} catch(e:ExportException) {
                Log.w(TAG,"export failed: "+e.errorCodeName+" "+e.message,e)
                throw IllegalStateException(explain(e),e)
            }
            Log.i(TAG,"export done: ${result.durationMs} ms of video, ${temp.length()} bytes")
            require(temp.isFile && temp.length()>2000) {"The video came out empty."}
            out.delete()
            require(temp.renameTo(out)) {"The finished video couldn't be saved."}
            progress(100)
            Done(out,result.durationMs/1000.0,out.length())
        } catch(e:CancellationException) {
            runCatching {transformer.cancel()};throw e
        } finally {temp.delete()}
    }

    /** What went wrong, in words a bot can act on. */
    fun explain(e:ExportException):String = when(e.errorCode) {
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,ExportException.ERROR_CODE_DECODER_INIT_FAILED,ExportException.ERROR_CODE_DECODING_FAILED->
            "This phone couldn't read one of the clips (format or size not supported). Try a shorter, smaller clip, or convert it first."
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,ExportException.ERROR_CODE_ENCODER_INIT_FAILED,ExportException.ERROR_CODE_ENCODING_FAILED->
            "This phone couldn't make a video that size. Try a smaller size such as 720x1280."
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND->"A clip file wasn't found. Check the names with files_list."
        else->"The video export failed ("+e.errorCodeName+": "+(e.message?.take(160)?:"no details")+")."
    }

    // ---- Looking at a video ----

    /** Facts about a video or sound file: length, size, whether it has sound, how it was turned. */
    fun probe(file:File):JSONObject {
        require(file.isFile) {"${file.name} isn't in this bot's files."}
        val r=MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            fun key(k:Int)=r.extractMetadata(k)
            val rotation=key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?:0
            var w=key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull();var h=key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if((rotation==90 || rotation==270) && w!=null && h!=null) {val t=w;w=h;h=t}
            return obj("name" to file.name,"seconds" to ((key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0)/1000.0),"width" to (w?:JSONObject.NULL),"height" to (h?:JSONObject.NULL),
                "has_video" to (w!=null),"has_audio" to (key(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)=="yes"),"frame_rate" to (key(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?:JSONObject.NULL),
                "bitrate_kbps" to ((key(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?:0)/1000),"megabytes" to Math.round(file.length()/10_000.0)/100.0)
        } finally {runCatching {r.release()}}
    }

    /** A picture of evenly spaced frames from a video, each labelled with its time, so a bot can see what it made. */
    fun contactSheet(file:File,frames:Int):Bitmap {
        val r=MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val duration=(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0)*1000
            require(duration>0) {"${file.name} doesn't look like a video."}
            val n=frames.coerceIn(2,12);val cols=if(n<=4) n else if(n<=6) 3 else 4
            val shots=(0 until n).map {i->
                val t=duration*(2*i+1)/(2*n)
                (r.getFrameAtTime(t,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?:Bitmap.createBitmap(120,200,Bitmap.Config.ARGB_8888)) to t
            }
            val tileW=if(shots[0].first.width>shots[0].first.height) 320 else 200
            val tileH=(shots[0].first.height*tileW/shots[0].first.width.toFloat()).toInt()
            val rows=(n+cols-1)/cols;val pad=8;val label=16
            val sheet=Bitmap.createBitmap(pad+cols*(tileW+pad),pad+rows*(tileH+label+pad),Bitmap.Config.ARGB_8888)
            val canvas=Canvas(sheet);canvas.drawColor(Color.rgb(32,32,36))
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(210,210,215);textSize=13f}
            shots.forEachIndexed {i,(bmp,t)->
                val x=pad+(i%cols)*(tileW+pad);val y=pad+(i/cols)*(tileH+label+pad)
                canvas.drawText("%.1f s".format(t/1_000_000.0),x.toFloat(),(y+12).toFloat(),paint)
                canvas.drawBitmap(Bitmap.createScaledBitmap(bmp,tileW,tileH,true),x.toFloat(),(y+label).toFloat(),null)
            }
            return sheet
        } finally {runCatching {r.release()}}
    }
}
