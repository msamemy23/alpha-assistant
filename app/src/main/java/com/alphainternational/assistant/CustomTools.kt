package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * A tool a bot made for itself (with the owner's OK). [kind] "code" is a JavaScript function body that reads input.<name>
 * and returns an answer; it runs in the offline sandbox. [kind] "web" is an https address on one public site with {name}
 * placeholders, read like web_fetch. Neither can reach accounts, files or passwords.
 */
data class CustomTool(val id:String,val title:String,val description:String,val kind:String,val inputs:List<String>,val code:String,val updated:Long) {
    fun json()=obj("id" to id,"title" to title,"description" to description,"kind" to kind,"inputs" to arr(inputs),"code" to code,"updated" to updated)
    fun summary()=obj("id" to id,"title" to title,"description" to description,"kind" to kind,"inputs" to arr(inputs))
    companion object {
        fun parse(j:JSONObject)=CustomTool(j.getString("id"),j.optString("title"),j.optString("description"),j.optString("kind","code"),
            j.optJSONArray("inputs")?.strings().orEmpty(),j.optString("code"),j.optLong("updated"))
    }
}

object CustomTools {
    private val idPattern=Regex("[a-z][a-z0-9_]{2,39}")
    private val inputPattern=Regex("[a-z][a-z0-9_]{0,29}")
    private val placeholder=Regex("\\{([a-z][a-z0-9_]{0,29})\\}")

    /** Input names from a JSON array or a comma list. */
    fun inputs(raw:String):List<String> {
        val text=raw.trim()
        val names=if(text.startsWith("[")) JSONArray(text).strings() else text.split(',')
        return names.map {it.trim().lowercase()}.filter {it.isNotBlank()}.distinct()
    }

    fun create(id:String,title:String,description:String,kind:String,inputs:List<String>,code:String,reserved:Set<String> = emptySet(),
               now:Long=System.currentTimeMillis()):CustomTool {
        val key=id.trim().lowercase()
        require(key.matches(idPattern)) {"Use a short lowercase tool id with letters, numbers or underscores, like labor_quote."}
        require(key !in reserved) {"That id belongs to a built-in tool. Choose another id."}
        require(title.isNotBlank() && title.length<=60) {"Give the tool a title under 60 characters."}
        require(description.trim().length in 10..400) {"Describe what the tool does and returns in 10 to 400 characters."}
        val type=kind.trim().lowercase()
        require(type in setOf("code","web")) {"A tool's kind is code (a calculator or formatter) or web (a lookup on one public website)."}
        require(inputs.size<=8 && inputs.all {it.matches(inputPattern)}) {"Use up to 8 lowercase input names, like hours or zip_code."}
        val body=code.trim()
        if(type=="code") require(body.length in 1..20_000) {"The tool's code must be 1 to 20,000 characters."}
        else {
            val used=placeholder.findAll(body).map {it.groupValues[1]}.toSet()
            require(inputs.containsAll(used)) {"Every {placeholder} in the address must be one of the tool's inputs."}
            // The site is fixed: placeholders may only change the path or query, never the host.
            val one=Rules.httpsUrl(placeholder.replace(body,"a"));val two=Rules.httpsUrl(placeholder.replace(body,"b"))
            require(URI(one).host==URI(two).host && !URI(body.replace(placeholder,"x")).host.isNullOrBlank()) {"The web address must be on one fixed https site."}
        }
        return CustomTool(key,title.trim(),description.trim(),type,inputs,body,now)
    }

    /** Only declared inputs, as short text or numbers. */
    fun input(tool:CustomTool,raw:String):JSONObject {
        val given=if(raw.isBlank()) JSONObject() else JSONObject(raw)
        val clean=JSONObject()
        tool.inputs.forEach {name->
            if(!given.has(name) || given.isNull(name)) return@forEach
            when(val value=given.get(name)) {
                is Number,is Boolean->clean.put(name,value)
                else->clean.put(name,value.toString().take(2000))
            }
        }
        return clean
    }
    /** The sandbox code: input is a plain object; the tool's body returns the answer. */
    fun script(tool:CustomTool,input:JSONObject)="var input="+input+";\n"+tool.code
    fun url(tool:CustomTool,input:JSONObject):String =
        Rules.httpsUrl(placeholder.replace(tool.code) {m->java.net.URLEncoder.encode(input.opt(m.groupValues[1])?.toString().orEmpty(),"UTF-8").replace("+","%20")})
}
