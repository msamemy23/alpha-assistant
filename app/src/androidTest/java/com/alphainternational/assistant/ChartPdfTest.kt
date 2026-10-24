package com.alphainternational.assistant

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** A document with pie, bar and line charts renders to a real PDF (kept in the app's external files for a look). */
class ChartPdfTest {
    @Test fun documentWithChartsRenders() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.getExternalFilesDir(null),"qa").apply {mkdirs()}
        val made=DocumentPdf.create(folder,"chart-check","Shop numbers","""
            Revenue for the week.
            ```chart
            type: pie
            title: Revenue mix
            Repairs: 40
            Parts: 30
            Labor: 20
            Maintenance: 10
            ```
            ```chart bar
            title: Cars per day
            Mon: 6
            Tue: 9
            Wed: 4
            Thu: 11
            Fri: 8
            Sat: 3
            ```
            ```chart line
            title: Monthly sales (${'$'})
            Jan: 18,400
            Feb: 21,250
            Mar: 19,900
            Apr: 24,600
            ```
        """.trimIndent(),letterhead=true)
        assertTrue(made.pages>=1)
        assertTrue(File(folder,"chart-check.pdf").length()>2000)
    }

    /** Two years of an index as a line chart (the live phone drew only the first 12 months on an axis from 0, with the numbers over one another). Kept for a look. */
    @Test fun twoYearsOfMonthlyValuesAreAllDrawnOnAnAxisThatFollowsThem() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.getExternalFilesDir(null),"qa").apply {mkdirs()}
        val months=listOf("Oct 2024","Nov 2024","Dec 2024","Jan 2025","Feb 2025","Mar 2025","Apr 2025","May 2025","Jun 2025","Jul 2025","Aug 2025","Sep 2025","Oct 2025","Nov 2025","Dec 2025","Jan 2026","Feb 2026","Mar 2026","Apr 2026","May 2026","Jun 2026","Jul 2026","Aug 2026","Sep 2026")
        val closes=listOf(5705.45,6032.38,5881.63,6040.53,5954.5,5611.85,5569.06,5911.69,6204.95,6339.39,6460.26,6688.46,6849.09,6845.5,6939.03,6878.88,6528.52,7209.01,7580.06,7499.36,7489.72,7686.14,7651.54,7811.54)
        val chart=DocumentPdf.chart(listOf("type: line","title: S&P 500 monthly closes, Oct 2024 to Sep 2026")+months.zip(closes).map {(m,c)->"$m: $c"})
        assertEquals(24,chart.points.size)
        val picture=DocumentPdf.chartPicture(chart)
        val file=File(folder,"line-24-months.png")
        file.outputStream().use {picture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        assertTrue(file.length()>5000)
        // the line must reach the right edge (the last month is drawn), not stop halfway
        val last=(picture.width*0.93).toInt()
        var inked=false
        for(x in last until picture.width-4) for(y in 0 until picture.height) {if(picture.getPixel(x,y)!=android.graphics.Color.WHITE) inked=true}
        assertTrue("something is drawn near the right edge (the last month)",inked)
    }
}
