package com.alphainternational.assistant

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/** Exercise real Android PDF generation/rendering. This diagnostic does not use or claim a database save. */
class InvoiceExportTest {
    @Test fun SampleInvoiceExportsAnOpenablePdfAndMatchingReceipts() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.getExternalFilesDir(null),"qa").apply {mkdirs()}
        val lines=InvoiceAmounts.lines("""[{"kind":"part","description":"Oil filter","quantity":1,"unit_price":15},{"kind":"part","description":"Engine oil","quantity":1,"unit_price":35},{"kind":"labor","description":"Oil change labor","quantity":1,"unit_price":60}]""")
        val subtotal=lines.fold(BigDecimal.ZERO){sum,line->sum+line.amount}
        val tax=InvoiceAmounts.tax(lines,BigDecimal("8.25"))
        val document=obj("sample" to true,"id" to "QA-EXPORT-ONLY-NO-DATABASE-SAVE","number" to "QA-SAMPLE-EXPORT",
            "date" to LocalDate.now().toString(),"customer" to "Demo Customer - export test only",
            "shop" to obj("shop_name" to InvoiceSample.SHOP,"shop_address" to "100 Main St, Springfield, TX 77001",
                "shop_phone" to "(713) 555-0100","shop_email" to "service@acme-auto.example"),
            "items" to arr(lines.map{obj("kind" to it.kind,"description" to it.description,"quantity" to it.quantity,"unit_price" to it.unitPrice,"amount" to it.amount)}),
            "subtotal" to subtotal,"tax_rate" to BigDecimal("8.25"),"tax" to tax,"total" to subtotal+tax,
            "notes" to "QA export test only. No Supabase connection or database write was used.")
        val files=InvoiceSample.export(document,folder)
        assertEquals(3,files.size)
        val receipt=JSONObject(File(folder,"QA-SAMPLE-EXPORT.json").readText())
        assertEquals("114.13",receipt.get("total").toString())
        assertTrue(File(folder,"QA-SAMPLE-EXPORT.html").readText().contains("QA export test only"))
        val pdfFile=File(folder,"QA-SAMPLE-EXPORT.pdf")
        ParcelFileDescriptor.open(pdfFile,ParcelFileDescriptor.MODE_READ_ONLY).use {descriptor->
            PdfRenderer(descriptor).use {renderer->
                assertEquals(1,renderer.pageCount)
                renderer.openPage(0).use {page->
                    val bitmap=Bitmap.createBitmap(page.width*2,page.height*2,Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        var nonWhite=0
                        for(y in 0 until bitmap.height step 8)for(x in 0 until bitmap.width step 8)if(bitmap.getPixel(x,y)!=Color.WHITE)nonWhite++
                        assertTrue("The PDF page should contain visible invoice content",nonWhite>30)
                        File(folder,"QA-SAMPLE-EXPORT.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                    }finally{bitmap.recycle()}
                }
            }
        }
    }
    private fun renderFirstPage(pdf:File,png:File):Int {
        ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY).use {descriptor->
            PdfRenderer(descriptor).use {renderer->
                renderer.openPage(0).use {page->
                    val bitmap=Bitmap.createBitmap(page.width*2,page.height*2,Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        png.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                    }finally{bitmap.recycle()}
                }
                return renderer.pageCount
            }
        }
    }
    @Test fun EstimatesAndDocumentsRenderReadablePdfs() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.getExternalFilesDir(null),"qa-docs").apply {mkdirs()}
        val estimate=LocalInvoice.document("qa-estimate","Maria Lopez","2015 Toyota Camry, 98,400 mi",
            """[{"kind":"part","description":"Front brake pads (ceramic)","quantity":1,"unit_price":89},{"kind":"labor","description":"Replace front pads, inspect rotors and calipers","quantity":1.5,"unit_price":120}]""",
            BigDecimal("8.25"),false,"Estimate valid for 30 days.",kind="estimate")
        val files=InvoiceSample.export(estimate,folder)
        assertEquals(1,renderFirstPage(File(folder,files.first()),File(folder,"QA-ESTIMATE.png")))
        val made=DocumentPdf.create(folder,"Brake Special Flyer","Brake Special This Week",
            "## Front brake pads installed\n**Only 179 dollars** with free rotor inspection.\n\n- Ceramic pads\n- Same-day service\n1. Call (713) 555-0100\n2. Mention this flyer\n\n| Service | Price |\n|---|---|\n| Pads | 179 |\n| Rotors | 149 |\n\n---\n[ ] Bring your keys",true)
        assertEquals(listOf("brake-special-flyer.pdf","brake-special-flyer.md"),made.files)
        assertEquals(1,renderFirstPage(File(folder,made.files.first()),File(folder,"QA-DOCUMENT.png")))
    }
    @Test fun LocalInvoiceMakesAnOpenablePdfWithoutAnyConnection() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.getExternalFilesDir(null),"qa-local").apply {mkdirs()}
        val document=LocalInvoice.document("qa-local","Demo Customer","2018 Honda Civic",
            """[{"kind":"part","description":"Oil filter","quantity":1,"unit_price":15},{"kind":"labor","description":"Brake pad replacement, front axle, including inspection of rotors and calipers","quantity":1.5,"unit_price":120}]""",
            BigDecimal.ZERO,false,"")
        val files=InvoiceSample.export(document,folder)
        assertEquals(document.getString("number")+".pdf",files.first())
        ParcelFileDescriptor.open(File(folder,files.first()),ParcelFileDescriptor.MODE_READ_ONLY).use {descriptor->
            PdfRenderer(descriptor).use {renderer->
                assertEquals(1,renderer.pageCount)
                renderer.openPage(0).use {page->
                    assertEquals(612,page.width);assertEquals(792,page.height)
                    val bitmap=Bitmap.createBitmap(page.width*2,page.height*2,Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        File(folder,"QA-LOCAL-INVOICE.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                    }finally{bitmap.recycle()}
                }
            }
        }
    }
}
