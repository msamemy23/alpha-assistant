package com.alphainternational.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiFailure(val code:Int, message:String,val retryAfterMs:Long=0):Exception(message)
object ReadRetry {
    fun delayMs(method:String,attempt:Int,error:Exception):Long? {
        if(method!="GET" || attempt>=2)return null
        if(error is ApiFailure && error.code !in setOf(408,429,500,502,503,504))return null
        if(error !is ApiFailure && error !is java.io.IOException)return null
        val wait=if(error is ApiFailure && error.retryAfterMs>0)error.retryAfterMs else 500L*(attempt+1)
        return wait.takeIf {it<=30000}
    }
}
object Http {
    suspend fun text(url:String,method:String="GET",headers:Map<String,String> = emptyMap(),body:String?=null,contentType:String="application/json"):String {
        var attempt=0
        while(true) {
            try{return once(url,method,headers,body,contentType)}catch(e:Exception) {
                val wait=ReadRetry.delayMs(method,attempt,e)?:throw e
                attempt++;delay(wait)
            }
        }
    }
    private suspend fun once(url:String,method:String,headers:Map<String,String>,body:String?,contentType:String):String =
        withContext(Dispatchers.IO) {
            Rules.httpsUrl(url)
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout=25_000; connection.readTimeout=90_000; connection.requestMethod=method; connection.instanceFollowRedirects=false
            headers.forEach { (k,v) -> require(!k.contains('\n') && !v.contains('\n') && !k.contains('\r') && !v.contains('\r')); connection.setRequestProperty(k,v) }
            try {
                if(body!=null) { connection.doOutput=true; connection.setRequestProperty("Content-Type",contentType); connection.outputStream.use { it.write(body.toByteArray()) } }
                val code=connection.responseCode
                val result=(if(code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { reader->
                    val buffer=CharArray(8192); val result=StringBuilder()
                    while(true) {
                        val n=reader.read(buffer); if(n<0) break
                        require(result.length+n<=2_000_000) { "Response is too large for this phone task. Narrow the request." }
                        result.append(buffer,0,n)
                    }
                    result.toString()
                }?:""
                if(code !in 200..299) {
                    val message = runCatching { JSONObject(result).optJSONObject("error")?.optString("message") ?: JSONObject(result).optString("error_description",result.take(400)) }.getOrDefault(result.take(400))
                    val retry=connection.getHeaderField("Retry-After").orEmpty()
                    val retryMs=retry.toLongOrNull()?.coerceAtLeast(0)?.times(1000)?:runCatching {
                        (java.time.ZonedDateTime.parse(retry,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis()).coerceAtLeast(0)
                    }.getOrDefault(0)
                    throw ApiFailure(code, "HTTP " + code + ": " + Rules.redact(message,headers.values),retryMs)
                }
                result
            } finally { connection.disconnect() }
        }
    suspend fun json(url:String,method:String="GET",headers:Map<String,String> = emptyMap(),body:JSONObject?=null):JSONObject =
        JSONObject(text(url,method,headers,body?.toString()))
    fun form(values:Map<String,String>) = values.entries.joinToString("&") { enc(it.key)+"="+enc(it.value) }
}
