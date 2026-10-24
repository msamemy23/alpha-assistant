package com.alphainternational.assistant

import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Developer tool, not a product test: writes each style's real kit (theme.css, fonts.css, fonts, motion files) to work/staging/blueprints/kit so templates can be tried in a desktop browser. */
class KitDumpTest {
    @Test fun dumpKits() {
        val root=File("src/main/assets/studio")
        val out=File("../../staging/blueprints/kit")
        val css=File(root,"fonts.css").readText()
        val pairings=JSONObject(File(root,"pairings.json").readText())
        File(root,"packs").listFiles()!!.filter {it.name.endsWith(".json") && it.name!="index.json"}.forEach {file->
            val j=JSONObject(file.readText());val pack=Studio.Pack(j.optString("id"),j.optString("name"),j)
            val dir=File(out,pack.id);dir.mkdirs()
            val pairId=j.optString("fonts")
            val files=pairings.optJSONObject(pairId)?.optJSONArray("files")?.let {a->(0 until a.length()).map {a.getString(it)}}.orEmpty()
            val blocks=Regex("@font-face\\{[^}]*\\}").findAll(css).map {it.value}.filter {b->files.any {f->b.contains("fonts/$f")}}.toList()
            File(dir,"fonts.css").writeText(blocks.joinToString("\n").replace("url(fonts/","url("))
            files.forEach {f->File(root,"fonts/$f").copyTo(File(dir,f),true)}
            val rule=Regex("\\[data-fonts=\""+Regex.escape(pairId)+"\"\\]\\{([^}]*)\\}").find(css)?.groupValues?.get(1).orEmpty()
            File(dir,"theme.css").writeText(Studio.themeCss(pack,rule))
            File(root,"motion.css").copyTo(File(dir,"motion.css"),true)
            File(root,"motion.js").copyTo(File(dir,"motion.js"),true)
        }
        File(root,"lint.js").copyTo(File(out,"../tools/lint.js").also {it.parentFile.mkdirs()},true)
        File(root,"lint-preamble.js").takeIf {it.isFile}?.copyTo(File(out,"../tools/lint-preamble.js"),true)
    }
}
