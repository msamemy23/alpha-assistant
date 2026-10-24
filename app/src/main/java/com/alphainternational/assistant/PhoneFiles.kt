package com.alphainternational.assistant

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny private link on this phone only (127.0.0.1) so the bots' command line can fetch files from a bot's own folder and put results back,
 * without Termux ever being given access to Alpha's data or the phone's shared storage. Each command run gets its own secret that works for
 * that one bot's folder and stops working when the command ends.
 */
object PhoneFiles {
    private const val MAX_BYTES=400L*1024*1024
    private val grants=ConcurrentHashMap<String,Grant>()
    private class Grant(val dir:File,val until:Long)
    @Volatile private var server:ServerSocket?=null
    val port get()=server?.localPort?:0

    /** Starts the link if it isn't running. Returns its port. */
    @Synchronized fun start():Int {
        server?.let {if(!it.isClosed) return it.localPort}
        val socket=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
        server=socket
        Thread({
            while(!socket.isClosed) {
                val client=try {socket.accept()} catch(e:Exception) {break}
                Thread({runCatching {handle(client)};runCatching {client.close()}},"alpha-files").apply {isDaemon=true}.start()
            }
        },"alpha-files-accept").apply {isDaemon=true}.start()
        return socket.localPort
    }

    /** A secret for [dir] that works for [seconds] seconds. */
    fun grant(dir:File,seconds:Int):String {
        val now=System.currentTimeMillis()
        grants.entries.removeAll {it.value.until<now}
        val token=randomUrl(24).filter {it.isLetterOrDigit()}
        grants[token]=Grant(dir,now+seconds*1000L+30_000)
        return token
    }
    fun revoke(token:String) {grants.remove(token)}

    /** Request text → reply, for one connection. Only GET /get/<name>, PUT /put/<name> and GET /list, always with the secret. */
    private fun handle(client:Socket) {
        client.soTimeout=60_000
        val input=BufferedInputStream(client.getInputStream())
        val request=readLine(input)?:return
        val parts=request.split(' ')
        if(parts.size<2) return
        val method=parts[0];val path=parts[1].substringBefore('?')
        val headers=mutableMapOf<String,String>()
        while(true) {val line=readLine(input)?:return;if(line.isEmpty()) break;line.indexOf(':').takeIf {it>0}?.let {headers[line.substring(0,it).trim().lowercase()]=line.substring(it+1).trim()}}
        val token=headers["authorization"]?.removePrefix("Bearer ")?.trim().orEmpty()
        val grant=grants[token]?.takeIf {it.until>System.currentTimeMillis()}
        val out=client.getOutputStream()
        if(grant==null) {reply(out,401,"Unauthorized");return}
        grant.dir.mkdirs()
        when {
            method=="GET" && path=="/list"-> reply(out,200,(grant.dir.listFiles()?.filter {it.isFile}?.joinToString("\n") {it.name+"\t"+it.length()}.orEmpty()))
            method=="GET" && path.startsWith("/get/")-> {
                val file=nameOf(path.removePrefix("/get/"))?.let {File(grant.dir,it)}
                if(file==null || !file.isFile) reply(out,404,"Not found")
                else {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: ${file.length()}\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n").toByteArray())
                    file.inputStream().use {it.copyTo(out)}
                }
            }
            method=="PUT" && path.startsWith("/put/")-> {
                val name=nameOf(path.removePrefix("/put/"))
                val length=headers["content-length"]?.toLongOrNull()
                if(name==null) reply(out,400,"Use a simple file name like result.mp4")
                else if(length==null || length<0 || length>MAX_BYTES) reply(out,413,"Too large or no length")
                else {
                    val temp=File(grant.dir,".incoming-"+System.nanoTime())
                    try {
                        temp.outputStream().use {copyExactly(input,it,length)}
                        val target=File(grant.dir,name);target.delete()
                        if(temp.renameTo(target)) reply(out,200,"ok") else reply(out,500,"Could not save")
                    } finally {temp.delete()}
                }
            }
            else-> reply(out,404,"Not found")
        }
        out.flush()
    }
    /** A flat, allowed file name (see Rules.fileName), or null. */
    private fun nameOf(raw:String):String? = runCatching {Rules.fileName(java.net.URLDecoder.decode(raw,"UTF-8"))}.getOrNull()

    private fun reply(out:java.io.OutputStream,code:Int,text:String) {
        val body=text.toByteArray()
        out.write(("HTTP/1.1 $code "+(if(code==200) "OK" else "Error")+"\r\nContent-Length: ${body.size}\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n").toByteArray());out.write(body)
    }
    private fun readLine(input:InputStream):String? {
        val line=StringBuilder()
        while(true) {
            val b=input.read();if(b<0) return if(line.isEmpty()) null else line.toString()
            if(b=='\n'.code) return line.toString().trimEnd('\r')
            if(line.length>8192) return null
            line.append(b.toChar())
        }
    }
    private fun copyExactly(input:InputStream,out:java.io.OutputStream,length:Long) {
        val buffer=ByteArray(64*1024);var left=length
        while(left>0) {val n=input.read(buffer,0,minOf(buffer.size.toLong(),left).toInt());if(n<0) throw java.io.IOException("Upload ended early");out.write(buffer,0,n);left-=n}
    }
}
