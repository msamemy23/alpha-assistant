package com.alphainternational.assistant

import kotlin.math.exp
import kotlin.random.Random

/** Turbo's temperature/top-k/nucleus sampling; the ONNX graph supplies the logits. */
internal object VoiceStyle {
    val styles=listOf("Natural","Expressive")
    fun instruction(style:String):String =
        "This is a voice call. Use warm, conversational English with contractions. " +
        "Respond in one or two short sentences, with natural punctuation and pauses. Avoid markdown, lists and long monologues. " +
        "Match the user's mood respectfully; don't pretend to feel things or invent results. " +
        if(style=="Expressive") "When it fits a lighthearted moment, you may use one [chuckle] or [laugh] cue. " +
            "Keep cues rare. Never laugh at a problem, error, distress or a serious business matter. " +
            "Do not use unsupported emotion labels such as [happy] or [sad]."
        else "Use a calm delivery without bracketed expression cues."

    fun preview(style:String):String = if(style=="Expressive")
        "Hey, I'm Alpha! [chuckle] It's good to talk with you."
    else "Hey, I'm Alpha. It's good to talk with you."

    fun normalize(text:String):String {
        var value=text.trim().replace(Regex("\\s+")," ")
        require(value.isNotBlank()) {"Give the local voice something to say."}
        for((old,new) in listOf("…" to ", ",":" to ",","—" to "-","–" to "-"," ," to ",","“" to "\"","”" to "\"","‘" to "'","’" to "'"))value=value.replace(old,new)
        if(value.first().isLowerCase())value=value.first().uppercaseChar()+value.drop(1)
        if(value.last() !in ".!?-,")value+="."
        return value
    }

    fun sample(scores:FloatArray,style:String,random:Random=Random.Default):Long {
        require(scores.size>6562) {"The local voice returned an incompatible vocabulary."}
        require(scores.none {it.isNaN() || it==Float.POSITIVE_INFINITY}) {"The local voice returned invalid speech scores."}
        val candidates=scores.indices.filter {it!=6561 && scores[it].isFinite()}
            .sortedByDescending {scores[it]}.take(1000)
        require(candidates.isNotEmpty()) {"The local voice returned no usable speech scores."}
        val temperature=if(style=="Expressive") 0.8 else 0.65
        val maximum=scores[candidates.first()].toDouble()
        val weights=candidates.map {exp((scores[it]-maximum)/temperature)}
        val total=weights.sum()
        var nucleus=0.0;var length=0
        while(length<weights.size && nucleus<total*0.95) {nucleus+=weights[length];length++}
        var pick=random.nextDouble()*nucleus
        for(index in 0 until length) {pick-=weights[index];if(pick<=0) return candidates[index].toLong()}
        return candidates[length-1].toLong()
    }
}
