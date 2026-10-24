package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Real Word, Excel and PowerPoint files, made and read with nothing but a zip file and some XML (no outside library, so the app does not grow and
 * the same code runs in the unit tests). A bot writes a block list or a table list; this turns it into a package that Word, Excel, PowerPoint,
 * Google Docs and the phone's own viewers open. The same reader pulls the words back out of a file the owner shares, so a bot can read it.
 * Pure Kotlin on purpose (nothing from Android), so every rule is tested off the phone.
 */
object Office {
    const val DOCX_MIME="application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX_MIME="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val PPTX_MIME="application/vnd.openxmlformats-officedocument.presentationml.presentation"
    /** Biggest Office file a bot will open (the owner's attachments are capped lower), and the most text it hands back. */
    const val READ_FILE_LIMIT=30_000_000
    const val READ_TEXT_LIMIT=100_000

    /** "word", "excel" or "slides" for an Office file name, else null. These are also the Library kinds. */
    fun kindOf(name:String):String? = when(name.substringAfterLast('.',"").lowercase()) {"docx"->"word";"xlsx"->"excel";"pptx"->"slides";else->null}
    fun isOffice(name:String)=kindOf(name)!=null
    /** "docx", "xlsx" or "pptx" for the matching MIME type (a file shared to Alpha), else null. */
    fun extensionForMime(mime:String):String? = when(mime.trim().lowercase()) {DOCX_MIME->"docx";XLSX_MIME->"xlsx";PPTX_MIME->"pptx";else->null}
    fun mime(name:String):String? = when(kindOf(name)) {"word"->DOCX_MIME;"excel"->XLSX_MIME;"slides"->PPTX_MIME;else->null}
    fun label(kind:String)=when(kind) {"word"->"Word document";"excel"->"Excel spreadsheet";"slides"->"Slide deck";else->kind}

    // ---------------------------------------------------------------- XML helpers

    /** Text with everything XML 1.0 forbids taken out (control characters, lone surrogate halves), and Windows line ends made plain. */
    fun clean(text:String):String {
        val out=StringBuilder(text.length)
        var i=0
        while(i<text.length) {
            val cp=text.codePointAt(i);i+=Character.charCount(cp)
            if(cp==0xD) continue
            if(cp==0x9 || cp==0xA || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF) out.appendCodePoint(cp)
        }
        return out.toString()
    }
    /** Text safe inside an XML element or attribute. */
    fun esc(text:String):String = clean(text).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
    private fun decode(text:String):String {
        if(!text.contains('&')) return text
        return text.replace(Regex("&#x([0-9a-fA-F]{1,6});")) {runCatching {String(Character.toChars(it.groupValues[1].toInt(16)))}.getOrDefault("")}
            .replace(Regex("&#(\\d{1,7});")) {runCatching {String(Character.toChars(it.groupValues[1].toInt()))}.getOrDefault("")}
            .replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&apos;","'").replace("&amp;","&")
    }
    private const val XML_HEAD="<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val NS_W="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val NS_R="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_A="http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val NS_P="http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val NS_S="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_REL="http://schemas.openxmlformats.org/package/2006/relationships"
    private const val NS_TYPES="http://schemas.openxmlformats.org/package/2006/content-types"
    private const val REL_DOC="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private fun utf8(text:String)=text.toByteArray(Charsets.UTF_8)

    /** A zip file from named parts, in the order given ([Content_Types].xml first, as Office writes it). */
    fun zip(parts:List<Pair<String,ByteArray>>,now:Instant=Instant.now()):ByteArray {
        val buffer=ByteArrayOutputStream()
        ZipOutputStream(buffer).use {zip->
            for((name,bytes) in parts) {
                val entry=ZipEntry(name);entry.time=now.toEpochMilli()
                zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }
    private fun rels(vararg items:Triple<String,String,String>):String =
        XML_HEAD+"<Relationships xmlns=\"$NS_REL\">"+items.joinToString("") {(id,type,target)->"<Relationship Id=\"$id\" Type=\"$type\" Target=\"${esc(target)}\"/>"}+"</Relationships>"
    private fun types(defaults:List<Pair<String,String>>,overrides:List<Pair<String,String>>):String =
        XML_HEAD+"<Types xmlns=\"$NS_TYPES\">"+defaults.joinToString("") {(e,t)->"<Default Extension=\"$e\" ContentType=\"$t\"/>"}+
            overrides.joinToString("") {(p,t)->"<Override PartName=\"$p\" ContentType=\"$t\"/>"}+"</Types>"
    private val baseDefaults=listOf("rels" to "application/vnd.openxmlformats-package.relationships+xml","xml" to "application/xml")
    private val coreOverride="/docProps/core.xml" to "application/vnd.openxmlformats-package.core-properties+xml"
    private val appOverride="/docProps/app.xml" to "application/vnd.openxmlformats-officedocument.extended-properties+xml"
    private fun core(title:String,now:Instant):String {
        val stamp=now.truncatedTo(ChronoUnit.SECONDS).toString()
        return XML_HEAD+"<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "+
            "xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"+
            "<dc:title>${esc(title)}</dc:title><dc:creator>Alpha Assistant</dc:creator><dcterms:created xsi:type=\"dcterms:W3CDTF\">$stamp</dcterms:created>"+
            "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">$stamp</dcterms:modified></cp:coreProperties>"
    }
    private val app=XML_HEAD+"<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>Alpha Assistant</Application></Properties>"
    private fun packageRels(main:String):String = rels(Triple("rId1","$REL_DOC/officeDocument",main),
        Triple("rId2","http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties","docProps/core.xml"),
        Triple("rId3","$REL_DOC/extended-properties","docProps/app.xml"))

    // ---------------------------------------------------------------- inline text (bold, italic)

    data class Run(val text:String,val bold:Boolean=false,val italic:Boolean=false)
    private val inlineMarks=Regex("\\*\\*\\*(.+?)\\*\\*\\*|\\*\\*(.+?)\\*\\*|\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*")
    private val markdownLink=Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")
    /** "a **bold** and *italic* word" as runs. Links become "text (address)"; stray asterisks stay as they are. */
    fun runs(text:String):List<Run> {
        val source=markdownLink.replace(text,"$1 ($2)")
        val out=mutableListOf<Run>();var at=0
        for(m in inlineMarks.findAll(source)) {
            if(m.range.first>at) out+=Run(source.substring(at,m.range.first))
            val g=m.groupValues
            out+=when {g[1].isNotEmpty()->Run(g[1],true,true);g[2].isNotEmpty()->Run(g[2],true);else->Run(g[3],italic=true)}
            at=m.range.last+1
        }
        if(at<source.length) out+=Run(source.substring(at))
        return out.ifEmpty {listOf(Run(""))}
    }
    private fun runsFrom(any:Any?):List<Run> = when(any) {
        is JSONArray->(0 until any.length()).flatMap {i->
            val r=any.opt(i)
            if(r is JSONObject) listOf(Run(r.optString("text"),r.optBoolean("bold"),r.optBoolean("italic"))) else runs(r?.toString().orEmpty())
        }
        is JSONObject->listOf(Run(any.optString("text"),any.optBoolean("bold"),any.optBoolean("italic")))
        null,JSONObject.NULL->listOf(Run(""))
        else->runs(any.toString())
    }
    private fun scalar(any:Any?):String = when(any) {
        null,JSONObject.NULL->""
        is Double->numberText(any)
        is Float->numberText(any.toDouble())
        is BigDecimal->any.stripTrailingZeros().toPlainString()
        is JSONObject->any.optString("text").ifBlank {any.optString("value")}
        else->any.toString()
    }
    /** 12 for 12.0, 45.5 for 45.5, never 1.0E7. */
    fun numberText(d:Double):String = if(d==Math.floor(d) && Math.abs(d)<1e15) d.toLong().toString() else BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()

    // ---------------------------------------------------------------- Word

    sealed class WordBlock {
        data class Heading(val level:Int,val text:String):WordBlock()
        data class Para(val runs:List<Run>):WordBlock()
        data class Bullets(val items:List<List<Run>>):WordBlock()
        data class Numbered(val items:List<List<Run>>):WordBlock()
        data class Table(val rows:List<List<String>>,val header:Boolean=true):WordBlock()
        object PageBreak:WordBlock()
    }
    const val MAX_BLOCKS=600
    const val MAX_TEXT=250_000

    /** The blocks a bot sent: a JSON list, or plain Markdown (# headings, - bullets, 1. steps, | tables |) when it did not send JSON. */
    fun wordBlocks(spec:String):List<WordBlock> {
        val t=spec.trim()
        require(t.isNotEmpty()) {"The document has no content."}
        val blocks=when {
            t.startsWith("[")->wordBlocks(JSONArray(t))
            t.startsWith("{")->JSONObject(t).let {o->wordBlocks(o.optJSONArray("blocks")?:JSONArray().put(o))}
            else->markdownBlocks(t)
        }
        require(blocks.isNotEmpty()) {"The document has no content."}
        require(blocks.size<=MAX_BLOCKS) {"Keep a document under $MAX_BLOCKS blocks; make two documents for more."}
        require(blocks.sumOf {textSize(it)}<=MAX_TEXT) {"That document is too long. Split it into two."}
        return blocks
    }
    private fun textSize(b:WordBlock):Int = when(b) {
        is WordBlock.Heading->b.text.length
        is WordBlock.Para->b.runs.sumOf {it.text.length}
        is WordBlock.Bullets->b.items.sumOf {i->i.sumOf {it.text.length}}
        is WordBlock.Numbered->b.items.sumOf {i->i.sumOf {it.text.length}}
        is WordBlock.Table->b.rows.sumOf {r->r.sumOf {it.length}}
        WordBlock.PageBreak->0
    }
    fun wordBlocks(array:JSONArray):List<WordBlock> {
        val out=mutableListOf<WordBlock>()
        for(i in 0 until array.length()) {
            when(val item=array.opt(i)) {
                is String->out+=markdownBlocks(item)
                is JSONObject->out+=wordBlock(item)
                else->{}
            }
        }
        return out
    }
    private fun items(o:JSONObject):List<List<Run>> {
        val list=o.optJSONArray("items")?:o.optJSONArray("bullets")?:o.optJSONArray("list")?:o.optJSONArray("steps")?:JSONArray()
        return (0 until list.length()).map {i->runsFrom(list.opt(i).let {x->if(x is JSONObject) x.opt("runs")?:x.optString("text") else x})}.filter {r->r.any {it.text.isNotBlank()}}
    }
    private fun wordBlock(o:JSONObject):WordBlock {
        val declared=o.optString("type").trim().lowercase().replace('-','_').replace(' ','_')
        val type=declared.ifBlank {when {o.has("heading")->"heading";o.has("rows") || o.has("table")->"table";o.has("items") || o.has("bullets") || o.has("steps")->"bullets"
            o.has("page_break")->"page_break";else->"paragraph"}}
        val text=o.optString("text").ifBlank {o.optString("heading")}.ifBlank {o.optString("paragraph")}
        return when(type) {
            "heading","h1","h2","h3","h4","title","subtitle"-> {
                val level=o.optInt("level",type.drop(1).toIntOrNull()?:if(type=="title") 1 else if(type=="subtitle") 2 else 1)
                WordBlock.Heading(level.coerceIn(1,3),text)
            }
            "paragraph","p","text","para","body"->WordBlock.Para(if(o.has("runs")) runsFrom(o.opt("runs")) else runs(text))
            "bullets","bullet","bulleted","bullet_list","list","ul","unordered"->WordBlock.Bullets(items(o))
            "numbered","numbers","number_list","numbered_list","ol","steps","ordered"->WordBlock.Numbered(items(o))
            "table"-> {
                val rowsJson=o.optJSONArray("rows")?:o.optJSONArray("table")?:JSONArray()
                val rows=(0 until rowsJson.length()).map {r->
                    val row=rowsJson.opt(r)
                    if(row is JSONArray) (0 until row.length()).map {scalar(row.opt(it))} else listOf(scalar(row))
                }
                require(rows.size<=300 && rows.all {it.size<=20}) {"A table can have up to 300 rows and 20 columns."}
                WordBlock.Table(rows,o.optBoolean("header",true))
            }
            "page_break","pagebreak","break"->WordBlock.PageBreak
            else-> {require(text.isNotBlank()) {"Unknown block type \"$declared\". Use heading, paragraph, bullets, numbered, table or page_break."};WordBlock.Para(runs(text))}
        }
    }
    /** Markdown lines as blocks: # headings, - bullets, 1. steps, | tables |, blank lines between paragraphs. */
    fun markdownBlocks(markdown:String):List<WordBlock> {
        val out=mutableListOf<WordBlock>()
        val para=mutableListOf<String>();val bullets=mutableListOf<List<Run>>();val numbers=mutableListOf<List<Run>>();val table=mutableListOf<List<String>>()
        fun flush() {
            if(para.isNotEmpty()) {out+=WordBlock.Para(runs(para.joinToString(" ")));para.clear()}
            if(bullets.isNotEmpty()) {out+=WordBlock.Bullets(bullets.toList());bullets.clear()}
            if(numbers.isNotEmpty()) {out+=WordBlock.Numbered(numbers.toList());numbers.clear()}
            if(table.isNotEmpty()) {out+=WordBlock.Table(table.toList(),true);table.clear()}
        }
        for(raw in markdown.replace("\r\n","\n").split('\n')) {
            val line=raw.trim()
            val h=Regex("^(#{1,6})\\s+(.+)$").find(line)
            val b=Regex("^[-*•]\\s+(.+)$").find(line)
            val n=Regex("^\\d{1,3}[.)]\\s+(.+)$").find(line)
            when {
                line.isEmpty()->flush()
                line.startsWith("|") && line.endsWith("|") && line.length>1-> {
                    val cells=line.trim('|').split('|').map {it.trim()}
                    if(cells.all {it.matches(Regex(":?-{2,}:?"))}) continue
                    if(para.isNotEmpty() || bullets.isNotEmpty() || numbers.isNotEmpty()) flush()
                    table+=cells.map {runs(it).joinToString("") {r->r.text}}
                }
                h!=null-> {flush();out+=WordBlock.Heading(h.groupValues[1].length.coerceAtMost(3),h.groupValues[2].trim().trim('*'))}
                b!=null-> {if(para.isNotEmpty() || numbers.isNotEmpty() || table.isNotEmpty()) flush();bullets+=runs(b.groupValues[1])}
                n!=null-> {if(para.isNotEmpty() || bullets.isNotEmpty() || table.isNotEmpty()) flush();numbers+=runs(n.groupValues[1])}
                line.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$"))->flush()
                else-> {if(bullets.isNotEmpty() || numbers.isNotEmpty() || table.isNotEmpty()) flush();para+=line}
            }
        }
        flush()
        return out
    }

    private fun wRuns(runs:List<Run>,extra:String=""):String = runs.filter {it.text.isNotEmpty()}.joinToString("") {r->
        val props=(if(r.bold) "<w:b/>" else "")+(if(r.italic) "<w:i/>" else "")+extra
        val rPr=if(props.isEmpty()) "" else "<w:rPr>$props</w:rPr>"
        // Line breaks and tabs inside a run are their own elements in Word.
        val pieces=clean(r.text).split('\n').joinToString("<w:br/>") {line->line.split('\t').joinToString("<w:tab/>") {t->if(t.isEmpty()) "" else "<w:t xml:space=\"preserve\">${esc(t)}</w:t>"}}
        "<w:r>$rPr$pieces</w:r>"
    }
    private fun wPara(style:String,runs:List<Run>,numId:Int=0,extra:String=""):String {
        val pPr=(if(style.isEmpty()) "" else "<w:pStyle w:val=\"$style\"/>")+(if(numId>0) "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"$numId\"/></w:numPr>" else "")
        return "<w:p>"+(if(pPr.isEmpty()) "" else "<w:pPr>$pPr</w:pPr>")+wRuns(runs,extra)+"</w:p>"
    }
    private fun wTable(rows:List<List<String>>,header:Boolean):String {
        val columns=(rows.maxOfOrNull {it.size}?:1).coerceAtLeast(1)
        val width=9360/columns
        val borders="<w:tblBorders>"+listOf("top","left","bottom","right","insideH","insideV").joinToString("") {"<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"A6B3BD\"/>"}+"</w:tblBorders>"
        val out=StringBuilder("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"${width*columns}\" w:type=\"dxa\"/>$borders<w:tblLook w:val=\"04A0\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"0\" w:lastColumn=\"0\" w:noHBand=\"0\" w:noVBand=\"1\"/></w:tblPr>")
        out.append("<w:tblGrid>").append("<w:gridCol w:w=\"$width\"/>".repeat(columns)).append("</w:tblGrid>")
        rows.forEachIndexed {r,row->
            val head=header && r==0 && rows.size>1
            out.append("<w:tr>").append(if(head) "<w:trPr><w:tblHeader/></w:trPr>" else "")
            for(c in 0 until columns) {
                val cell=row.getOrElse(c) {""}
                out.append("<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/>").append(if(head) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"E7EEF3\"/>" else "").append("</w:tcPr>")
                out.append(wPara("",runs(cell).let {rs->if(head) rs.map {it.copy(bold=true)} else rs})).append("</w:tc>")
            }
            out.append("</w:tr>")
        }
        // Word wants a paragraph after a table, and two tables with nothing between them would merge into one.
        return out.append("</w:tbl><w:p/>").toString()
    }
    private val wordStyles=XML_HEAD+"<w:styles xmlns:w=\"$NS_W\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>"+
        "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-US\" w:eastAsia=\"en-US\" w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault>"+
        "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"+
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>"+
        "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:uiPriority w:val=\"10\"/><w:qFormat/>"+
        "<w:pPr><w:spacing w:after=\"240\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:rPr><w:b/><w:color w:val=\"1F3A4D\"/><w:sz w:val=\"52\"/><w:szCs w:val=\"52\"/></w:rPr></w:style>"+
        listOf(Triple(1,32,"147658"),Triple(2,28,"1F3A4D"),Triple(3,24,"1F3A4D")).joinToString("") {(n,size,color)->
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$n\"><w:name w:val=\"heading $n\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:uiPriority w:val=\"9\"/><w:qFormat/>"+
                "<w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before=\"${if(n==1) 360 else 240}\" w:after=\"120\"/><w:outlineLvl w:val=\"${n-1}\"/></w:pPr>"+
                "<w:rPr><w:b/><w:color w:val=\"$color\"/><w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/></w:rPr></w:style>"}+
        "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:uiPriority w:val=\"34\"/><w:qFormat/>"+
        "<w:pPr><w:spacing w:after=\"60\"/><w:ind w:left=\"720\"/></w:pPr></w:style>"+
        "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:uiPriority w:val=\"99\"/><w:semiHidden/><w:unhideWhenUsed/>"+
        "<w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>"+
        "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:basedOn w:val=\"TableNormal\"/><w:uiPriority w:val=\"39\"/>"+
        "<w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:style></w:styles>"
    private fun numbering(lists:Int):String {
        val level="<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"%s\"/><w:lvlText w:val=\"%s\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl>"
        return XML_HEAD+"<w:numbering xmlns:w=\"$NS_W\">"+
            "<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>"+level.format("bullet","\u2022")+"</w:abstractNum>"+
            "<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>"+level.format("decimal","%1.")+"</w:abstractNum>"+
            "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>"+
            (0 until lists).joinToString("") {"<w:num w:numId=\"${it+2}\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>"}+"</w:numbering>"
    }

    /** A .docx file: [title] on top, then the blocks. */
    fun docx(title:String,blocks:List<WordBlock>,now:Instant=Instant.now()):ByteArray {
        val body=StringBuilder();var lists=0
        if(title.isNotBlank()) body.append(wPara("Title",listOf(Run(title.trim()))))
        for(b in blocks) when(b) {
            is WordBlock.Heading->body.append(wPara("Heading${b.level}",runs(b.text)))
            is WordBlock.Para->body.append(wPara("",b.runs))
            is WordBlock.Bullets->b.items.forEach {body.append(wPara("ListParagraph",it,numId=1))}
            is WordBlock.Numbered-> {val id=2+lists++;b.items.forEach {body.append(wPara("ListParagraph",it,numId=id))}}
            is WordBlock.Table->body.append(wTable(b.rows,b.header))
            WordBlock.PageBreak->body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        }
        if(body.isEmpty()) body.append("<w:p/>")
        val document=XML_HEAD+"<w:document xmlns:w=\"$NS_W\" xmlns:r=\"$NS_R\"><w:body>$body"+
            "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
        return zip(listOf(
            "[Content_Types].xml" to utf8(types(baseDefaults,listOf("/word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
                "/word/styles.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml",
                "/word/numbering.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml",coreOverride,appOverride))),
            "_rels/.rels" to utf8(packageRels("word/document.xml")),
            "word/_rels/document.xml.rels" to utf8(rels(Triple("rId1","$REL_DOC/styles","styles.xml"),Triple("rId2","$REL_DOC/numbering","numbering.xml"))),
            "word/document.xml" to utf8(document),
            "word/styles.xml" to utf8(wordStyles),
            "word/numbering.xml" to utf8(numbering(lists)),
            "docProps/core.xml" to utf8(core(title,now)),
            "docProps/app.xml" to utf8(app)),now)
    }

    // ---------------------------------------------------------------- Excel

    /** A formula typed as text ("=SUM(B2:B4)"). */
    data class Formula(val text:String)
    class Sheet(val name:String,val rows:List<List<Any?>>,val header:Boolean=true)
    const val MAX_SHEETS=20
    const val MAX_ROWS=5_000
    const val MAX_COLUMNS=100

    /** One cell from whatever JSON gave: numbers stay numbers, "=SUM(A1:A3)" is a formula, "0123" stays text, blank is an empty cell. */
    fun cellValue(x:Any?):Any? = when(x) {
        null,JSONObject.NULL->null
        is Boolean->x
        is Int->x.toDouble()
        is Long->x.toDouble()
        is Number->x.toDouble().takeIf {it.isFinite()}?:x.toString()
        is String-> {
            val s=clean(x)
            when {
                s.isBlank()->null
                s.startsWith("=") && s.length>1->Formula(s.substring(1).trim())
                s.trim().matches(Regex("-?(0|[1-9]\\d{0,14})(\\.\\d+)?"))->s.trim().toDouble()
                else->s.take(32_000)
            }
        }
        else->clean(x.toString()).take(32_000)
    }
    /** The sheets a bot sent as JSON: [{"name":"Sales","header":true,"rows":[["Item","Qty"],["Brakes",2]]}]. A bare list of rows is one sheet. */
    fun sheets(spec:String):List<Sheet> {
        val t=spec.trim()
        require(t.isNotEmpty()) {"The spreadsheet has no content."}
        val array=when {
            t.startsWith("[") && JSONArray(t).let {a->a.length()>0 && a.opt(0) is JSONArray}->JSONArray().put(JSONObject().put("name","Sheet1").put("rows",JSONArray(t)))
            t.startsWith("[")->JSONArray(t)
            t.startsWith("{")->JSONObject(t).let {o->o.optJSONArray("sheets")?:JSONArray().put(o)}
            else->throw IllegalArgumentException("Send the sheets as JSON: [{\"name\":\"Sales\",\"rows\":[[\"Item\",\"Qty\"],[\"Brakes\",2]]}].")
        }
        require(array.length() in 1..MAX_SHEETS) {"A workbook can have 1 to $MAX_SHEETS sheets."}
        val names=mutableSetOf<String>()
        return (0 until array.length()).map {i->
            val o=array.optJSONObject(i)?:throw IllegalArgumentException("Each sheet is an object with a name and rows.")
            val rowsJson=o.optJSONArray("rows")?:JSONArray()
            require(rowsJson.length()<=MAX_ROWS) {"A sheet can have up to $MAX_ROWS rows."}
            val rows=(0 until rowsJson.length()).map {r->
                val row=rowsJson.opt(r)
                val cells=if(row is JSONArray) (0 until row.length()).map {cellValue(row.opt(it))} else listOf(cellValue(row))
                require(cells.size<=MAX_COLUMNS) {"A sheet can have up to $MAX_COLUMNS columns."}
                cells
            }
            Sheet(sheetName(o.optString("name"),i,names),rows,o.optBoolean("header",true))
        }
    }
    /** A sheet name Excel accepts: 31 characters at most, none of []:*?/\, not blank, and different from the other sheets' names. */
    fun sheetName(raw:String,index:Int,taken:MutableSet<String>):String {
        var name=clean(raw).replace(Regex("[\\[\\]:*?/\\\\]")," ").replace(Regex("\\s+")," ").trim().trim('\'').take(31).trim().ifEmpty {"Sheet${index+1}"}
        var n=2
        while(!taken.add(name.lowercase())) {val tail=" "+n++;name=name.take(31-tail.length).trimEnd()+tail}
        return name
    }
    /** Column letters for a zero-based column: 0 is A, 26 is AA. */
    fun columnName(index:Int):String {
        var n=index;val out=StringBuilder()
        do {out.insert(0,'A'+(n%26));n=n/26-1} while(n>=0)
        return out.toString()
    }
    private fun columnIndex(letters:String):Int = letters.uppercase().fold(0) {acc,c->acc*26+(c-'A'+1)}-1

    /** Works out simple formulas (SUM, AVERAGE, MIN, MAX, COUNT, ROUND, ABS, + - * / ^ and brackets, on cells of the same sheet) so the file shows numbers even in viewers that do not calculate. */
    class Calc(private val grid:List<List<Any?>>) {
        private val memo=HashMap<Long,Double?>();private val busy=HashSet<Long>()
        private fun key(r:Int,c:Int)=r.toLong()*1_000+c
        /** The number in a cell; a formula is worked out; text and empty are null. */
        fun at(r:Int,c:Int):Double? {
            val x=grid.getOrNull(r)?.getOrNull(c)
            return when(x) {
                is Double->x
                is Formula-> {
                    val k=key(r,c)
                    if(k in memo) memo[k] else if(!busy.add(k)) null else try {memo[k]=evaluate(x.text);memo[k]} finally {busy.remove(k)}
                }
                else->null
            }
        }
        fun evaluate(formula:String):Double? = try {Parser(formula).run()?.takeIf {it.isFinite()}} catch(_:Exception) {null}
        private inner class Parser(val s:String) {
            var i=0
            fun run():Double? {val v=expr();ws();return if(i==s.length) v else null}
            fun ws() {while(i<s.length && s[i]==' ') i++}
            fun expr():Double {
                var v=term()
                while(true) {ws();if(i<s.length && (s[i]=='+' || s[i]=='-')) {val op=s[i++];val r=term();v=if(op=='+') v+r else v-r} else return v}
            }
            fun term():Double {
                var v=power()
                while(true) {ws();if(i<s.length && (s[i]=='*' || s[i]=='/')) {val op=s[i++];val r=power();if(op=='/'){if(r==0.0) throw ArithmeticException();v/=r} else v*=r} else return v}
            }
            fun power():Double {val b=unary();ws();if(i<s.length && s[i]=='^') {i++;return Math.pow(b,power())};return b}
            fun unary():Double {ws();if(i<s.length && s[i]=='-') {i++;return -unary()};if(i<s.length && s[i]=='+') {i++;return unary()};return atom()}
            fun atom():Double {
                ws();if(i>=s.length) throw IllegalStateException()
                if(s[i]=='(') {i++;val v=expr();ws();check(i<s.length && s[i]==')');i++;return v}
                val number=Regex("\\d+(\\.\\d+)?|\\.\\d+").matchAt(s,i)
                if(number!=null) {i=number.range.last+1;return number.value.toDouble()}
                val name=Regex("[A-Za-z_$][A-Za-z0-9_$.]*").matchAt(s,i)?:throw IllegalStateException()
                i=name.range.last+1
                ws()
                if(i<s.length && s[i]=='(') {i++;return call(name.value.uppercase())}
                val ref=Regex("\\$?([A-Za-z]{1,3})\\$?(\\d{1,5})").matchEntire(name.value)?:throw IllegalStateException()
                return value(ref.groupValues[2].toInt()-1,columnIndex(ref.groupValues[1]))
            }
            fun value(r:Int,c:Int):Double {
                val x=grid.getOrNull(r)?.getOrNull(c)
                return if(x==null) 0.0 else at(r,c)?:throw IllegalStateException()
            }
            fun call(fn:String):Double {
                val values=mutableListOf<Double>();var scalars=0
                ws()
                if(i<s.length && s[i]==')') i++ else while(true) {
                    ws()
                    val range=Regex("\\$?([A-Za-z]{1,3})\\$?(\\d{1,5}):\\$?([A-Za-z]{1,3})\\$?(\\d{1,5})").matchAt(s,i)
                    if(range!=null) {
                        i=range.range.last+1
                        val g=range.groupValues
                        val r1=minOf(g[2].toInt(),g[4].toInt())-1;val r2=maxOf(g[2].toInt(),g[4].toInt())-1
                        val c1=minOf(columnIndex(g[1]),columnIndex(g[3]));val c2=maxOf(columnIndex(g[1]),columnIndex(g[3]))
                        check((r2-r1+1).toLong()*(c2-c1+1)<=100_000)
                        for(r in r1..r2) for(c in c1..c2) at(r,c)?.let {values+=it}
                    } else {values+=expr();scalars++}
                    ws();check(i<s.length)
                    if(s[i]==',') {i++;continue}
                    check(s[i]==')');i++;break
                }
                return when(fn) {
                    "SUM"->values.sum()
                    "AVERAGE","AVG"->{check(values.isNotEmpty());values.average()}
                    "MIN"->{check(values.isNotEmpty());values.min()}
                    "MAX"->{check(values.isNotEmpty());values.max()}
                    "COUNT"->values.size.toDouble()
                    "ABS"->{check(values.size==1);Math.abs(values[0])}
                    "ROUND"->{check(values.size==2);BigDecimal.valueOf(values[0]).setScale(values[1].toInt(),java.math.RoundingMode.HALF_UP).toDouble()}
                    else->throw IllegalStateException()
                }
            }
        }
    }

    private val sheetTypes="application/vnd.openxmlformats-officedocument.spreadsheetml"
    private val excelStyles=XML_HEAD+"<styleSheet xmlns=\"$NS_S\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"+
        "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font></fonts>"+
        "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"+
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE7EEF3\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"+
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"+
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"+
        "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"+
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/></cellXfs>"+
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"

    private fun sheetXml(sheet:Sheet,first:Boolean):String {
        val calc=Calc(sheet.rows)
        val widths=IntArray(sheet.rows.maxOfOrNull {it.size}?:0)
        val data=StringBuilder()
        sheet.rows.forEachIndexed {r,row->
            if(row.all {it==null}) return@forEachIndexed
            data.append("<row r=\"${r+1}\">")
            row.forEachIndexed {c,x->
                if(x==null) return@forEachIndexed
                val ref=columnName(c)+(r+1)
                val style=if(sheet.header && r==0 && sheet.rows.size>1) " s=\"1\"" else ""
                val shown:String
                when(x) {
                    is Double-> {shown=numberText(x);data.append("<c r=\"$ref\"$style><v>$shown</v></c>")}
                    is Boolean-> {shown=if(x) "TRUE" else "FALSE";data.append("<c r=\"$ref\"$style t=\"b\"><v>${if(x) 1 else 0}</v></c>")}
                    is Formula-> {
                        val v=calc.at(r,c)
                        shown=v?.let {numberText(it)}?:"=${x.text}"
                        data.append("<c r=\"$ref\"$style><f>${esc(x.text)}</f>").append(if(v==null) "" else "<v>${numberText(v)}</v>").append("</c>")
                    }
                    else-> {shown=x.toString();data.append("<c r=\"$ref\"$style t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(shown)}</t></is></c>")}
                }
                widths[c]=maxOf(widths[c],shown.lines().maxOfOrNull {it.length}?:0)
            }
            data.append("</row>")
        }
        val columns=widths.size
        val cols=if(columns==0) "" else "<cols>"+widths.mapIndexed {c,w->"<col min=\"${c+1}\" max=\"${c+1}\" width=\"${(w+2).coerceIn(8,60)}\" customWidth=\"1\"/>"}.joinToString("")+"</cols>"
        val freeze=if(sheet.header && sheet.rows.size>1) "<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/><selection pane=\"bottomLeft\"/>" else ""
        val last=if(sheet.rows.isEmpty() || columns==0) "A1" else columnName(columns-1)+sheet.rows.size
        return XML_HEAD+"<worksheet xmlns=\"$NS_S\" xmlns:r=\"$NS_R\"><dimension ref=\"A1:$last\"/>"+
            "<sheetViews><sheetView workbookViewId=\"0\"${if(first) " tabSelected=\"1\"" else ""}>$freeze</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/>$cols"+
            (if(data.isEmpty()) "<sheetData/>" else "<sheetData>$data</sheetData>")+"</worksheet>"
    }

    /** An .xlsx file. An empty list of sheets still makes one blank sheet, since a workbook needs at least one. */
    fun xlsx(sheets:List<Sheet>,now:Instant=Instant.now()):ByteArray {
        val all=sheets.ifEmpty {listOf(Sheet("Sheet1",emptyList()))}
        val workbook=XML_HEAD+"<workbook xmlns=\"$NS_S\" xmlns:r=\"$NS_R\"><bookViews><workbookView/></bookViews><sheets>"+
            all.mapIndexed {i,s->"<sheet name=\"${esc(s.name)}\" sheetId=\"${i+1}\" r:id=\"rId${i+1}\"/>"}.joinToString("")+"</sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>"
        val parts=mutableListOf<Pair<String,ByteArray>>()
        parts+="[Content_Types].xml" to utf8(types(baseDefaults,listOf("/xl/workbook.xml" to "$sheetTypes.sheet.main+xml")+
            all.indices.map {"/xl/worksheets/sheet${it+1}.xml" to "$sheetTypes.worksheet+xml"}+listOf("/xl/styles.xml" to "$sheetTypes.styles+xml",coreOverride,appOverride)))
        parts+="_rels/.rels" to utf8(packageRels("xl/workbook.xml"))
        parts+="xl/workbook.xml" to utf8(workbook)
        parts+="xl/_rels/workbook.xml.rels" to utf8(rels(*(all.indices.map {Triple("rId${it+1}","$REL_DOC/worksheet","worksheets/sheet${it+1}.xml")}+
            Triple("rId${all.size+1}","$REL_DOC/styles","styles.xml")).toTypedArray()))
        parts+="xl/styles.xml" to utf8(excelStyles)
        all.forEachIndexed {i,s->parts+="xl/worksheets/sheet${i+1}.xml" to utf8(sheetXml(s,i==0))}
        parts+="docProps/core.xml" to utf8(core(all.first().name,now))
        parts+="docProps/app.xml" to utf8(app)
        return zip(parts,now)
    }

    // ---------------------------------------------------------------- PowerPoint

    /** One slide. [image] is a ready jpg or png (already shrunk by the caller); [subtitle] is only used on a slide with no bullets. */
    class Slide(val title:String,val bullets:List<String> = emptyList(),val notes:String="",val subtitle:String="",val image:SlideImage?=null)
    class SlideImage(val ext:String,val bytes:ByteArray,val alt:String="")
    const val MAX_SLIDES=60

    /** The slides a bot sent as JSON: [{"title":"…","bullets":["…"],"notes":"…","image":"photo.jpg"}]. [loadImage] turns an image file name into a SlideImage (or throws a plain sentence). */
    fun slides(spec:String,loadImage:(String)->SlideImage? = {null}):List<Slide> {
        val t=spec.trim()
        require(t.isNotEmpty()) {"The deck has no slides."}
        val array=when {t.startsWith("[")->JSONArray(t);t.startsWith("{")->JSONObject(t).let {o->o.optJSONArray("slides")?:JSONArray().put(o)};else->throw IllegalArgumentException("Send the slides as JSON: [{\"title\":\"…\",\"bullets\":[\"…\"],\"notes\":\"…\"}].")}
        require(array.length() in 1..MAX_SLIDES) {"A deck can have 1 to $MAX_SLIDES slides."}
        return (0 until array.length()).map {i->
            val o=array.optJSONObject(i)?:throw IllegalArgumentException("Each slide is an object with a title and bullets.")
            val bullets=o.optJSONArray("bullets")?.let {b->(0 until b.length()).map {k->scalar(b.opt(k))}}?:o.optString("bullets").lines()
            val image=o.optString("image").trim().takeIf {it.isNotEmpty()}?.let {name->loadImage(name)?.let {im->if(im.alt.isBlank()) SlideImage(im.ext,im.bytes,o.optString("title")) else im}}
            Slide(o.optString("title").trim().take(200),bullets.map {it.trimEnd()}.filter {it.isNotBlank()}.take(14),o.optString("notes").trim().take(8_000),o.optString("subtitle").trim().take(300),image)
        }
    }
    /** Width and height of a PNG or JPEG from its first bytes, or null when it is neither. */
    fun imageSize(bytes:ByteArray):Pair<Int,Int>? {
        fun u16(i:Int)=((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i+1].toInt() and 0xFF)
        fun u32(i:Int)=(u16(i) shl 16) or u16(i+2)
        if(bytes.size>=24 && bytes[0]==0x89.toByte() && bytes[1]=='P'.code.toByte() && bytes[2]=='N'.code.toByte() && bytes[3]=='G'.code.toByte()) return u32(16) to u32(20)
        if(bytes.size>=4 && bytes[0]==0xFF.toByte() && bytes[1]==0xD8.toByte()) {
            var i=2
            while(i+9<bytes.size) {
                if(bytes[i]!=0xFF.toByte()) {i++;continue}
                val marker=bytes[i+1].toInt() and 0xFF
                if(marker==0xFF) {i++;continue}
                if(marker in 0xC0..0xCF && marker !in setOf(0xC4,0xC8,0xCC)) return u16(i+7) to u16(i+5)
                if(marker==0xD8 || marker==0x01 || marker in 0xD0..0xD7) {i+=2;continue}
                i+=2+u16(i+2)
            }
        }
        return null
    }
    private const val EMU_W=12192000
    private const val EMU_H=6858000
    private fun grp()="<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
    private fun ph(id:Int,name:String,phAttrs:String,x:Int?=null,y:Int?=null,cx:Int?=null,cy:Int?=null,body:String="<a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p>"):String =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph $phAttrs/></p:nvPr></p:nvSpPr>"+
            (if(x==null) "<p:spPr/>" else "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm></p:spPr>")+"<p:txBody>$body</p:txBody></p:sp>"
    private fun aParas(lines:List<String>,size:Int?):String = lines.joinToString("") {raw->
        val level=if(raw.startsWith("  ") || raw.startsWith("\t")) 1 else 0
        val text=raw.trimStart().removePrefix("- ").removePrefix("* ").removePrefix("• ").trim()
        val rPr="<a:rPr lang=\"en-US\"${if(size!=null) " sz=\"$size\"" else ""} dirty=\"0\"/>"
        "<a:p>${if(level==1) "<a:pPr lvl=\"1\"/>" else ""}"+runs(text).filter {it.text.isNotEmpty()}.joinToString("") {r->
            val props="<a:rPr lang=\"en-US\"${if(size!=null) " sz=\"$size\"" else ""}${if(r.bold) " b=\"1\"" else ""}${if(r.italic) " i=\"1\"" else ""} dirty=\"0\"/>"
            "<a:r>$props<a:t>${esc(r.text)}</a:t></a:r>"}.ifEmpty {"<a:r>$rPr<a:t></a:t></a:r>"}+"</a:p>"
    }
    private fun slideXml(s:Slide,hasImage:Boolean):String {
        val tree=StringBuilder(grp())
        val titleOnly=s.bullets.isEmpty() && !hasImage
        if(titleOnly) {
            tree.append(ph(2,"Title 1","type=\"ctrTitle\"",body="<a:bodyPr/><a:lstStyle/>"+aParas(listOf(s.title),null)))
            tree.append(ph(3,"Subtitle 2","type=\"subTitle\" idx=\"1\"",body="<a:bodyPr/><a:lstStyle/>"+aParas(listOf(s.subtitle),null)))
        } else {
            tree.append(ph(2,"Title 1","type=\"title\"",body="<a:bodyPr/><a:lstStyle/>"+aParas(listOf(s.title),null)))
            val size=when {s.bullets.size>9->1800;s.bullets.size>6->2000;else->null}
            val frame=if(hasImage) intArrayOf(609600,1600200,5791200,4525963) else null
            tree.append(ph(3,"Content Placeholder 2","idx=\"1\"",frame?.get(0),frame?.get(1),frame?.get(2),frame?.get(3),"<a:bodyPr><a:normAutofit/></a:bodyPr><a:lstStyle/>"+aParas(s.bullets.ifEmpty {listOf("")},size)))
        }
        if(hasImage && s.image!=null) {
            val (w,h)=imageSize(s.image.bytes)?:(4 to 3)
            val boxW=5000000;val boxH=4525963
            val scale=minOf(boxW.toDouble()/w,boxH.toDouble()/h)
            val cx=(w*scale).toInt().coerceAtLeast(1);val cy=(h*scale).toInt().coerceAtLeast(1)
            val x=6400800+(boxW-cx)/2;val y=1600200+(boxH-cy)/2
            tree.append("<p:pic><p:nvPicPr><p:cNvPr id=\"4\" name=\"Picture 3\" descr=\"${esc(s.image.alt)}\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>"+
                "<p:blipFill><a:blip r:embed=\"rId3\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>"+
                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>")
        }
        return XML_HEAD+"<p:sld xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\"><p:cSld><p:spTree>$tree</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }
    private fun notesXml(text:String):String {
        val body=text.replace("\r\n","\n").split('\n').joinToString("") {line->"<a:p><a:r><a:rPr lang=\"en-US\" dirty=\"0\"/><a:t>${esc(line)}</a:t></a:r></a:p>"}
        return XML_HEAD+"<p:notes xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\"><p:cSld><p:spTree>${grp()}"+
            "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Slide Image Placeholder 1\"/><p:cNvSpPr><a:spLocks noGrp=\"1\" noRot=\"1\" noChangeAspect=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"sldImg\"/></p:nvPr></p:nvSpPr><p:spPr/></p:sp>"+
            "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Notes Placeholder 2\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr/>"+
            "<p:txBody><a:bodyPr/><a:lstStyle/>$body</p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:notes>"
    }
    private val theme=XML_HEAD+"<a:theme xmlns:a=\"$NS_A\" name=\"Alpha\"><a:themeElements><a:clrScheme name=\"Alpha\">"+
        "<a:dk1><a:srgbClr val=\"1B2430\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1><a:dk2><a:srgbClr val=\"1F3A4D\"/></a:dk2><a:lt2><a:srgbClr val=\"EEF3F6\"/></a:lt2>"+
        "<a:accent1><a:srgbClr val=\"147658\"/></a:accent1><a:accent2><a:srgbClr val=\"2F6DB5\"/></a:accent2><a:accent3><a:srgbClr val=\"E0A100\"/></a:accent3>"+
        "<a:accent4><a:srgbClr val=\"C2412D\"/></a:accent4><a:accent5><a:srgbClr val=\"7B4FA3\"/></a:accent5><a:accent6><a:srgbClr val=\"2A9BB5\"/></a:accent6>"+
        "<a:hlink><a:srgbClr val=\"2F6DB5\"/></a:hlink><a:folHlink><a:srgbClr val=\"7B4FA3\"/></a:folHlink></a:clrScheme>"+
        "<a:fontScheme name=\"Alpha\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>"+
        "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>"+
        "<a:fmtScheme name=\"Alpha\"><a:fillStyleLst>"+"<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>".repeat(3)+"</a:fillStyleLst><a:lnStyleLst>"+
        "<a:ln w=\"6350\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln>".repeat(3)+
        "</a:lnStyleLst><a:effectStyleLst>"+"<a:effectStyle><a:effectLst/></a:effectStyle>".repeat(3)+"</a:effectStyleLst><a:bgFillStyleLst>"+
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>".repeat(3)+"</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"
    private fun level(n:Int,marL:Int,indent:Int,size:Int,bullet:String)="<a:lvl${n}pPr marL=\"$marL\" indent=\"$indent\" algn=\"l\" defTabSz=\"914400\" rtl=\"0\" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\">"+
        "<a:lnSpc><a:spcPct val=\"100000\"/></a:lnSpc><a:spcBef><a:spcPts val=\"1000\"/></a:spcBef><a:buFont typeface=\"Arial\"/><a:buChar char=\"$bullet\"/>"+
        "<a:defRPr sz=\"$size\" kern=\"1200\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill><a:latin typeface=\"+mn-lt\"/><a:ea typeface=\"+mn-ea\"/><a:cs typeface=\"+mn-cs\"/></a:defRPr></a:lvl${n}pPr>"
    private val master=XML_HEAD+"<p:sldMaster xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\"><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>${grp()}"+
        "<p:sp><p:nvSpPr><p:cNvPr id=\"4\" name=\"Accent bar\"/><p:cNvSpPr/><p:nvPr userDrawn=\"1\"/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$EMU_W\" cy=\"137160\"/></a:xfrm>"+
        "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:schemeClr val=\"accent1\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr></p:sp>"+
        "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title Placeholder 1\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr>"+
        "<p:spPr><a:xfrm><a:off x=\"609600\" y=\"365125\"/><a:ext cx=\"10972800\" cy=\"1143000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>"+
        "<p:txBody><a:bodyPr vert=\"horz\" lIns=\"91440\" tIns=\"45720\" rIns=\"91440\" bIns=\"45720\" rtlCol=\"0\" anchor=\"ctr\"><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\"/><a:t>Click to edit the title</a:t></a:r></a:p></p:txBody></p:sp>"+
        "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Text Placeholder 2\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr>"+
        "<p:spPr><a:xfrm><a:off x=\"609600\" y=\"1600200\"/><a:ext cx=\"10972800\" cy=\"4525963\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>"+
        "<p:txBody><a:bodyPr vert=\"horz\" lIns=\"91440\" tIns=\"45720\" rIns=\"91440\" bIns=\"45720\" rtlCol=\"0\"><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p><a:pPr lvl=\"0\"/><a:r><a:rPr lang=\"en-US\"/><a:t>Click to edit the text</a:t></a:r></a:p></p:txBody></p:sp>"+
        "</p:spTree></p:cSld><p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"+
        "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/><p:sldLayoutId id=\"2147483650\" r:id=\"rId2\"/></p:sldLayoutIdLst>"+
        "<p:txStyles><p:titleStyle><a:lvl1pPr algn=\"l\" defTabSz=\"914400\" rtl=\"0\" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\"><a:lnSpc><a:spcPct val=\"90000\"/></a:lnSpc><a:spcBef><a:spcPct val=\"0\"/></a:spcBef><a:buNone/>"+
        "<a:defRPr sz=\"3600\" b=\"1\" kern=\"1200\"><a:solidFill><a:schemeClr val=\"tx2\"/></a:solidFill><a:latin typeface=\"+mj-lt\"/><a:ea typeface=\"+mj-ea\"/><a:cs typeface=\"+mj-cs\"/></a:defRPr></a:lvl1pPr></p:titleStyle>"+
        "<p:bodyStyle>"+level(1,342900,-342900,2400,"\u2022")+level(2,742950,-285750,2000,"\u2013")+level(3,1143000,-228600,1800,"\u2022")+"</p:bodyStyle>"+
        "<p:otherStyle><a:defPPr><a:defRPr lang=\"en-US\"/></a:defPPr></p:otherStyle></p:txStyles></p:sldMaster>"
    private val layoutTitle=XML_HEAD+"<p:sldLayout xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\" type=\"title\" preserve=\"1\"><p:cSld name=\"Title Slide\"><p:spTree>${grp()}"+
        "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title 1\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"ctrTitle\"/></p:nvPr></p:nvSpPr>"+
        "<p:spPr><a:xfrm><a:off x=\"1524000\" y=\"1805000\"/><a:ext cx=\"9144000\" cy=\"1500000\"/></a:xfrm></p:spPr><p:txBody><a:bodyPr anchor=\"b\"><a:normAutofit/></a:bodyPr><a:lstStyle><a:lvl1pPr algn=\"ctr\"><a:defRPr sz=\"4800\"/></a:lvl1pPr></a:lstStyle>"+
        "<a:p><a:r><a:rPr lang=\"en-US\"/><a:t>Click to edit the title</a:t></a:r></a:p></p:txBody></p:sp>"+
        "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Subtitle 2\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"subTitle\" idx=\"1\"/></p:nvPr></p:nvSpPr>"+
        "<p:spPr><a:xfrm><a:off x=\"1524000\" y=\"3429000\"/><a:ext cx=\"9144000\" cy=\"1200000\"/></a:xfrm></p:spPr><p:txBody><a:bodyPr><a:normAutofit/></a:bodyPr><a:lstStyle><a:lvl1pPr marL=\"0\" indent=\"0\" algn=\"ctr\"><a:buNone/><a:defRPr sz=\"2400\"/></a:lvl1pPr></a:lstStyle>"+
        "<a:p><a:r><a:rPr lang=\"en-US\"/><a:t>Click to edit the subtitle</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"
    private val layoutContent=XML_HEAD+"<p:sldLayout xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\" type=\"obj\" preserve=\"1\"><p:cSld name=\"Title and Content\"><p:spTree>${grp()}"+
        "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title 1\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/>"+
        "<a:p><a:r><a:rPr lang=\"en-US\"/><a:t>Click to edit the title</a:t></a:r></a:p></p:txBody></p:sp>"+
        "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Content Placeholder 2\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/>"+
        "<a:p><a:pPr lvl=\"0\"/><a:r><a:rPr lang=\"en-US\"/><a:t>Click to edit the text</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"
    private val notesMaster=XML_HEAD+"<p:notesMaster xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\"><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>${grp()}"+
        "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Slide Image Placeholder 1\"/><p:cNvSpPr><a:spLocks noGrp=\"1\" noRot=\"1\" noChangeAspect=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"sldImg\" idx=\"2\"/></p:nvPr></p:nvSpPr>"+
        "<p:spPr><a:xfrm><a:off x=\"685800\" y=\"1143000\"/><a:ext cx=\"5486400\" cy=\"3086100\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln w=\"12700\"><a:solidFill><a:prstClr val=\"black\"/></a:solidFill></a:ln></p:spPr></p:sp>"+
        "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Notes Placeholder 2\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"body\" sz=\"quarter\" idx=\"3\"/></p:nvPr></p:nvSpPr>"+
        "<p:spPr><a:xfrm><a:off x=\"685800\" y=\"4400550\"/><a:ext cx=\"5486400\" cy=\"3600450\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>"+
        "<p:txBody><a:bodyPr vert=\"horz\" lIns=\"91440\" tIns=\"45720\" rIns=\"91440\" bIns=\"45720\" rtlCol=\"0\"/><a:lstStyle/><a:p><a:pPr lvl=\"0\"/><a:r><a:rPr lang=\"en-US\"/><a:t>Click to edit the notes</a:t></a:r></a:p></p:txBody></p:sp>"+
        "</p:spTree></p:cSld><p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"+
        "<p:notesStyle><a:lvl1pPr marL=\"0\" algn=\"l\" defTabSz=\"914400\" rtl=\"0\" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\"><a:defRPr sz=\"1200\" kern=\"1200\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill>"+
        "<a:latin typeface=\"+mn-lt\"/><a:ea typeface=\"+mn-ea\"/><a:cs typeface=\"+mn-cs\"/></a:defRPr></a:lvl1pPr></p:notesStyle></p:notesMaster>"

    /** A .pptx file: a 16:9 deck, a title slide for slides with no bullets and no picture, notes where given, and pictures at the right. */
    fun pptx(deckTitle:String,slides:List<Slide>,now:Instant=Instant.now()):ByteArray {
        require(slides.isNotEmpty()) {"The deck has no slides."}
        val p="application/vnd.openxmlformats-officedocument.presentationml"
        val parts=mutableListOf<Pair<String,ByteArray>>()
        val extensions=slides.mapNotNull {it.image?.ext?.lowercase()}.map {if(it=="jpeg") "jpg" else it}.toSet()
        val overrides=mutableListOf("/ppt/presentation.xml" to "$p.presentation.main+xml","/ppt/slideMasters/slideMaster1.xml" to "$p.slideMaster+xml",
            "/ppt/slideLayouts/slideLayout1.xml" to "$p.slideLayout+xml","/ppt/slideLayouts/slideLayout2.xml" to "$p.slideLayout+xml",
            "/ppt/notesMasters/notesMaster1.xml" to "$p.notesMaster+xml","/ppt/theme/theme1.xml" to "application/vnd.openxmlformats-officedocument.theme+xml",
            "/ppt/theme/theme2.xml" to "application/vnd.openxmlformats-officedocument.theme+xml","/ppt/presProps.xml" to "$p.presProps+xml",
            "/ppt/viewProps.xml" to "$p.viewProps+xml","/ppt/tableStyles.xml" to "$p.tableStyles+xml",coreOverride,appOverride)
        slides.forEachIndexed {i,s->
            overrides+="/ppt/slides/slide${i+1}.xml" to "$p.slide+xml"
            if(s.notes.isNotBlank()) overrides+="/ppt/notesSlides/notesSlide${i+1}.xml" to "$p.notesSlide+xml"
        }
        val defaults=baseDefaults+extensions.map {if(it=="png") "png" to "image/png" else "jpg" to "image/jpeg"}.distinct()
        parts+="[Content_Types].xml" to utf8(types(defaults,overrides))
        parts+="_rels/.rels" to utf8(packageRels("ppt/presentation.xml"))
        val n=slides.size
        parts+="ppt/presentation.xml" to utf8(XML_HEAD+"<p:presentation xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\" saveSubsetFonts=\"1\">"+
            "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:notesMasterIdLst><p:notesMasterId r:id=\"rId2\"/></p:notesMasterIdLst><p:sldIdLst>"+
            (1..n).joinToString("") {"<p:sldId id=\"${255+it}\" r:id=\"rId${2+it}\"/>"}+"</p:sldIdLst><p:sldSz cx=\"$EMU_W\" cy=\"$EMU_H\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
        parts+="ppt/_rels/presentation.xml.rels" to utf8(rels(*(listOf(Triple("rId1","$REL_DOC/slideMaster","slideMasters/slideMaster1.xml"),Triple("rId2","$REL_DOC/notesMaster","notesMasters/notesMaster1.xml"))+
            (1..n).map {Triple("rId${2+it}","$REL_DOC/slide","slides/slide$it.xml")}+
            listOf(Triple("rId${n+3}","$REL_DOC/presProps","presProps.xml"),Triple("rId${n+4}","$REL_DOC/viewProps","viewProps.xml"),
                Triple("rId${n+5}","$REL_DOC/theme","theme/theme1.xml"),Triple("rId${n+6}","$REL_DOC/tableStyles","tableStyles.xml"))).toTypedArray()))
        parts+="ppt/presProps.xml" to utf8(XML_HEAD+"<p:presentationPr xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\"/>")
        parts+="ppt/viewProps.xml" to utf8(XML_HEAD+"<p:viewPr xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\"/>")
        parts+="ppt/tableStyles.xml" to utf8(XML_HEAD+"<a:tblStyleLst xmlns:a=\"$NS_A\" def=\"{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}\"/>")
        parts+="ppt/theme/theme1.xml" to utf8(theme)
        parts+="ppt/theme/theme2.xml" to utf8(theme)
        parts+="ppt/slideMasters/slideMaster1.xml" to utf8(master)
        parts+="ppt/slideMasters/_rels/slideMaster1.xml.rels" to utf8(rels(Triple("rId1","$REL_DOC/slideLayout","../slideLayouts/slideLayout1.xml"),
            Triple("rId2","$REL_DOC/slideLayout","../slideLayouts/slideLayout2.xml"),Triple("rId3","$REL_DOC/theme","../theme/theme1.xml")))
        parts+="ppt/slideLayouts/slideLayout1.xml" to utf8(layoutTitle)
        parts+="ppt/slideLayouts/slideLayout2.xml" to utf8(layoutContent)
        for(k in 1..2) parts+="ppt/slideLayouts/_rels/slideLayout$k.xml.rels" to utf8(rels(Triple("rId1","$REL_DOC/slideMaster","../slideMasters/slideMaster1.xml")))
        parts+="ppt/notesMasters/notesMaster1.xml" to utf8(notesMaster)
        parts+="ppt/notesMasters/_rels/notesMaster1.xml.rels" to utf8(rels(Triple("rId1","$REL_DOC/theme","../theme/theme2.xml")))
        slides.forEachIndexed {i,s->
            val k=i+1;val image=s.image
            val titleOnly=s.bullets.isEmpty() && image==null
            parts+="ppt/slides/slide$k.xml" to utf8(slideXml(s,image!=null))
            val slideRels=mutableListOf(Triple("rId1","$REL_DOC/slideLayout","../slideLayouts/slideLayout${if(titleOnly) 1 else 2}.xml"))
            if(s.notes.isNotBlank()) slideRels+=Triple("rId2","$REL_DOC/notesSlide","../notesSlides/notesSlide$k.xml")
            if(image!=null) {
                val ext=image.ext.lowercase().let {if(it=="jpeg") "jpg" else it}
                slideRels+=Triple("rId3","$REL_DOC/image","../media/image$k.$ext")
                parts+="ppt/media/image$k.$ext" to image.bytes
            }
            parts+="ppt/slides/_rels/slide$k.xml.rels" to utf8(rels(*slideRels.toTypedArray()))
            if(s.notes.isNotBlank()) {
                parts+="ppt/notesSlides/notesSlide$k.xml" to utf8(notesXml(s.notes))
                parts+="ppt/notesSlides/_rels/notesSlide$k.xml.rels" to utf8(rels(Triple("rId1","$REL_DOC/notesMaster","../notesMasters/notesMaster1.xml"),Triple("rId2","$REL_DOC/slide","../slides/slide$k.xml")))
            }
        }
        parts+="docProps/core.xml" to utf8(core(deckTitle.ifBlank {slides.first().title},now))
        parts+="docProps/app.xml" to utf8(app)
        return zip(parts,now)
    }

    // ---------------------------------------------------------------- reading

    /** The named parts of a zip file whose names [wanted] accepts. Each part is capped, so a zip bomb cannot fill the phone's memory. */
    fun unzip(bytes:ByteArray,wanted:(String)->Boolean):Map<String,ByteArray> = unzip(bytes,wanted,25_000_000,60_000_000)
    fun unzip(bytes:ByteArray,wanted:(String)->Boolean,entryLimit:Int=25_000_000,totalLimit:Int=60_000_000):Map<String,ByteArray> {
        val out=LinkedHashMap<String,ByteArray>();var total=0
        ZipInputStream(ByteArrayInputStream(bytes)).use {zip->
            while(true) {
                val entry=zip.nextEntry?:break
                if(entry.isDirectory || !wanted(entry.name)) continue
                val buffer=ByteArrayOutputStream();val chunk=ByteArray(16_384)
                while(true) {
                    val n=zip.read(chunk);if(n<0) break
                    require(buffer.size()+n<=entryLimit) {"A part of this file is too large to read."}
                    buffer.write(chunk,0,n)
                }
                total+=buffer.size();require(total<=totalLimit) {"This file is too large to read."}
                out[entry.name]=buffer.toByteArray()
            }
        }
        return out
    }
    private val token=Regex("<(/?)([A-Za-z_][\\w.:-]*)([^>]*?)(/?)>|([^<]+)")
    /** An attribute's value. A name with a prefix ("r:id") must match exactly; a bare name ("val") also matches w:val. */
    private fun attr(attrs:String,name:String):String {
        val prefix=if(name.contains(':')) "" else "(?:[\\w.-]+:)?"
        return Regex("(?:^|\\s)$prefix${Regex.escape(name)}\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").find(attrs)?.let {decode(it.groupValues[2].ifEmpty {it.groupValues[3]})}.orEmpty()
    }
    private class Tag(val close:Boolean,val name:String,val attrs:String,val selfClosing:Boolean,val text:String?)
    private fun tags(xml:String):Sequence<Tag> = token.findAll(xml).map {m->
        val g=m.groupValues
        if(g[2].isEmpty()) Tag(false,"","",false,decode(g[5])) else Tag(g[1]=="/",g[2].substringAfter(':'),g[3],g[4]=="/",null)
    }
    private fun relTargets(xml:String):Map<String,String> = tags(xml).filter {it.name=="Relationship" && !it.close}.associate {attr(it.attrs,"Id") to attr(it.attrs,"Target")}

    /** The words of a Word file: headings marked with #, list items with -, table rows as "a | b | c". */
    fun wordText(documentXml:String):String {
        val lines=mutableListOf<String>()
        val para=StringBuilder();var style="";var listed=false;var inText=false;var inTabs=false
        var depth=0;val row=mutableListOf<String>();val cell=StringBuilder()
        for(t in tags(documentXml)) {
            if(t.text!=null) {if(inText) para.append(t.text);continue}
            when(t.name) {
                "p"->if(!t.close && !t.selfClosing) {para.setLength(0);style="";listed=false} else if(t.selfClosing) {if(depth==0) lines+=""} else {
                    val text=para.toString()
                    if(depth>0) {if(cell.isNotEmpty() && text.isNotBlank()) cell.append(' ');cell.append(text.trim())}
                    else {
                        val prefix=when {Regex("(?i)heading(\\d)").find(style)!=null->"#".repeat(Regex("(?i)heading(\\d)").find(style)!!.groupValues[1].toInt().coerceIn(1,6))+" ";style.equals("title",true)->"# ";listed->"- ";else->""}
                        lines+=if(text.isBlank()) "" else prefix+text
                    }
                    para.setLength(0)
                }
                "pStyle"->style=attr(t.attrs,"val")
                "numPr"->listed=true
                "t"->inText=!t.close && !t.selfClosing
                "tabs"->inTabs=!t.close
                "tab"->if(!inTabs) para.append('\t')
                "br","cr"->para.append(' ')
                "tbl"->if(t.close) {depth--;if(depth==0) lines+=""} else if(!t.selfClosing) depth++
                "tr"->if(depth==1) {if(t.close) {lines+=row.joinToString(" | ");row.clear()} else row.clear()}
                "tc"->if(depth==1) {if(t.close) {row+=cell.toString().trim();cell.setLength(0)} else cell.setLength(0)}
            }
        }
        return lines.joinToString("\n").replace(Regex("\n{3,}"),"\n\n").trim()
    }
    private fun sharedStrings(xml:String):List<String> {
        val out=mutableListOf<String>();val cur=StringBuilder();var inText=false;var skip=0
        for(t in tags(xml)) {
            if(t.text!=null) {if(inText && skip==0) cur.append(t.text);continue}
            when(t.name) {
                "si"->if(t.close) {out+=cur.toString();cur.setLength(0)} else cur.setLength(0)
                "rPh"->if(!t.selfClosing) skip+=if(t.close) -1 else 1
                "t"->inText=!t.close && !t.selfClosing
            }
        }
        return out
    }
    /** One worksheet as lines "row 3: Brakes | 2 | 45.5 | =B3*C3 (91)". Empty rows are left out. */
    fun sheetText(xml:String,shared:List<String>,maxRows:Int=2_000):String {
        val lines=mutableListOf<String>()
        var rowNumber=0;var cells=sortedMapOf<Int,String>()
        var type="";var column=0;var value=StringBuilder();var formula=StringBuilder();var inV=false;var inF=false;var inT=false;var inIs=false
        for(t in tags(xml)) {
            if(t.text!=null) {
                if(inV) value.append(t.text) else if(inF) formula.append(t.text) else if(inT && inIs) value.append(t.text)
                continue
            }
            when(t.name) {
                "row"->if(!t.close) {rowNumber=attr(t.attrs,"r").toIntOrNull()?:(rowNumber+1);cells=sortedMapOf()} else if(cells.isNotEmpty() && lines.size<maxRows) {
                    val last=cells.lastKey()
                    lines+="row $rowNumber: "+(0..last).joinToString(" | ") {cells[it].orEmpty()}
                }
                "c"->if(!t.close) {
                    type=attr(t.attrs,"t");value=StringBuilder();formula=StringBuilder()
                    val ref=attr(t.attrs,"r").takeWhile {it.isLetter()}
                    column=if(ref.isEmpty()) (cells.lastKeyOrNull()?.plus(1)?:0) else columnIndex(ref)
                    if(t.selfClosing) {}
                } else {
                    val raw=value.toString()
                    val shown=when(type) {
                        "s"->shared.getOrElse(raw.trim().toIntOrNull()?:-1) {""}
                        "b"->if(raw.trim()=="1") "TRUE" else "FALSE"
                        else->raw
                    }
                    val text=if(formula.isNotEmpty()) (if(shown.isBlank()) "=$formula" else "=$formula ($shown)") else shown
                    if(text.isNotBlank()) cells[column]=text.trim()
                }
                "v"->inV=!t.close && !t.selfClosing
                "f"->inF=!t.close && !t.selfClosing
                "is"->inIs=!t.close && !t.selfClosing
                "t"->inT=!t.close && !t.selfClosing
            }
        }
        return lines.joinToString("\n")
    }
    private fun java.util.SortedMap<Int,String>.lastKeyOrNull():Int? = if(isEmpty()) null else lastKey()
    private fun excelText(parts:Map<String,ByteArray>):String {
        val workbook=parts["xl/workbook.xml"]?.toString(Charsets.UTF_8)?:error("This does not look like an Excel file.")
        val rel=relTargets(parts["xl/_rels/workbook.xml.rels"]?.toString(Charsets.UTF_8).orEmpty())
        val shared=parts["xl/sharedStrings.xml"]?.let {sharedStrings(it.toString(Charsets.UTF_8))}.orEmpty()
        val out=StringBuilder()
        var index=0
        for(t in tags(workbook)) {
            if(t.text!=null || t.name!="sheet" || t.close) continue
            index++
            val name=attr(t.attrs,"name")
            val target=rel[attr(t.attrs,"r:id")].orEmpty().ifEmpty {"worksheets/sheet$index.xml"}
            val path=if(target.startsWith("/")) target.removePrefix("/") else "xl/"+target
            val xml=parts[path]?.toString(Charsets.UTF_8)
            out.append("## Sheet: ").append(name).append('\n')
            out.append(if(xml==null) "(empty)" else sheetText(xml,shared).ifBlank {"(empty)"}).append("\n\n")
        }
        return out.toString().trim()
    }
    private fun slideTexts(xml:String):List<String> {
        val paras=mutableListOf<String>();val cur=StringBuilder();var inText=false;var field=0
        for(t in tags(xml)) {
            if(t.text!=null) {if(inText && field==0) cur.append(t.text);continue}
            when(t.name) {
                "p"->if(t.close) {if(cur.isNotBlank()) paras+=cur.toString().trim();cur.setLength(0)} else cur.setLength(0)
                "fld"->if(!t.selfClosing) field+=if(t.close) -1 else 1
                "t"->inText=!t.close && !t.selfClosing
                "br"->cur.append(' ')
            }
        }
        return paras
    }
    private fun slidesText(parts:Map<String,ByteArray>):String {
        val order:List<String> = run {
            val pres=parts["ppt/presentation.xml"]?.toString(Charsets.UTF_8)
            val rel=relTargets(parts["ppt/_rels/presentation.xml.rels"]?.toString(Charsets.UTF_8).orEmpty())
            val fromPresentation:List<String> = if(pres==null) emptyList() else tags(pres).filter {it.name=="sldId" && !it.close && it.text==null}.mapNotNull {rel[attr(it.attrs,"r:id")]}.map {"ppt/"+it.removePrefix("/").removePrefix("ppt/")}.toList()
            fromPresentation.filter {it in parts}.ifEmpty {parts.keys.filter {Regex("ppt/slides/slide\\d+\\.xml").matches(it)}.sortedBy {Regex("\\d+").find(it.substringAfterLast('/'))!!.value.toInt()}}
        }
        val notesFor=parts.keys.filter {Regex("ppt/notesSlides/notesSlide\\d+\\.xml").matches(it)}.associate {path->
            val relPath="ppt/notesSlides/_rels/"+path.substringAfterLast('/')+".rels"
            val slide=relTargets(parts[relPath]?.toString(Charsets.UTF_8).orEmpty()).values.firstOrNull {it.contains("slides/slide")}?.substringAfterLast('/')
            ("ppt/slides/"+slide) to path
        }
        val out=StringBuilder()
        order.forEachIndexed {i,path->
            out.append("## Slide ${i+1}\n")
            slideTexts(parts.getValue(path).toString(Charsets.UTF_8)).forEach {out.append(it).append('\n')}
            notesFor[path]?.let {np->
                val notes=slideTexts(parts.getValue(np).toString(Charsets.UTF_8))
                if(notes.isNotEmpty()) out.append("Notes: ").append(notes.joinToString(" ")).append('\n')
            }
            out.append('\n')
        }
        return out.toString().trim()
    }
    /** The words in a Word, Excel or PowerPoint file's bytes. [name] only decides which kind it is. Throws a plain sentence when the file is not one. */
    fun readText(name:String,bytes:ByteArray,entryLimit:Int=25_000_000):String {
        val kind=kindOf(name)?:error("That is not a Word, Excel or PowerPoint file.")
        require(bytes.size<=READ_FILE_LIMIT) {"That file is too large to read (30 MB limit)."}
        val text=try {
            when(kind) {
                "word"-> {
                    val parts=unzip(bytes,{it=="word/document.xml"},entryLimit)
                    wordText(parts["word/document.xml"]?.toString(Charsets.UTF_8)?:error("This does not look like a Word file."))
                }
                "excel"->excelText(unzip(bytes,{it=="xl/workbook.xml" || it=="xl/_rels/workbook.xml.rels" || it=="xl/sharedStrings.xml" || it.startsWith("xl/worksheets/sheet")},entryLimit))
                else->slidesText(unzip(bytes,{Regex("ppt/(slides/slide\\d+\\.xml|presentation\\.xml|_rels/presentation\\.xml\\.rels|notesSlides/(_rels/)?notesSlide\\d+\\.xml(\\.rels)?)").matches(it)},entryLimit))
            }
        } catch(e:java.io.IOException) {error("That file is damaged or password protected, so I could not open it.")}
        return if(text.length>READ_TEXT_LIMIT) text.take(READ_TEXT_LIMIT)+"\n\n(The rest of the file was left out because it is long.)" else text
    }
    fun readFile(file:File,entryLimit:Int=25_000_000):String {
        require(file.isFile) {"${file.name} is not in this bot's files."}
        require(file.length()<=READ_FILE_LIMIT) {"That file is too large to read (30 MB limit)."}
        return readText(file.name,file.readBytes(),entryLimit)
    }

    /** What a freshly made file holds, found by opening it again with the reader: the proof that it is a real package, not just bytes. */
    class Check(val kind:String,val parts:Int,val characters:Int)
    fun verify(name:String,bytes:ByteArray):Check {
        val kind=kindOf(name)?:error("That is not a Word, Excel or PowerPoint file.")
        val parts=unzip(bytes) {true}
        require("[Content_Types].xml" in parts && "_rels/.rels" in parts) {"The file is missing its package parts."}
        val text=readText(name,bytes)
        return Check(kind,parts.size,text.length)
    }
}
