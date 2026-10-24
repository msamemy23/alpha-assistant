package com.alphainternational.assistant

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.time.LocalDate

/** Simple Markdown subset for phone-made documents: headings, paragraphs, bullets, numbers, tables and rules. */
sealed class DocBlock {
    data class Heading(val level:Int,val text:String):DocBlock()
    data class Paragraph(val text:String,val bold:Boolean=false):DocBlock()
    data class Bullet(val marker:String,val text:String):DocBlock()
    data class Table(val rows:List<List<String>>):DocBlock()
    /** A real chart drawn in the PDF: pie, bar or line, from "Label: number" lines in a ```chart block. */
    data class Chart(val type:String,val title:String,val points:List<Pair<String,Double>>):DocBlock()
    object Rule:DocBlock()
    object Space:DocBlock()
}

object DocumentPdf {
    fun blocks(markdown:String):List<DocBlock> {
        val out=mutableListOf<DocBlock>();val table=mutableListOf<List<String>>();val paragraph=mutableListOf<String>()
        fun flushTable() {if(table.isNotEmpty()){out+=DocBlock.Table(table.toList());table.clear()}}
        fun flushParagraph() {
            if(paragraph.isEmpty())return
            val raw=paragraph.joinToString(" ").trim();paragraph.clear()
            val bold=Regex("^(\\*\\*|__)(.+)(\\*\\*|__)$").matches(raw)
            out+=DocBlock.Paragraph(plain(raw),bold)
        }
        var chart:MutableList<String>?=null
        for(line in markdown.replace("\r\n","\n").split('\n')) {
            val text=line.trim()
            // ```chart … ``` becomes a drawn chart.
            if(chart!=null) {
                if(text.startsWith("```")) {out+=chart(chart);chart=null} else chart+=text
                continue
            }
            if(text.startsWith("```chart",true)) {flushParagraph();flushTable();chart=mutableListOf(text.removePrefix("```").drop(5).trim().let {if(it.isBlank()) "" else "type: $it"});continue}
            if(text.startsWith("|") && text.endsWith("|") && text.length>1) {
                flushParagraph()
                val cells=text.trim('|').split('|').map {plain(it.trim())}
                if(cells.all {it.matches(Regex(":?-{2,}:?"))})continue
                table+=cells;continue
            }
            flushTable()
            when {
                text.isEmpty()->{flushParagraph();if(out.lastOrNull()!=DocBlock.Space && out.isNotEmpty())out+=DocBlock.Space}
                text.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$"))->{flushParagraph();out+=DocBlock.Rule}
                Regex("^(#{1,3})\\s+(.+)$").find(text)!=null->{
                    flushParagraph();val m=Regex("^(#{1,3})\\s+(.+)$").find(text)!!
                    out+=DocBlock.Heading(m.groupValues[1].length,plain(m.groupValues[2]))
                }
                Regex("^[-*•]\\s+(.+)$").find(text)!=null->{flushParagraph();out+=DocBlock.Bullet("•",plain(text.drop(1).trim()))}
                Regex("^\\[( |x|X)]\\s+(.+)$").find(text)!=null->{
                    flushParagraph();val m=Regex("^\\[( |x|X)]\\s+(.+)$").find(text)!!
                    out+=DocBlock.Bullet(if(m.groupValues[1].isBlank())"[ ]" else "[x]",plain(m.groupValues[2]))
                }
                Regex("^(\\d{1,3})[.)]\\s+(.+)$").find(text)!=null->{
                    flushParagraph();val m=Regex("^(\\d{1,3})[.)]\\s+(.+)$").find(text)!!
                    out+=DocBlock.Bullet(m.groupValues[1]+".",plain(m.groupValues[2]))
                }
                else->paragraph+=text
            }
        }
        chart?.let {out+=chart(it)}
        flushParagraph();flushTable()
        while(out.lastOrNull()==DocBlock.Space)out.removeAt(out.lastIndex)
        return out
    }
    /** "type: pie", "title: …", then "Repairs: 40" (or "- Repairs: $1,200", "Parts | 30%") lines; up to 12 slices of a pie or 60 points of a bar or line chart (more is an error, never a quiet cut). */
    fun chart(lines:List<String>):DocBlock.Chart {
        var type="bar";var title="";val points=mutableListOf<Pair<String,Double>>()
        for(raw in lines) {
            val line=raw.trim().removePrefix("-").removePrefix("*").trim()
            val parts=line.split(Regex("\\s*[:|=]\\s*"),limit=2)
            if(parts.size<2 || parts[0].isBlank()) continue
            val key=parts[0].trim();val value=parts[1].trim()
            when(key.lowercase()) {
                "type","kind"->type=value.lowercase().let {if(it.contains("pie") || it.contains("donut")) "pie" else if(it.contains("line")) "line" else "bar"}
                "title"->title=plain(value).take(100)
                else->Regex("-?[0-9][0-9,]*\\.?[0-9]*").find(value)?.value?.replace(",","")?.toDoubleOrNull()?.let {points+=plain(key).take(40) to it}
            }
        }
        require(points.isNotEmpty()) {"A chart needs lines like \"Repairs: 40\"."}
        require(points.size<=ChartLayout.limit(type)) {ChartLayout.tooMany(type,points.size)}
        if(type=="pie") require(points.all {it.second>=0} && points.sumOf {it.second}>0) {"A pie chart needs positive numbers."}
        return DocBlock.Chart(type,title,points)
    }
    /** Removes inline Markdown markers that a PDF cannot style piecemeal. Links keep their visible text and address. */
    fun plain(text:String):String=text.replace(Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)"),"$1 ($2)")
        .replace(Regex("(\\*\\*|__)(.+?)\\1"),"$2").replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\w)"),"$1").replace("`","")

    /** Safe workspace base name: "Brake Estimate (Smith)" → "brake-estimate-smith". */
    fun baseName(value:String):String {
        val name=value.substringBeforeLast(".pdf").lowercase().replace(Regex("[^a-z0-9]+"),"-").trim('-').take(60)
        require(name.isNotBlank()) {"Give the document a file name."}
        return name
    }

    fun create(folder:File,fileName:String,title:String,markdown:String,letterhead:Boolean,date:LocalDate=LocalDate.now()):Made {
        require(title.isNotBlank() && title.length<=120) {"Give the document a title under 120 characters."}
        require(markdown.isNotBlank() && markdown.length<=40_000) {"Document text must be 1 to 40,000 characters."}
        val base=baseName(fileName)
        require(folder.isDirectory || folder.mkdirs()) {"The document folder could not be created."}
        val source=File(folder,"$base.md");val body="# "+title.trim()+"\n\n"+markdown.trim()+"\n"
        source.writeText(body);require(source.readText()==body) {"The document text could not be saved."}
        val pdfFile=File(folder,"$base.pdf")
        render(pdfFile,title.trim(),blocks(markdown),letterhead,date)
        val pages=ParcelFileDescriptor.open(pdfFile,ParcelFileDescriptor.MODE_READ_ONLY).use {descriptor->
            PdfRenderer(descriptor).use {renderer->
                require(renderer.pageCount>0) {"The PDF could not be opened."}
                renderer.openPage(0).use {page->require(page.width>0 && page.height>0)}
                renderer.pageCount
            }
        }
        return Made(listOf(pdfFile.name,source.name),pages)
    }
    data class Made(val files:List<String>,val pages:Int)

    // Plain ARGB values (not Color.rgb) so the Markdown parser stays usable off the phone in tests.
    private val palette=listOf(0xFF147658,0xFF4285F4,0xFFF4B400,0xFFDB4437,0xFF8E24AA,0xFF00ACC1,0xFFFF7043,0xFF7CB342,0xFF5C6BC0,0xFFF06292,0xFF795548,0xFF607D8B)
        .map {it.toInt()}
    fun number(value:Double):String=if(value==Math.floor(value) && kotlin.math.abs(value)<1e12) "%,d".format(value.toLong()) else "%,.2f".format(value).trimEnd('0').trimEnd('.')

    /** Draws [chart] in the box x,y,w,h on a PDF page. */
    private fun drawChart(canvas:android.graphics.Canvas,chart:DocBlock.Chart,x:Float,y:Float,w:Float,h:Float,fontScale:Float=1f) {
        val text=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(23,36,43);textSize=9.5f*fontScale}
        val muted=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(82,100,110);textSize=8.5f*fontScale}
        val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.FILL}
        fun fit(label:String,paint:Paint,limit:Float):String {
            if(paint.measureText(label)<=limit) return label
            var cut=label;while(cut.length>1 && paint.measureText("$cut…")>limit) cut=cut.dropLast(1)
            return "$cut…"
        }
        val points=chart.points
        if(chart.type=="pie") {
            val total=points.sumOf {it.second}
            val radius=minOf(h/2f-6f,w*0.22f);val cx=x+radius+12f;val cy=y+h/2f
            val box=android.graphics.RectF(cx-radius,cy-radius,cx+radius,cy+radius)
            var start=-90f
            points.forEachIndexed {i,(_,value)->
                val sweep=(value/total*360.0).toFloat()
                fill.color=palette[i%palette.size];canvas.drawArc(box,start,sweep,true,fill)
                if(sweep>=22f) {
                    val middle=Math.toRadians((start+sweep/2).toDouble())
                    val label="%.0f%%".format(value/total*100)
                    val white=Paint(text).apply {color=Color.WHITE;typeface=Typeface.DEFAULT_BOLD;textAlign=Paint.Align.CENTER;textSize=9f*fontScale}
                    canvas.drawText(label,(cx+radius*0.62f*Math.cos(middle)).toFloat(),(cy+radius*0.62f*Math.sin(middle)).toFloat()+3f,white)
                }
                start+=sweep
            }
            canvas.drawCircle(cx,cy,radius,Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;color=Color.WHITE;strokeWidth=1f})
            val legendX=cx+radius+28f;val row=minOf(18f,h/points.size);var ly=cy-row*points.size/2f+row/2f
            points.forEachIndexed {i,(label,value)->
                fill.color=palette[i%palette.size];canvas.drawRect(legendX,ly-7f,legendX+10f,ly+3f,fill)
                val share=" · "+number(value)+" · "+"%.0f%%".format(value/total*100)
                canvas.drawText(fit(label,text,x+w-legendX-18f-text.measureText(share))+share,legendX+16f,ly+2f,text)
                ly+=row
            }
            return
        }
        // Bar and line charts share axes: value labels on the left, item labels underneath. The axis lines are round numbers that follow the data
        // (a line is not squeezed against the top of an axis that starts at 0), and labels that would print over one another are left out.
        val top=y+12f;val bottom=y+h-26f;val axisX=x+46f;val end=x+w-6f
        val ticks=ChartLayout.axis(chart.type,points.map {it.second})
        val low=ticks.first();val high=ticks.last();val span=(high-low).takeIf {it>0}?:1.0
        fun yOf(value:Double)=(bottom-(value-low)/span*(bottom-top)*0.92).toFloat()
        val grid=Paint().apply {color=Color.rgb(230,230,230);strokeWidth=0.8f}
        muted.textAlign=Paint.Align.RIGHT
        ticks.forEach {value->
            val gy=yOf(value)
            canvas.drawLine(axisX,gy,end,gy,grid);canvas.drawText(number(value),axisX-5f,gy+3f,muted)
        }
        muted.textAlign=Paint.Align.CENTER
        val slot=(end-axisX)/points.size
        val centers=points.indices.map {axisX+slot*(it+0.5f)}
        val crowded=points.size>12
        // Under the axis: every item's name while they fit, otherwise as many as fit side by side, the first and last always.
        val named=if(!crowded) points.indices.toSet() else ChartLayout.labelled(centers,points.map {muted.measureText(it.first)},listOf(0,points.lastIndex),gap=4f)
        // The numbers: the first, last, highest and lowest come first, then any that fit.
        val values=points.map {it.second}
        val widths=values.map {text.measureText(number(it))}
        // On a line each number sits on the side of its point the line is not on (left of a rise, right of a fall, under a dip), so the layout works from where it really is.
        val places=values.indices.map {ChartLayout.place(values,it)}
        val numberAt=centers.indices.map {i->if(chart.type=="line") centers[i]+places[i].shift*(widths[i]/2f-2f) else centers[i]}
        // A crowded line numbers its ends, highest and lowest, then only the peaks and dips that fit: a number beside a slope would have the line running through it.
        val valued=ChartLayout.labelled(numberAt,widths,ChartLayout.important(values),allowed={i->!(crowded && chart.type=="line") || places[i].shift==0})
        text.textAlign=Paint.Align.CENTER
        /** The middle for a label of this width: its own point's middle, but never so far out that the label runs over the picture's edge. */
        fun inside(middle:Float,width:Float)=middle.coerceIn(x+2f+width/2f,x+w-2f-width/2f)
        if(chart.type=="line") {
            val line=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=palette[0];strokeWidth=if(crowded) 1.8f else 2.2f;style=Paint.Style.STROKE}
            val path=android.graphics.Path()
            points.forEachIndexed {i,(_,value)->
                val px=centers[i];val py=yOf(value)
                if(i==0) path.moveTo(px,py) else path.lineTo(px,py)
            }
            canvas.drawPath(path,line)
            points.forEachIndexed {i,(label,value)->
                val px=centers[i];val py=yOf(value)
                fill.color=palette[0];canvas.drawCircle(px,py,if(points.size>24) 2f else 3.2f,fill)
                if(i in valued) canvas.drawText(number(value),inside(numberAt[i],widths[i]),if(places[i].below) py+text.textSize+2f else py-7f,text)
                if(i in named) {val shown=if(crowded) label else fit(label,muted,slot-4f);canvas.drawText(shown,inside(px,muted.measureText(shown)),bottom+14f,muted)}
            }
        } else {
            val zero=yOf(0.0)
            points.forEachIndexed {i,(label,value)->
                val left=axisX+slot*i+slot*0.18f;val right=axisX+slot*(i+1)-slot*0.18f;val vy=yOf(value)
                fill.color=palette[i%palette.size];canvas.drawRect(left,minOf(vy,zero),right,maxOf(vy,zero),fill)
                if(i in valued) canvas.drawText(number(value),inside((left+right)/2f,text.measureText(number(value))),minOf(vy,zero)-4f,text)
                if(i in named) {val shown=if(crowded) label else fit(label,muted,slot-4f);canvas.drawText(shown,inside((left+right)/2f,muted.measureText(shown)),bottom+14f,muted)}
            }
        }
        canvas.drawLine(axisX,bottom,end,bottom,Paint().apply {color=Color.rgb(160,160,160);strokeWidth=1f})
    }

    /** A chart as a picture for the chat: white card, optional title, the same drawing the PDFs use at a phone-readable size. */
    fun chartPicture(chart:DocBlock.Chart):android.graphics.Bitmap {
        val scale=2.8f;val width=420f;val body=if(chart.type=="pie") 250f else 280f
        val head=if(chart.title.isBlank()) 8f else 34f
        val bitmap=android.graphics.Bitmap.createBitmap((width*scale).toInt(),((body+head+8f)*scale).toInt(),android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE);canvas.scale(scale,scale)
        if(chart.title.isNotBlank()) {
            val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(23,36,43);textSize=15f;typeface=Typeface.DEFAULT_BOLD}
            var title=chart.title;while(title.length>1 && ink.measureText(title)>width-28f) title=title.dropLast(1)
            canvas.drawText(title,14f,24f,ink)
        }
        drawChart(canvas,chart,10f,head,width-20f,body,fontScale=1.3f)
        return bitmap
    }

    private fun render(file:File,title:String,blocks:List<DocBlock>,letterhead:Boolean,date:LocalDate) {
        val pdf=PdfDocument()
        try {
            val width=612f;val height=792f;val left=54f;val right=width-54f;val bottom=height-54f
            var page:PdfDocument.Page?=null;var number=0;var y=0f
            val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(23,36,43)}
            val muted=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(82,100,110)}
            val accent=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(20,118,88)}
            val rule=Paint().apply {color=Color.rgb(221,221,221);strokeWidth=1f}
            fun canvas()=page!!.canvas
            fun style(paint:Paint,size:Float,bold:Boolean){paint.textSize=size;paint.typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT;paint.textAlign=Paint.Align.LEFT}
            fun footer() {style(muted,8f,false);muted.textAlign=Paint.Align.RIGHT;canvas().drawText("Page $number",right,height-30f,muted);muted.textAlign=Paint.Align.LEFT}
            fun newPage() {page?.let {footer();pdf.finishPage(it)};page=pdf.startPage(PdfDocument.PageInfo.Builder(612,792,++number).create());y=60f}
            fun room(needed:Float){if(y+needed>bottom)newPage()}
            fun wrap(text:String,paint:Paint,limit:Float):List<String> {
                val lines=mutableListOf<String>();var current=""
                for(word in text.split(Regex("\\s+")).filter {it.isNotEmpty()}) {
                    val candidate=if(current.isEmpty())word else "$current $word"
                    if(paint.measureText(candidate)<=limit){current=candidate;continue}
                    if(current.isNotEmpty())lines+=current
                    var rest=word
                    while(paint.measureText(rest)>limit){val cut=paint.breakText(rest,true,limit,null).coerceAtLeast(1);lines+=rest.take(cut);rest=rest.drop(cut)}
                    current=rest
                }
                if(current.isNotEmpty())lines+=current
                return lines
            }
            fun paragraph(text:String,paint:Paint,size:Float,bold:Boolean,x:Float=left,leading:Float=size*1.45f) {
                style(paint,size,bold)
                for(line in wrap(text,paint,right-x)){room(leading);y+=leading;canvas().drawText(line,x,y,paint)}
            }
            newPage()
            if(letterhead) {
                style(ink,15f,true);canvas().drawText(InvoiceSample.SHOP,left,y,ink)
                style(muted,9.5f,false);muted.textAlign=Paint.Align.RIGHT
                canvas().drawText(LocalInvoice.ADDRESS,right,y-4f,muted);canvas().drawText(LocalInvoice.PHONE,right,y+9f,muted);muted.textAlign=Paint.Align.LEFT
                y+=18f;canvas().drawLine(left,y,right,y,Paint(rule).apply {color=Color.rgb(20,118,88);strokeWidth=1.5f});y+=14f
            }
            paragraph(title,ink,21f,true,leading=26f)
            style(muted,9.5f,false);y+=16f;canvas().drawText(date.toString(),left,y,muted);y+=10f
            for(block in blocks) when(block) {
                is DocBlock.Heading-> {
                    val size=when(block.level){1->16f;2->13.5f;else->12f}
                    room(size*3);y+=size*0.6f;paragraph(block.text,if(block.level==1)accent else ink,size,true)
                }
                is DocBlock.Paragraph->paragraph(block.text,ink,10.5f,block.bold)
                is DocBlock.Bullet-> {
                    style(ink,10.5f,false);val indent=left+18f;val lines=wrap(block.text,ink,right-indent)
                    lines.forEachIndexed {index,line->
                        room(15f);y+=15f
                        if(index==0){style(accent,10.5f,true);canvas().drawText(block.marker,left+2f,y,accent);style(ink,10.5f,false)}
                        canvas().drawText(line,indent,y,ink)
                    }
                }
                is DocBlock.Table-> {
                    val columns=block.rows.maxOf {it.size};val cell=(right-left)/columns
                    block.rows.forEachIndexed {rowIndex,row->
                        val header=rowIndex==0 && block.rows.size>1
                        style(ink,10f,header)
                        val wrapped=(0 until columns).map {wrap(row.getOrElse(it){""},ink,cell-8f).ifEmpty {listOf("")}}
                        val tall=wrapped.maxOf {it.size}*13f+8f
                        room(tall+2f);val top=y
                        if(header)canvas().drawRect(left,top+2f,right,top+tall+2f,Paint().apply {color=Color.rgb(236,243,240)})
                        wrapped.forEachIndexed {column,lines->lines.forEachIndexed {i,line->canvas().drawText(line,left+column*cell+4f,top+15f+i*13f,ink)}}
                        y=top+tall+2f;canvas().drawLine(left,y,right,y,rule)
                    }
                    y+=4f
                }
                DocBlock.Rule->{room(16f);y+=10f;canvas().drawLine(left,y,right,y,rule);y+=4f}
                DocBlock.Space->y+=7f
                is DocBlock.Chart-> {
                    val tall=if(block.title.isBlank()) 230f else 252f
                    room(tall);y+=8f
                    if(block.title.isNotBlank()) {style(ink,12f,true);y+=14f;canvas().drawText(block.title,left,y,ink);y+=8f}
                    drawChart(canvas(),block,left,y,right-left,214f)
                    y+=222f
                }
            }
            page?.let {footer();pdf.finishPage(it)}
            file.outputStream().use {pdf.writeTo(it)}
        }finally{pdf.close()}
    }
}
