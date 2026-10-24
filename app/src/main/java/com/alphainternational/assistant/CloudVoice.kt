package com.alphainternational.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Human-sounding voices with emotion. The ChatGPT sign-in only covers chat (OpenAI's plan-usage docs list just /v1/responses
 * and /v1/models), so a realistic voice needs the owner's own voice account: ElevenLabs (most human, laughs and emotion tags)
 * or an OpenAI API key (gpt-4o-mini-tts with emotion instructions). The built-in offline voice stays as the free fallback.
 */
object CloudVoice {
    val providers=linkedMapOf("built_in" to "Built-in (free, works offline)","elevenlabs" to "ElevenLabs (most human)","openai" to "OpenAI voice")
    val elevenVoices=linkedMapOf("EXAVITQu4vr4xnSDxMaL" to "Sarah · warm","JBFqnCBsd6RMkjVDRZzb" to "George · calm","TX3LPaxmHKxFdv7VOQHJ" to "Liam · upbeat",
        "XrExE9yKIg1WjnnlVkGX" to "Matilda · friendly","pqHfZKP75CvOlQylNhV4" to "Bill · easygoing")
    val openaiVoices=linkedMapOf("marin" to "Marin · natural","cedar" to "Cedar · warm","coral" to "Coral · bright","sage" to "Sage · calm","ash" to "Ash · steady","verse" to "Verse · expressive")
    const val SAMPLE_RATE=24000

    /** The cloud voice settings, or null when the built-in voice is in use (or no key was added yet). */
    fun active(config:JSONObject):JSONObject? =
        config.takeIf {it.optString("provider") in setOf("elevenlabs","openai") && it.optString("key").isNotBlank()}
    fun voiceName(config:JSONObject)=when(config.optString("provider")) {
        "elevenlabs"->elevenVoices[config.optString("voice")]?:elevenVoices.values.first()
        "openai"->openaiVoices[config.optString("voice")]?:openaiVoices.values.first()
        else->"Alpha"
    }
    fun checkKey(provider:String,key:String):String {
        val clean=key.trim()
        require(clean.length in 20..300 && !clean.contains(Regex("\\s"))) {"That doesn't look like a complete key. Copy it again from your account."}
        if(provider=="openai") require(clean.startsWith("sk-")) {"An OpenAI key starts with sk-."}
        return clean
    }

    /**
     * Text the voice understands: links and markdown removed. ElevenLabs' expressive model reads cues like [laughs];
     * OpenAI's voice gets its emotion from the instructions instead, so cues are dropped there.
     */
    fun prepare(text:String,provider:String):String {
        var spoken=text.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"),"$1").replace(Regex("https?://\\S+"),"").replace(Regex("[#*_`>|]"),"")
        spoken=if(provider=="elevenlabs") spoken.replace("[chuckle]","[chuckles]",true).replace("[laugh]","[laughs]",true)
            else spoken.replace(Regex("\\[[a-zA-Z ]{2,20}]"),"")
        return spoken.replace(Regex("\\s+")," ").trim().take(2500)
    }

    suspend fun speak(config:JSONObject,text:String):FloatArray=withContext(Dispatchers.IO) {
        val provider=config.optString("provider")
        val words=prepare(text,provider);require(words.isNotBlank()) {"Nothing to say."}
        val pcm=when(provider) {
            "elevenlabs"-> {
                val voice=config.optString("voice").ifBlank {elevenVoices.keys.first()}
                fun ask(model:String)=post("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=pcm_24000",
                    mapOf("xi-api-key" to config.getString("key")),
                    obj("text" to words,"model_id" to model,"voice_settings" to obj("stability" to 0.5,"similarity_boost" to 0.75)))
                // eleven_v3 is the most expressive; older plans or regions fall back to multilingual v2.
                try {ask("eleven_v3")} catch(e:ApiFailure) {if(e.code in 400..422 && e.code!=401) ask("eleven_multilingual_v2") else throw e}
            }
            "openai"->post("https://api.openai.com/v1/audio/speech",mapOf("Authorization" to "Bearer "+config.getString("key")),
                obj("model" to "gpt-4o-mini-tts","voice" to config.optString("voice").ifBlank {"marin"},"input" to words,"response_format" to "pcm",
                    "instructions" to "Talk like a real, warm person on a phone call, not a narrator. Natural rhythm with small pauses. " +
                        "Let feeling come through: upbeat and friendly for good news, gentle and caring for problems, a little laugh in your voice when something is funny."))
            else->error("Choose a voice service first.")
        }
        pcm16(pcm)
    }
    /** 16-bit little-endian mono samples → the floats the phone's audio player takes. */
    fun pcm16(bytes:ByteArray):FloatArray {
        require(bytes.size>=2) {"The voice service returned no audio."}
        return FloatArray(bytes.size/2) {i->((bytes[i*2].toInt() and 0xFF) or (bytes[i*2+1].toInt() shl 8)).toShort()/32768f}
    }
    private fun post(url:String,headers:Map<String,String>,body:JSONObject):ByteArray {
        val connection=URL(url).openConnection() as HttpURLConnection
        connection.requestMethod="POST";connection.doOutput=true;connection.connectTimeout=15_000;connection.readTimeout=60_000;connection.instanceFollowRedirects=false
        headers.forEach {(k,v)->connection.setRequestProperty(k,v)}
        connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Accept","*/*")
        try {
            connection.outputStream.use {it.write(body.toString().toByteArray())}
            val code=connection.responseCode
            if(code !in 200..299) {
                val raw=connection.errorStream?.bufferedReader()?.use {it.readText().take(600)}.orEmpty()
                val reason=when(code) {
                    401->"The voice key was refused. Copy it again from your account."
                    402,429->"The voice account is out of credits or busy right now."
                    else->"The voice service said: "+Rules.redact(raw).take(300)
                }
                throw ApiFailure(code,reason)
            }
            return connection.inputStream.use {input->input.readBytes().also {require(it.size<=40_000_000) {"The voice answer was too long."}}}
        } finally {connection.disconnect()}
    }
}
