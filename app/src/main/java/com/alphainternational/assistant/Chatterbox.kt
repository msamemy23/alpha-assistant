package com.alphainternational.assistant

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.regex.Pattern

/** Byte-level BPE for the official Turbo tokenizer; no server or native tokenizer service. */
class TurboTokenizer(file:File) {
    private val vocabulary:Map<String,Int>
    private val ranks:Map<Pair<String,String>,Int>
    private val specials:Map<String,Int>
    private val bytes=Array(256) {""}
    private val cache=mutableMapOf<String,List<Int>>()
    private val pattern=Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+")
    init {
        val json=JSONObject(file.readText());val model=json.getJSONObject("model");val vocab=model.getJSONObject("vocab")
        vocabulary=vocab.keys().asSequence().associateWith {vocab.getInt(it)}
        val merges=model.getJSONArray("merges")
        ranks=(0 until merges.length()).associate {index->
            val raw=merges.get(index)
            val pair=if(raw is org.json.JSONArray) raw.getString(0) to raw.getString(1) else raw.toString().split(' ').let {it[0] to it[1]}
            pair to index
        }
        specials=json.getJSONArray("added_tokens").objects().associate {it.getString("content") to it.getInt("id")}
        val direct=((33..126)+(161..172)+(174..255)).toSet();var extra=0
        for(byte in 0..255) bytes[byte]=String(Character.toChars(if(byte in direct) byte else 256+extra++))
    }
    fun encode(text:String):LongArray {
        val ids=mutableListOf<Int>()
        val specialRegex=Regex(specials.keys.sortedByDescending {it.length}.joinToString("|") {Regex.escape(it)})
        var offset=0
        for(match in specialRegex.findAll(text)) {
            encodePlain(text.substring(offset,match.range.first),ids);ids+=specials.getValue(match.value);offset=match.range.last+1
        }
        encodePlain(text.substring(offset),ids)
        ids+=50256;ids+=50256
        require(ids.size<=500) {"Use a shorter sentence for the local voice."}
        return ids.map(Int::toLong).toLongArray()
    }
    private fun encodePlain(text:String,ids:MutableList<Int>) {
        val matcher=pattern.matcher(text)
        while(matcher.find()) {
            val word=matcher.group().toByteArray(Charsets.UTF_8).joinToString("") {bytes[it.toInt() and 255]}
            ids+=cache.getOrPut(word) {
                var parts=word.codePoints().toArray().map {String(Character.toChars(it))}
                while(parts.size>1) {
                    val best=parts.zipWithNext().mapIndexedNotNull {i,pair->ranks[pair]?.let {i to it}}.minByOrNull {it.second}?.first?:break
                    val joined=parts[best]+parts[best+1]
                    parts=parts.take(best)+joined+parts.drop(best+2)
                }
                parts.map {vocabulary[it]?:error("Voice tokenizer could not encode this text.")}
            }
        }
    }
}

object Wav {
    fun read(file:File,targetRate:Int=24000):FloatArray {
        val bytes=file.readBytes();require(bytes.size>44) {"Voice reference is incomplete."}
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes,0,4)=="RIFF" && String(bytes,8,4)=="WAVE") {"Use a PCM WAV voice reference."}
        var position=12;var channels=1;var rate=24000;var bits=16;var format=1;var data=ByteArray(0)
        while(position+8<=bytes.size) {
            val id=String(bytes,position,4);val size=b.getInt(position+4)
            require(size>=0 && position.toLong()+8+size<=bytes.size) {"Invalid WAV chunk."}
            if(id=="fmt ") {format=b.getShort(position+8).toInt();channels=b.getShort(position+10).toInt();rate=b.getInt(position+12);bits=b.getShort(position+22).toInt()}
            if(id=="data") data=bytes.copyOfRange(position+8,position+8+size)
            position+=8+size+(size and 1)
        }
        require(format==1 && bits==16 && channels in 1..2 && rate>0 && data.isNotEmpty()) {"Reference must be 16-bit PCM mono or stereo."}
        val source=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val mono=FloatArray(data.size/(2*channels)) {var sample=0f;repeat(channels){sample+=source.short/32768f};sample/channels}
        val length=(mono.size.toLong()*targetRate/rate).toInt()
        return FloatArray(length) {i->val p=i.toDouble()*rate/targetRate;val low=p.toInt().coerceAtMost(mono.lastIndex);val high=(low+1).coerceAtMost(mono.lastIndex);(mono[low]*(1-(p-low))+mono[high]*(p-low)).toFloat()}
    }
    fun write(file:File,audio:FloatArray,rate:Int=24000) {
        val out=ByteBuffer.allocate(44+audio.size*2).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36+audio.size*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate*2).putShort(2).putShort(16).put("data".toByteArray()).putInt(audio.size*2)
        audio.forEach {out.putShort((it.coerceIn(-1f,1f)*32767).toInt().toShort())}
        file.writeBytes(out.array())
    }
}

/** Native ONNX pipeline adapted from Resemble AI's MIT-licensed Turbo model contract. */
class Chatterbox(private val folder:File):AutoCloseable {
    private val env=OrtEnvironment.getEnvironment()
    private var embed:OrtSession?=null;private var language:OrtSession?=null;private var decoder:OrtSession?=null
    private var conditionals:List<OnnxTensor>?=null
    private var referenceResult:OrtSession.Result?=null
    private val tokenizer=TurboTokenizer(File(folder,"tokenizer.json"))
    private val owned=mutableListOf<OnnxTensor>()
    private fun session(name:String):OrtSession {
        val options=OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4);setInterOpNumThreads(1);setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(false)
        }
        return options.use {env.createSession(File(folder,"onnx/"+name+"_q4.onnx").absolutePath,it)}
    }
    fun prepare(reference:File) {
        check(conditionals==null)
        val samples=Wav.read(reference).take(24000*15).toFloatArray()
        require(samples.size>=24000*5) {"Voice reference must be at least five seconds."}
        session("speech_encoder").use {encoder->
            OnnxTensor.createTensor(env,FloatBuffer.wrap(samples),longArrayOf(1,samples.size.toLong())).use {input->
                referenceResult=encoder.run(mapOf("audio_values" to input))
                conditionals=(0..3).map {referenceResult!![it] as OnnxTensor}
            }
        }
        embed=session("embed_tokens");language=session("language_model");decoder=session("conditional_decoder")
    }
    fun prepareDefault(bytes:ByteArray) {
        check(conditionals==null)
        val data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic=ByteArray(8);data.get(magic)
        require(String(magic)=="ALPHACB1") {"The bundled voice conditioning is invalid."}
        val tensors=mutableListOf<OnnxTensor>()
        try {
            repeat(4) {i->
                val rank=data.int;require(rank in 2..3)
                val shape=LongArray(rank) {data.long}
                require(shape[0]==1L && shape.all {it in 1..1024})
                val count=shape.fold(1L) {a,b->a*b}.toInt();require(count in 1..500000)
                val length=count*(if(i==1)8 else 4);require(data.remaining()>=length)
                val raw=data.slice().order(ByteOrder.LITTLE_ENDIAN);raw.limit(length)
                tensors+=if(i==1) OnnxTensor.createTensor(env,raw.asLongBuffer(),shape) else OnnxTensor.createTensor(env,raw.asFloatBuffer(),shape)
                data.position(data.position()+length)
            }
            require(!data.hasRemaining())
            conditionals=tensors
            embed=session("embed_tokens");language=session("language_model");decoder=session("conditional_decoder")
        } catch(e:Throwable) {tensors.forEach {it.close()};conditionals=null;throw e}
    }
    private fun floats(values:FloatArray,shape:LongArray)=OnnxTensor.createTensor(env,FloatBuffer.wrap(values),shape)
    private fun longs(values:LongArray,shape:LongArray)=OnnxTensor.createTensor(env,LongBuffer.wrap(values),shape)
    suspend fun generate(text:String,style:String="Expressive",progress:(Int)->Unit={}):FloatArray {
        val embeddings=embed?:error("Prepare the local voice first.")
        val model=language?:error("Local voice model is not loaded.")
        val cond=conditionals?:error("Voice reference is not loaded.")
        var ids=tokenizer.encode(VoiceStyle.normalize(text))
        var total=0;var cacheResult:OrtSession.Result?=null
        val pastNames=model.inputNames.filter {it.startsWith("past_key_values")}.sortedWith(compareBy<String> {it.split('.').getOrNull(1)?.toIntOrNull()?:0}.thenBy {it.substringAfterLast('.')})
        var cache=pastNames.associateWith {name->
            val info=model.inputInfo.getValue(name).info as TensorInfo
            require(info.type==ai.onnxruntime.OnnxJavaType.FLOAT) {"This voice pack requires float32 KV cache."}
            floats(FloatArray(0),longArrayOf(1,16,0,64)).also {owned+=it}
        }
        val generated=mutableListOf<Long>()
        try {
            repeat(400) {step->
                currentCoroutineContext().ensureActive()
                progress(step)
                var nextToken=0L
                longs(ids,longArrayOf(1,ids.size.toLong())).use {tokenInput->
                    embeddings.run(mapOf("input_ids" to tokenInput)).use {eResult->
                        val e=eResult[0] as OnnxTensor
                        val embShape=e.info.shape
                        var inputEmb=e
                        var combined:OnnxTensor?=null
                        var size=embShape[1].toInt()
                        if(step==0) {
                            val prompt=cond[0] as OnnxTensor
                            val values=FloatArray(prompt.floatBuffer.remaining()+e.floatBuffer.remaining())
                            val p=prompt.floatBuffer;val q=e.floatBuffer
                            p.get(values,0,p.remaining());q.get(values,values.size-q.remaining(),q.remaining())
                            size+=(prompt.info.shape[1]).toInt()
                            combined=floats(values,longArrayOf(1,size.toLong(),embShape[2]));inputEmb=combined
                        }
                        val attention=longs(LongArray(total+size){1},longArrayOf(1,(total+size).toLong()))
                        val positions=longs(LongArray(size){total.toLong()+it},longArrayOf(1,size.toLong()))
                        try {
                            val result=model.run(cache+mapOf("inputs_embeds" to inputEmb,"attention_mask" to attention,"position_ids" to positions))
                            val logits=result[0] as OnnxTensor
                            val vocabulary=logits.info.shape.last().toInt()
                            val buffer=logits.floatBuffer
                            buffer.position(buffer.limit()-vocabulary)
                            val scores=FloatArray(vocabulary);buffer.get(scores)
                            (generated+6561L).distinct().forEach {token->if(token in 0 until vocabulary.toLong()) {
                                val i=token.toInt();scores[i]=if(scores[i]<0) scores[i]*1.2f else scores[i]/1.2f
                            }}
                            nextToken=VoiceStyle.sample(scores,style)
                            val nextCache=pastNames.associateWith {name->
                                (result.get(name.replace("past_key_values","present")).orElseThrow {
                                    IllegalStateException("The voice model returned no KV cache for "+name)
                                }) as OnnxTensor
                            }
                            cacheResult?.close();owned.forEach {it.close()};owned.clear()
                            cacheResult=result;cache=nextCache
                            total+=size
                        } finally {combined?.close();attention.close();positions.close()}
                    }
                }
                if(nextToken==6562L) return decode(generated,cond)
                require(nextToken<6561) {"The voice model generated an invalid speech token."}
                generated+=nextToken;ids=longArrayOf(nextToken)
            }
            error("The local voice reached its length limit. Try a shorter sentence.")
        } finally {cacheResult?.close();owned.forEach {it.close()};owned.clear()}
    }
    private fun decode(generated:List<Long>,cond:List<OnnxTensor>):FloatArray {
        require(generated.isNotEmpty()) {"The local voice generated no audio."}
        val prompt=cond[1] as OnnxTensor;val p=prompt.longBuffer
        val ids=LongArray(p.remaining()+generated.size+3);p.get(ids,0,p.remaining())
        generated.forEachIndexed {index,value->ids[ids.size-3-generated.size+index]=value}
        ids[ids.size-3]=4299;ids[ids.size-2]=4299;ids[ids.size-1]=4299
        longs(ids,longArrayOf(1,ids.size.toLong())).use {tokens->
            decoder!!.run(mapOf("speech_tokens" to tokens,"speaker_embeddings" to (cond[2] as OnnxTensor),"speaker_features" to (cond[3] as OnnxTensor))).use {result->
                val buffer=(result[0] as OnnxTensor).floatBuffer
                return FloatArray(buffer.remaining()).also {buffer.get(it)}
            }
        }
    }
    override fun close() {owned.forEach {it.close()};owned.clear();if(referenceResult!=null)referenceResult?.close() else conditionals?.forEach {it.close()};referenceResult=null;conditionals=null;embed?.close();language?.close();decoder?.close();embed=null;language=null;decoder=null}
}
