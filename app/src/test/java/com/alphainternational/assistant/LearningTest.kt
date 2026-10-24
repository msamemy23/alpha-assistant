package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LearningTest {
    private fun m(id:String,kind:String,title:String,text:String,updated:Long=0)=Memory(id,title,text,"You said",updated,"Alpha",kind=kind)

    @Test fun recallAlwaysBringsHowTheOwnerWorksAndWhatMatchesTheRequest() {
        val all=listOf(m("p1","preference","Labor rate","Charges \$120 an hour for labor"),m("h1","habit","Morning estimates","Usually asks for estimates before 9 AM"),
            m("s","summary","Where things stand","Waiting on Maria to approve brakes"),m("f1","person","Maria Lopez","2015 Camry, prefers texts",1),
            m("f2","fact","Supplier","Parts come from O'Reilly on Main",2))+(1..60).map {m("x$it","fact","Old note $it","nothing related $it",3L+it)}
        val picked=Learning.recall(all,"Make Maria an estimate for her Camry")
        val ids=picked.map {it.id}
        assertTrue(ids.containsAll(listOf("p1","h1","s","f1")))
        assertTrue(ids.indexOf("f1")<ids.indexOf("x60"))
        assertTrue(picked.size<=45)
        val text=Learning.describe(picked)
        assertTrue(text.contains("How the owner likes things:") && text.contains("What the owner usually does:") && text.contains("Where things stand:"))
    }
    @Test fun learnedMemoriesAreCheckedBeforeSaving() {
        val reply="""Sure, here you go: {"remember":[
            {"id":"p1","kind":"preference","title":"Text sign-off","text":"Signs texts as Acme Auto Repair","from":"you","private":false},
            {"id":"made-up","kind":"weird","title":"Maria's car","text":"Maria drives a 2015 Camry","from":"reading"},
            {"kind":"fact","title":"Key","text":"api key sk-abcdefghijklmnopqrstuvwxyz123456","from":"reading"},
            {"kind":"habit","title":"","text":"no title"}],
            "forget":["p9","unknown"],"summary":"Estimate for Maria is out; waiting on her OK."}"""
        val result=Learning.parse(reply,setOf("p1","p9"))
        assertEquals(2,result.remember.size)
        assertEquals("p1",result.remember[0].id);assertEquals("preference",result.remember[0].kind);assertEquals("you",result.remember[0].from)
        assertEquals("",result.remember[1].id);assertEquals("fact",result.remember[1].kind);assertEquals("reading",result.remember[1].from)
        assertEquals(listOf("p9"),result.forget)
        assertTrue(result.summary.startsWith("Estimate for Maria"))
        assertTrue(Learning.parse("nothing to learn",emptySet()).remember.isEmpty())
        assertEquals("You said",Learning.source("you"))
    }
    @Test fun stepsSummarizeTheWorkNotTheBookkeeping() {
        val history=JSONArray()
            .put(JSONObject().put("type","function_call").put("call_id","a").put("name","alpha.task_plan").put("arguments","{}"))
            .put(JSONObject().put("type","function_call_output").put("call_id","a").put("output","{}"))
            .put(JSONObject().put("type","function_call").put("call_id","b").put("name","alpha.web_fetch").put("arguments","{}"))
            .put(JSONObject().put("type","function_call_output").put("call_id","b").put("output","{\"title\":\"Alpha Auto\",\"text\":\"Open 8-6\"}"))
        assertEquals(listOf("web fetch: {\"title\":\"Alpha Auto\",\"text\":\"Open 8-6\"}"),Learning.steps(history))
    }
    @Test fun learningCanBeTurnedOff() {
        assertTrue(AiSettings().learn)
        assertFalse(AiSettings.parse(JSONObject(AiSettings(learn=false).json().toString())).learn)
        assertEquals("How you like it",WorkWords.kindLabel("preference"))
        assertTrue(WorkWords.kindOrder("summary")<WorkWords.kindOrder("lesson"))
        assertEquals("preference",Memory.parse(JSONObject(m("a","preference","t","x").json().toString())).kind)
        assertEquals("fact",Memory.parse(JSONObject().put("id","a").put("title","t").put("text","x")).kind)
    }
}
