package com.alphainternational.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class VoiceModels(private val context:Context) {
    private val manifest=JSONObject(context.assets.open("chatterbox-models.json").bufferedReader().use {it.readText()})
    val folder=File(context.filesDir,"voice/chatterbox-turbo-q4").apply {mkdirs()}
    private val files=manifest.getJSONArray("files").objects()
    val totalBytes=files.sumOf {it.getLong("size")}
    val ready:Boolean get()=files.all {val f=File(folder,it.getString("path"));f.isFile && f.length()==it.getLong("size")}
    fun reference():File=File(folder,"alpha-reference.wav").apply {
        if(!exists()) context.assets.open("alpha-reference.wav").use {input->outputStream().use {input.copyTo(it)}}
    }
    suspend fun download(progress:suspend (Long,Long)->Unit) = withContext(Dispatchers.IO) {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val network=manager.getNetworkCapabilities(manager.activeNetwork)
        require(network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)==true) {"Connect to unmetered Wi-Fi to download the 725 MB voice pack."}
        var complete=0L
        for(spec in files) {
            currentCoroutineContext().ensureActive()
            val path=spec.getString("path");val size=spec.getLong("size")
            require(path=="tokenizer.json" || path.matches(Regex("onnx/(speech_encoder|embed_tokens|language_model|conditional_decoder)_q4\\.onnx(_data)?")))
            val file=File(folder,path);file.parentFile?.mkdirs()
            if(file.isFile && file.length()==size && checksum(file)==spec.getString("sha256")) {
                complete+=size;progress(complete,totalBytes);continue
            }
            val part=File(file.parentFile,file.name+".part")
            if(part.length()>size) part.delete()
            var offset=part.length()
            if(offset<size) {
                var address="https://huggingface.co/"+manifest.getString("repo")+"/resolve/"+manifest.getString("commit")+"/"+path
                var connection:HttpURLConnection?=null
                for(redirect in 0..8) {
                    val next=URL(address).openConnection() as HttpURLConnection
                    next.instanceFollowRedirects=false;next.connectTimeout=25_000;next.readTimeout=90_000
                    if(offset>0) next.setRequestProperty("Range","bytes="+offset+"-")
                    val code=next.responseCode
                    if(code in 300..399) {
                        val location=next.getHeaderField("Location")?:error("Voice model download returned an invalid redirect.")
                        val uri=java.net.URI(address).resolve(location);require(uri.scheme=="https") {"Voice download redirected outside HTTPS."}
                        address=uri.toASCIIString();next.disconnect()
                    } else {connection=next;break}
                }
                val active=connection?:error("Too many voice download redirects.")
                try {
                    require(active.responseCode in setOf(200,206)) {"Voice pack download returned HTTP "+active.responseCode+"."}
                    if(active.responseCode==200) offset=0
                    active.inputStream.use {input->
                        java.io.FileOutputStream(part,offset>0).use {output->
                            val buffer=ByteArray(64*1024);var received=offset;var lastProgress=0L
                            while(true) {
                                currentCoroutineContext().ensureActive()
                                val n=input.read(buffer);if(n<0)break
                                received+=n;require(received<=size) {"Voice model exceeded its expected size."}
                                output.write(buffer,0,n)
                                if(System.currentTimeMillis()-lastProgress>200) {lastProgress=System.currentTimeMillis();progress(complete+received,totalBytes)}
                            }
                        }
                    }
                } finally {active.disconnect()}
            }
            if(part.length()!=size || checksum(part)!=spec.getString("sha256")) {
                part.delete()
                error("Voice model verification failed. Retry the download.")
            }
            if(file.exists()) file.delete()
            require(part.renameTo(file)) {"Could not save the verified voice model."}
            complete+=size;progress(complete,totalBytes)
        }
        reference()
    }
    private fun checksum(file:File):String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val buffer=ByteArray(64*1024);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}}
        return hash.digest().joinToString("") {"%02x".format(it)}
    }
}
