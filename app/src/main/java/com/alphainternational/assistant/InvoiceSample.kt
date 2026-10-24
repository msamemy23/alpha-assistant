package com.alphainternational.assistant

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

data class InvoiceLine(val kind:String,val description:String,val quantity:BigDecimal,val unitPrice:BigDecimal) {
    val amount:BigDecimal get()=(quantity*unitPrice).setScale(2,RoundingMode.HALF_UP)
}
object InvoiceAmounts {
    fun lines(raw:String,max:Int=12):List<InvoiceLine> {
        val rows=JSONArray(raw).objects();require(rows.size in 1..max) {"An invoice needs 1 to $max items."}
        return rows.map {row->
            val kind=row.getString("kind");require(kind in setOf("part","labor")) {"Each item kind must be part or labor."}
            val description=row.getString("description").trim();require(description.isNotBlank() && description.length<=160) {"Each item needs a short description."}
            val quantity=BigDecimal(row.get("quantity").toString());val price=BigDecimal(row.get("unit_price").toString())
            require(quantity>BigDecimal.ZERO && quantity<=BigDecimal(100) && price>=BigDecimal.ZERO && price<=BigDecimal(10000)) {"Use a positive quantity and a unit price up to USD 10,000."}
            require(quantity.scale()<=3 && price.scale()<=2) {"Use at most two decimal places for prices."}
            InvoiceLine(kind,description,quantity,price)
        }
    }
    fun tax(lines:List<InvoiceLine>,rate:BigDecimal):BigDecimal {
        require(rate>=BigDecimal.ZERO && rate<=BigDecimal(100))
        return (lines.filter {it.kind=="part"}.fold(BigDecimal.ZERO) {sum,l->sum+l.amount}*rate/BigDecimal(100)).setScale(2,RoundingMode.HALF_UP)
    }
    fun quote(value:String)="'"+value.replace("'","''").also {require(!it.contains('\u0000'))}+"'"
}

/** Business-specific writes use the existing document schema, never arbitrary model SQL. */
class InvoiceSample(private val mcp:Mcp) {
    private suspend fun rows(query:String):List<JSONObject> {
        val result=mcp.call("execute_sql",obj("query" to query))
        require(!result.optBoolean("isError")) {"Supabase could not complete the invoice query. Check the project connection before retrying."}
        val content=result.optJSONArray("content")?.objects()?.filter {it.optString("type")=="text"}?.joinToString("\n") {it.optString("text")}.orEmpty()
        return decodeRows(content)
    }
    suspend fun create(taskId:String,customer:String,rawItems:String,folder:File):JSONObject {
        require(customer.isNotBlank() && customer.length<=120) {"Give the sample customer a name."}
        val lines=InvoiceAmounts.lines(rawItems)
        val shops=rows("select shop_name,shop_address,shop_phone,shop_email,tax_rate,shop_id from public.settings where shop_name="+InvoiceAmounts.quote(SHOP)+" limit 2")
        require(shops.size==1) {"Choose one shop in Supabase before creating an invoice."}
        val shop=shops.single();val shopId=UUID.fromString(shop.getString("shop_id"))
        val rate=BigDecimal(shop.get("tax_rate").toString());val tax=InvoiceAmounts.tax(lines,rate)
        val subtotal=lines.fold(BigDecimal.ZERO) {sum,l->sum+l.amount}.setScale(2,RoundingMode.HALF_UP);val total=subtotal+tax
        val id=UUID.nameUUIDFromBytes(("alpha-sample-invoice|"+taskId).toByteArray()).toString()
        val number="SAMPLE-"+id.take(8).uppercase();val date=LocalDate.now().toString()
        val parts=arr(lines.filter {it.kind=="part"}.map {obj("name" to it.description,"brand" to "Sample","qty" to it.quantity,
            "unitPrice" to it.unitPrice,"cost" to BigDecimal.ZERO,"status" to "Sample","taxable" to true)})
        val labors=arr(lines.filter {it.kind=="labor"}.map {obj("operation" to it.description,"pricing" to "flat",
            "hours" to it.quantity,"rate" to it.unitPrice,"amount" to it.amount,"tech" to "Sample")})
        val notes="SAMPLE ONLY - Not a bill. Created to test Alpha Assistant. No customer account, appointment, payment or outbound message was created. Tax is illustrative using the configured shop rate on sample parts."
        val query="insert into public.documents (id,type,doc_number,status,doc_date,customer_name,parts,labors,shop_supplies,sublet,tax_rate,apply_tax,deposit,amount_paid,notes,locked,shop_id) values ("+
            listOf(InvoiceAmounts.quote(id)+"::uuid","'Invoice'",InvoiceAmounts.quote(number),"'Draft'",InvoiceAmounts.quote(date),
                InvoiceAmounts.quote("SAMPLE - "+customer),InvoiceAmounts.quote(parts.toString())+"::jsonb",InvoiceAmounts.quote(labors.toString())+"::jsonb",
                "0","0",rate.toPlainString(),"true","0","0",InvoiceAmounts.quote(notes),"false",InvoiceAmounts.quote(shopId.toString())+"::uuid").joinToString(",")+
            ") on conflict (id) do nothing returning id,doc_number"
        rows(query)
        // A successful HTTP response alone is not proof that the record is right.
        val saved=rows("select id,type,doc_number,status,doc_date,customer_name,parts,labors,tax_rate,apply_tax,notes,shop_id from public.documents where id="+InvoiceAmounts.quote(id)+"::uuid").singleOrNull()
            ?:error("The invoice write could not be verified. Check Supabase before retrying.")
        require(saved.getString("type")=="Invoice" && saved.getString("notes")==notes && saved.getString("doc_number")==number && saved.getString("shop_id")==shopId.toString() && saved.getString("status")=="Draft" &&
            saved.getString("customer_name")=="SAMPLE - "+customer && same(saved.getJSONArray("parts"),parts) && same(saved.getJSONArray("labors"),labors) &&
            BigDecimal(saved.get("tax_rate").toString()).compareTo(rate)==0 && saved.getBoolean("apply_tax")) {"Supabase returned a different invoice. The app did not export it as a confirmed sample."}
        // An idempotent retry across midnight must export the saved date, not change the document's apparent date.
        val savedDate=LocalDate.parse(saved.getString("doc_date")).toString()
        val document=obj("sample" to true,"id" to id,"number" to number,"date" to savedDate,"status" to "Draft","shop" to shop,"customer" to customer,
            "items" to arr(lines.map {obj("kind" to it.kind,"description" to it.description,"quantity" to it.quantity,"unit_price" to it.unitPrice,"amount" to it.amount)}),
            "subtotal" to subtotal,"tax_rate" to rate,"tax" to tax,"total" to total,"notes" to notes,"database_record" to saved)
        val files=export(document,folder)
        return obj("result" to "Sample invoice saved in Supabase, independently read back, and exported on this phone.","id" to id,"number" to number,
            "subtotal" to subtotal,"tax" to tax,"total" to total,"currency" to "USD","files" to arr(files),"verified" to true,"sent" to false)
    }
    companion object {
        const val SHOP=BuildConfig.SHOP_NAME
        internal fun export(document:JSONObject,folder:File):List<String> {
            val number=document.getString("number");require(number.matches(Regex("[A-Z0-9_-]{1,60}")))
            require(folder.isDirectory || folder.mkdirs()) {"The invoice output folder could not be created."}
            val jsonFile=File(folder,"$number.json");val json=document.toString(2)
            jsonFile.writeText(json);require(jsonFile.readText()==json) {"The saved invoice receipt could not be verified."}
            val htmlFile=File(folder,"$number.html");val html=html(document)
            htmlFile.writeText(html);require(htmlFile.readText()==html) {"The saved invoice preview could not be verified."}
            val pdfFile=File(folder,"$number.pdf");pdf(pdfFile,document)
            ParcelFileDescriptor.open(pdfFile,ParcelFileDescriptor.MODE_READ_ONLY).use {descriptor->
                PdfRenderer(descriptor).use {renderer->
                    require(renderer.pageCount>0) {"The invoice PDF could not be opened."}
                    renderer.openPage(0).use {page->require(page.width>0 && page.height>0)}
                }
            }
            return listOf(pdfFile.name,htmlFile.name,jsonFile.name)
        }
        fun decodeRows(raw:String):List<JSONObject> {
            var text=raw.trim()
            if(text.startsWith("{"))text=JSONObject(text).optString("result",text)
            val enclosed=Regex("<untrusted-data[^>]*>\\s*([\\s\\S]*?)\\s*</untrusted-data[^>]*>").find(text)
            if(enclosed!=null)text=enclosed.groupValues[1].trim()
            require(text.startsWith("[")) {"Supabase returned an unsupported query format; no confirmed invoice was exported."}
            return JSONArray(text).objects()
        }
        private fun same(a:JSONArray,b:JSONArray):Boolean = a.length()==b.length() && a.objects().zip(b.objects()).all {(left,right)->
            right.keys().asSequence().all {key->
                val expected=right.get(key);val actual=left.opt(key)
                if(expected is Number)runCatching {BigDecimal(actual.toString()).compareTo(BigDecimal(expected.toString()))==0}.getOrDefault(false) else expected==actual
            }
        }
        internal fun money(value:Any):String="$"+"%,.2f".format(java.util.Locale.US,BigDecimal(value.toString()).setScale(2,RoundingMode.HALF_UP))
        private fun quantity(value:Any)=BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        private fun escape(value:Any)=value.toString().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
        // Older documents have no sample flag; treat them as samples so nothing is mistaken for a bill.
        private fun isSample(d:JSONObject)=d.optBoolean("sample",true)
        private fun isEstimate(d:JSONObject)=d.optString("document_type")=="estimate"
        private fun heading(d:JSONObject)=(if(isSample(d))"SAMPLE " else "")+(if(isEstimate(d))"ESTIMATE" else "INVOICE")
        private fun totalLabel(d:JSONObject)=when {isEstimate(d)->"Estimated total";isSample(d)->"Sample total";else->"Total due"}
        private fun shopContact(shop:JSONObject)=listOf(shop.optString("shop_phone"),shop.optString("shop_email")).filter {it.isNotBlank()}.joinToString("  ·  ")
        private fun showsTax(d:JSONObject)=BigDecimal(d.get("tax_rate").toString()).signum()!=0 || BigDecimal(d.get("tax").toString()).signum()!=0
        fun html(d:JSONObject):String {
            val shop=d.getJSONObject("shop");val sample=isSample(d)
            val rows=d.getJSONArray("items").objects().joinToString("") {"<tr><td>${escape(it.getString("description"))}<span class=\"kind\">${escape(it.optString("kind"))}</span></td><td>${escape(quantity(it.get("quantity")))}</td><td>${money(it.get("unit_price"))}</td><td>${money(it.get("amount"))}</td></tr>"}
            val details=listOfNotNull("Date: "+escape(d.getString("date")),"Customer: "+escape(d.getString("customer")),
                d.optString("vehicle").takeIf {it.isNotBlank()}?.let {"Vehicle: "+escape(it)},d.optString("status").takeIf {it.isNotBlank()}?.let {"Status: "+escape(it)}).joinToString("<br>")
            val tax=if(showsTax(d))"Tax on parts (${escape(BigDecimal(d.get("tax_rate").toString()).stripTrailingZeros().toPlainString())}%): ${money(d.get("tax"))}<br>" else ""
            val record=if(d.has("database_record"))"<br>Supabase record: ${escape(d.getString("id"))}" else ""
            return """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(d.getString("number"))}</title>
                <style>body{font:16px system-ui,sans-serif;color:#17242b;max-width:850px;margin:40px auto;padding:24px}h1{font-size:26px}strong{color:#147658}.sample{background:#fff3cd;padding:15px;border:1px solid #efd18c}table{width:100%;border-collapse:collapse;margin:28px 0}td,th{padding:12px;border-bottom:1px solid #ddd;text-align:right}td:first-child,th:first-child{text-align:left}.kind{display:block;font-size:12px;color:#52646e;text-transform:capitalize}.totals{text-align:right;line-height:1.9}.notes{font-size:13px;color:#52646e;margin-top:30px}@media print{body{margin:0}}</style>
                <h1>${escape(shop.getString("shop_name"))}</h1><p>${escape(shop.optString("shop_address"))}<br>${escape(shopContact(shop))}</p>
                ${if(sample)"<div class=\"sample\"><strong>${heading(d)} — NOT A BILL</strong></div>" else "<h2>${heading(d)}</h2>"}<h2>${escape(d.getString("number"))}</h2><p>$details</p>
                <table><thead><tr><th>Description</th><th>Qty / hours</th><th>Unit price</th><th>Amount</th></tr></thead><tbody>$rows</tbody></table>
                <div class="totals">Subtotal: ${money(d.get("subtotal"))}<br>$tax<strong>${totalLabel(d)}: ${money(d.get("total"))}</strong></div>
                <p class="notes">${escape(d.optString("notes"))}$record</p></html>""".trimIndent()
        }
        private fun pdf(file:File,d:JSONObject) {
            val pdf=PdfDocument()
            try {
                // US Letter, in points.
                val width=612f;val height=792f;val left=48f;val right=width-48f
                val qtyColumn=372f;val priceColumn=468f
                var pageNumber=0;var page:PdfDocument.Page?=null;var y=0f
                val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(23,36,43)}
                val muted=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(82,100,110)}
                val accent=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(20,118,88)}
                val rule=Paint().apply {color=Color.rgb(221,221,221);strokeWidth=1f}
                fun canvas()=page!!.canvas
                fun newPage() {
                    page?.let {pdf.finishPage(it)}
                    page=pdf.startPage(PdfDocument.PageInfo.Builder(width.toInt(),height.toInt(),++pageNumber).create());y=56f
                }
                fun room(needed:Float) {if(y+needed>height-48f)newPage()}
                fun draw(text:String,x:Float,paint:Paint,size:Float,bold:Boolean=false,align:Paint.Align=Paint.Align.LEFT) {
                    paint.textSize=size;paint.typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT;paint.textAlign=align
                    canvas().drawText(text,x,y,paint)
                }
                fun wrap(text:String,paint:Paint,size:Float,limit:Float):List<String> {
                    paint.textSize=size;paint.typeface=Typeface.DEFAULT
                    val lines=mutableListOf<String>();var current=""
                    for(word in text.split(Regex("\\s+")).filter {it.isNotEmpty()}) {
                        val candidate=if(current.isEmpty())word else "$current $word"
                        if(paint.measureText(candidate)<=limit) {current=candidate;continue}
                        if(current.isNotEmpty())lines+=current
                        var rest=word
                        while(paint.measureText(rest)>limit) {
                            val cut=paint.breakText(rest,true,limit,null).coerceAtLeast(1)
                            lines+=rest.take(cut);rest=rest.drop(cut)
                        }
                        current=rest
                    }
                    if(current.isNotEmpty())lines+=current
                    return lines.ifEmpty {listOf("")}
                }
                val shop=d.getJSONObject("shop");val sample=isSample(d)
                newPage()
                draw(shop.getString("shop_name"),left,ink,19f,true)
                draw(heading(d),right,accent,19f,true,Paint.Align.RIGHT)
                y+=18;draw(shop.optString("shop_address"),left,muted,10f);draw(d.getString("number"),right,ink,11f,true,Paint.Align.RIGHT)
                y+=14;draw(shopContact(shop),left,muted,10f);draw("Date: "+d.getString("date"),right,muted,10f,align=Paint.Align.RIGHT)
                d.optString("status").takeIf {it.isNotBlank()}?.let {y+=14;draw("Status: "+it,right,muted,10f,align=Paint.Align.RIGHT)}
                if(sample) {
                    y+=30;canvas().drawRect(left,y-15f,right,y+9f,Paint().apply {color=Color.rgb(255,243,205)})
                    draw("SAMPLE ONLY - NOT A BILL",left+10f,ink,11f,true)
                }
                y+=34;draw("BILL TO",left,muted,9f,true)
                y+=16;draw(d.getString("customer"),left,ink,12f,true)
                d.optString("vehicle").takeIf {it.isNotBlank()}?.let {vehicle->
                    for(text in wrap("Vehicle: "+vehicle,muted,10f,right-left)) {y+=14;draw(text,left,muted,10f)}
                }
                y+=32;draw("DESCRIPTION",left,muted,9f,true);draw("QTY / HRS",qtyColumn,muted,9f,true,Paint.Align.RIGHT)
                draw("UNIT PRICE",priceColumn,muted,9f,true,Paint.Align.RIGHT);draw("AMOUNT",right,muted,9f,true,Paint.Align.RIGHT)
                y+=8;canvas().drawLine(left,y,right,y,rule)
                for(item in d.getJSONArray("items").objects()) {
                    val description=wrap(item.getString("description"),ink,11f,qtyColumn-left-60f)
                    room(description.size*15f+26f)
                    y+=18
                    description.forEachIndexed {index,text->
                        if(index>0)y+=15
                        draw(text,left,ink,11f)
                        if(index==0) {
                            draw(quantity(item.get("quantity")),qtyColumn,ink,11f,align=Paint.Align.RIGHT)
                            draw(money(item.get("unit_price")),priceColumn,ink,11f,align=Paint.Align.RIGHT)
                            draw(money(item.get("amount")),right,ink,11f,align=Paint.Align.RIGHT)
                        }
                    }
                    y+=13;draw(item.optString("kind").replaceFirstChar {it.uppercase()},left,muted,8.5f)
                    y+=8;canvas().drawLine(left,y,right,y,rule)
                }
                room(90f)
                y+=24;draw("Subtotal",priceColumn,muted,11f,align=Paint.Align.RIGHT);draw(money(d.get("subtotal")),right,ink,11f,align=Paint.Align.RIGHT)
                if(showsTax(d)) {
                    y+=18;draw("Tax on parts ("+BigDecimal(d.get("tax_rate").toString()).stripTrailingZeros().toPlainString()+"%)",priceColumn,muted,11f,align=Paint.Align.RIGHT)
                    draw(money(d.get("tax")),right,ink,11f,align=Paint.Align.RIGHT)
                }
                y+=24;draw(totalLabel(d),priceColumn,ink,13f,true,Paint.Align.RIGHT)
                draw(money(d.get("total")),right,accent,13f,true,Paint.Align.RIGHT)
                val notes=d.optString("notes")
                if(notes.isNotBlank()) {
                    y+=20
                    for(text in wrap(notes,muted,9f,right-left)) {room(14f);y+=13;draw(text,left,muted,9f)}
                }
                if(d.has("database_record")) {room(14f);y+=14;draw("Supabase record: "+d.getString("id"),left,muted,8f)}
                page?.let {pdf.finishPage(it)};file.outputStream().use {pdf.writeTo(it)}
            }finally{pdf.close()}
        }
    }
}

/** Invoices made only on this phone. No account, database or outbound message is involved. */
object LocalInvoice {
    const val ADDRESS=BuildConfig.SHOP_ADDRESS
    const val PHONE=BuildConfig.SHOP_PHONE
    fun document(taskId:String,customer:String,vehicle:String,rawItems:String,taxRate:BigDecimal,sample:Boolean,notes:String,date:LocalDate=LocalDate.now(),kind:String="invoice"):JSONObject {
        require(kind in setOf("invoice","estimate")) {"Choose invoice or estimate."}
        val name=customer.trim();require(name.isNotBlank() && name.length<=120) {"Give the invoice a customer name."}
        val car=vehicle.trim();require(car.length<=160) {"Keep the vehicle description under 160 characters."}
        val extra=notes.trim();require(extra.length<=600) {"Keep invoice notes under 600 characters."}
        val lines=InvoiceAmounts.lines(rawItems,30)
        val tax=InvoiceAmounts.tax(lines,taxRate)
        val subtotal=lines.fold(BigDecimal.ZERO) {sum,l->sum+l.amount}.setScale(2,RoundingMode.HALF_UP)
        // Repeating the same request rewrites the same files instead of piling up duplicate invoices.
        val key=listOf(taskId,kind,name,car,lines.joinToString("|"),taxRate.toPlainString(),sample.toString(),extra).joinToString("\u0000")
        val id=UUID.nameUUIDFromBytes(("alpha-local-invoice|"+key).toByteArray()).toString()
        val number=(if(sample)"SAMPLE-" else "")+(if(kind=="estimate")"EST-" else if(sample)"" else "INV-")+id.take(8).uppercase()
        val text=if(sample)listOf("SAMPLE ONLY - Not a bill. Made on this phone for testing; nothing was saved to a database or sent.",extra).filter {it.isNotBlank()}.joinToString(" ") else extra
        return obj("sample" to sample,"document_type" to kind,"id" to id,"number" to number,"date" to date.toString(),"storage" to "phone",
            "shop" to obj("shop_name" to InvoiceSample.SHOP,"shop_address" to ADDRESS,"shop_phone" to PHONE),
            "customer" to name,"vehicle" to car,
            "items" to arr(lines.map {obj("kind" to it.kind,"description" to it.description,"quantity" to it.quantity,"unit_price" to it.unitPrice,"amount" to it.amount)}),
            "subtotal" to subtotal,"tax_rate" to taxRate,"tax" to tax,"total" to subtotal+tax,"notes" to text)
    }
}
