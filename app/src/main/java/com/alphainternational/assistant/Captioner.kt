package com.alphainternational.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.alphainternational.assistant.media.PcmDecoder
import com.alphainternational.assistant.media.WhisperOrt
import com.alphainternational.assistant.media.Word
import com.alphainternational.assistant.media.toMono16k
import com.alphainternational.assistant.media.transcribeLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The speech-to-text model for video captions: OpenAI's Whisper (base, 8-bit) converted to ONNX, run on the phone with the same ONNX Runtime the voice
 * uses. A one-time download of about 78 MB from Hugging Face, pinned to one exact version and checked file by file, then it works offline.
 */
class CaptionModels(private val context:Context) {
    private data class Spec(val path:String,val size:Long,val sha256:String)
    private val repo="onnx-community/whisper-base_timestamped"
    private val commit="608c49e61301901684bc36cac8f74b95ff6b5a8e"
    private val files=listOf(
        Spec("onnx/encoder_model_int8.onnx",23_159_150,"152da96dd8ff3f28f3fadabc2e8960405a277846453ff94ed411fe935a72917f"),
        Spec("onnx/decoder_model_merged_int8.onnx",53_712_708,"cf9a8d5bcddc0917a0078135b484cedcaf44f28909cd91910abd29dced9171db"),
        Spec("vocab.json",1_036_584,"50d6a919f0a0601d56a04eb583c780d18553aa388254ba3158eb6a00f13e2c1a"),
        Spec("generation_config.json",3_832,"61070cf8de25b1e9256e8e102ded49d8d24a8369ed36ef84fdf21549e68125a0"),
        Spec("config.json",2_243,"f4d0608f7d918166da7edb3e188de5ef1bfe70d9802e785d271fd88111e9cf4b"))
    val folder=File(context.filesDir,"captions/whisper-base")
    val totalBytes=files.sumOf {it.size}
    val ready:Boolean get()=files.all {val f=File(folder,it.path);f.isFile && f.length()==it.size}

    fun remove() {folder.deleteRecursively()}

    suspend fun download(progress:suspend (Long,Long)->Unit) = withContext(Dispatchers.IO) {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val network=manager.getNetworkCapabilities(manager.activeNetwork)
        require(network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)==true) {"Connect to unmetered Wi-Fi to download the 78 MB captions model."}
        folder.mkdirs()
        var complete=0L
        for(spec in files) {
            currentCoroutineContext().ensureActive()
            val file=File(folder,spec.path);file.parentFile?.mkdirs()
            if(file.isFile && file.length()==spec.size && checksum(file)==spec.sha256) {complete+=spec.size;progress(complete,totalBytes);continue}
            val part=File(file.parentFile,file.name+".part")
            if(part.length()>spec.size) part.delete()
            var offset=part.length()
            if(offset<spec.size) {
                var address="https://huggingface.co/$repo/resolve/$commit/${spec.path}"
                var connection:HttpURLConnection?=null
                for(redirect in 0..8) {
                    val next=URL(address).openConnection() as HttpURLConnection
                    next.instanceFollowRedirects=false;next.connectTimeout=25_000;next.readTimeout=90_000
                    if(offset>0) next.setRequestProperty("Range","bytes=$offset-")
                    val code=next.responseCode
                    if(code in 300..399) {
                        val location=next.getHeaderField("Location")?:error("The captions download returned an invalid redirect.")
                        val uri=java.net.URI(address).resolve(location);require(uri.scheme=="https") {"The captions download redirected outside HTTPS."}
                        address=uri.toASCIIString();next.disconnect()
                    } else {connection=next;break}
                }
                val active=connection?:error("Too many redirects in the captions download.")
                try {
                    require(active.responseCode in setOf(200,206)) {"The captions download returned HTTP ${active.responseCode}."}
                    if(active.responseCode==200) offset=0
                    active.inputStream.use {input->
                        java.io.FileOutputStream(part,offset>0).use {output->
                            val buffer=ByteArray(64*1024);var received=offset;var last=0L
                            while(true) {
                                currentCoroutineContext().ensureActive()
                                val n=input.read(buffer);if(n<0) break
                                received+=n;require(received<=spec.size) {"A captions file was bigger than expected."}
                                output.write(buffer,0,n)
                                if(System.currentTimeMillis()-last>200) {last=System.currentTimeMillis();progress(complete+received,totalBytes)}
                            }
                        }
                    }
                } finally {active.disconnect()}
            }
            if(part.length()!=spec.size || checksum(part)!=spec.sha256) {part.delete();error("The captions model failed its check. Try the download again.")}
            if(file.exists()) file.delete()
            require(part.renameTo(file)) {"Could not save the captions model."}
            complete+=spec.size;progress(complete,totalBytes)
        }
    }
    private fun checksum(file:File):String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val buffer=ByteArray(64*1024);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}}
        return hash.digest().joinToString("") {"%02x".format(it)}
    }
}

/** Turns the speech in a video or sound file into words with times, and the words into short caption lines. */
object Captioner {
    const val MAX_SECONDS=600.0
    class Transcript(val words:List<Word>,val captions:List<MediaSpec.Caption>,val text:String,val seconds:Double)

    /**
     * Listens to [file] from [from] to [to] seconds (to <= from means to the end). Times in the result start at 0 at [from], so captions made for a clip
     * cut at [from] line up when that clip starts the video. [progress] gets 0..100.
     */
    suspend fun listen(context:Context,models:CaptionModels,file:File,from:Double,to:Double,language:String,progress:(Int)->Unit):Transcript = withContext(Dispatchers.Default) {
        require(file.isFile) {"${file.name} isn't in this bot's files."}
        require(models.ready) {"The captions model isn't downloaded."}
        val startUs=(from.coerceAtLeast(0.0)*1_000_000).toLong()
        val length=if(to>from) to-from else MAX_SECONDS
        require(length<=MAX_SECONDS) {"Captions can listen to at most ${MAX_SECONDS.toInt()/60} minutes at a time. Use from and to to pick a part."}
        progress(2)
        val clip=try {PcmDecoder.decode(context,Uri.fromFile(file),startUs,(length*1_000_000).toLong())}
            catch(e:IllegalArgumentException) {throw IllegalArgumentException("${file.name} has no sound to listen to.")}
        val pcm=clip.toMono16k()
        require(pcm.size>=16_000/2) {"There is less than a second of sound there."}
        progress(8)
        val words=WhisperOrt(models.folder).use {whisper->
            whisper.transcribeLong(pcm,language.trim().lowercase().ifBlank {null}) {share->progress((8+share*90).toInt())}
        }
        val captions=MediaSpec.lines(words.map {Triple(it.text.trim(),it.startSec.toDouble(),it.endSec.toDouble())}.filter {it.first.isNotEmpty()})
        Transcript(words,captions,words.joinToString(" ") {it.text.trim()}.replace(Regex("\\s+")," ").trim(),pcm.size/16000.0)
    }
}
