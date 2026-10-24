package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

/** The live phone, 2026-10-11: 24 months of the S&P 500 came out as a 12-month stripe on an axis that started at 0, with the numbers printed over one another. */
class ChartLayoutTest {
    private val monthly=listOf(5705.45,6032.38,5881.63,6040.53,5954.5,5611.85,5569.06,5911.69,6204.95,6339.39,6460.26,6688.46,6849.09,6845.5,6939.03,6878.88,6528.52,7209.01,7580.06,7499.36,7489.72,7686.14,7651.54,7811.54)

    @Test fun aTwoYearMonthlyLineKeepsEveryPointAndSaysWhenThereAreTooMany() {
        val lines=listOf("type: line","title: S&P 500")+monthly.mapIndexed {i,v->"Month ${i+1}: $v"}
        val chart=DocumentPdf.chart(lines)
        assertEquals(24,chart.points.size)
        assertEquals(monthly.last(),chart.points.last().second,0.0001)
        val tooMany=listOf("type: line")+(1..61).map {"M$it: $it"}
        val error=runCatching {DocumentPdf.chart(tooMany)}.exceptionOrNull()
        assertNotNull("more than 60 points is an error the bot can fix, never a quiet cut",error)
        assertTrue(error!!.message,error.message!!.contains("61") && error.message!!.contains("60"))
        assertEquals(60,DocumentPdf.chart(listOf("type: bar")+(1..60).map {"M$it: $it"}).points.size)
        assertTrue(runCatching {DocumentPdf.chart(listOf("type: pie")+(1..13).map {"S$it: $it"})}.isFailure)
        assertEquals(12,DocumentPdf.chart(listOf("type: pie")+(1..12).map {"S$it: $it"}).points.size)
    }

    @Test fun aLineFollowsItsDataButBarsStartAtZero() {
        val line=ChartLayout.axis("line",monthly)
        assertTrue("the axis starts near the data, not at 0: $line",line.first()>=4000.0 && line.first()<=5705.45)
        assertTrue(line.last()>=7811.54 && line.last()<=9000.0)
        val bars=ChartLayout.axis("bar",monthly)
        assertEquals(0.0,bars.first(),0.0)
        assertTrue(bars.last()>=7811.54)
        // Round numbers, evenly spaced
        val steps=line.zipWithNext {a,b->b-a}
        assertTrue(steps.toString(),steps.all {Math.abs(it-steps[0])<1e-6})
        assertTrue(line.joinToString(),line.all {it%100==0.0 || it%250==0.0 || it%500==0.0 || it%1000==0.0})
        assertTrue(line.size in 3..7)
    }

    @Test fun anAxisHandlesNegativesFlatDataAndSmallNumbers() {
        assertTrue(ChartLayout.axis("line",listOf(-3.0,2.0,5.0)).first()<=-3.0)
        assertEquals(0.0,ChartLayout.axis("line",listOf(0.5,0.9,1.4)).first(),0.0)
        val flat=ChartLayout.axis("line",listOf(100.0,100.0,100.0))
        assertTrue(flat.first()<100.0 && flat.last()>100.0)
        val bar=ChartLayout.axis("bar",listOf(-5.0,12.0))
        assertTrue(bar.first()<=-5.0 && bar.last()>=12.0)
        assertEquals(listOf(0.0,0.5,1.0),ChartLayout.axis("bar",listOf(0.0,0.9),lines=2))
        assertEquals(2.5,ChartLayout.niceStep(2.2),0.0)
        assertEquals(500.0,ChartLayout.niceStep(430.0),0.0)
        assertEquals(1.0,ChartLayout.niceStep(0.0),0.0)
    }

    @Test fun labelsThatWouldOverlapAreLeftOutButTheImportantOnesStay() {
        // 24 points 16 wide, each label 40 wide: no two neighbours fit
        val centers=(0 until 24).map {20f+it*16f}
        val widths=List(24) {40f}
        val shown=ChartLayout.labelled(centers,widths,listOf(0,23,5,11))
        assertTrue(shown.containsAll(listOf(0,23,5,11)))
        val sorted=shown.sorted()
        for((a,b) in sorted.zipWithNext()) assertTrue("labels $a and $b touch",centers[b]-centers[a]>=40f)
        assertTrue("many still fit",shown.size>=6)
        // Plenty of room: every label is shown
        assertEquals((0 until 5).toSet(),ChartLayout.labelled((0 until 5).map {50f+it*60f},List(5) {30f}))
        // Only the allowed ones fill in after the important ones
        assertEquals(setOf(0,2),ChartLayout.labelled((0 until 5).map {50f+it*60f},List(5) {30f},listOf(0)) {it==2})
        // The first listed wins when two important ones collide
        assertEquals(setOf(0),ChartLayout.labelled(listOf(10f,20f),listOf(30f,30f),listOf(0,1)).filter {it==0 || it==1}.toSet())
    }

    /** The owner's screenshot (2026-10-12): the line ran through "6,460.26" (a rise) and "5,705.45" (the first point). */
    @Test fun aNumberSitsOnTheSideOfItsPointTheLineIsNotOn() {
        val v=listOf(5.0,6.0,5.5,4.0,4.5,9.0,9.0,7.0)
        assertEquals("first point, the line leaves upward: under it",ChartLayout.Place(0,true),ChartLayout.place(v,0))
        assertEquals("a peak: above, centred",ChartLayout.Place(0,false),ChartLayout.place(v,1))
        assertEquals("falling through: to the right",ChartLayout.Place(1,false),ChartLayout.place(v,2))
        assertEquals("a dip: under it",ChartLayout.Place(0,true),ChartLayout.place(v,3))
        assertEquals("rising through: to the left",ChartLayout.Place(-1,false),ChartLayout.place(v,4))
        assertEquals("a flat top: above",ChartLayout.Place(0,false),ChartLayout.place(v,5))
        assertEquals("last point arrived from above: under it",ChartLayout.Place(0,true),ChartLayout.place(v,7))
        assertEquals("last point arrived from below: above it",ChartLayout.Place(0,false),ChartLayout.place(listOf(1.0,2.0),1))
        assertEquals(ChartLayout.Place(0,false),ChartLayout.place(listOf(3.0),0))
    }

    private fun png(vararg chunks:Pair<String,ByteArray>):ByteArray {
        val out=java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(),0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A))
        for((kind,data) in chunks) {
            out.write(java.nio.ByteBuffer.allocate(4).putInt(data.size).array());out.write(kind.toByteArray());out.write(data)
            out.write(java.nio.ByteBuffer.allocate(4).putInt(java.util.zip.CRC32().apply {update(kind.toByteArray());update(data)}.value.toInt()).array())
        }
        return out.toByteArray()
    }

    /** A chart picture says which chart maker drew it; one with no note (drawn before) counts as old, so it is drawn again and never handed back. */
    @Test fun aChartPictureCarriesTheVersionOfTheChartMakerThatDrewIt() {
        val plain=png("IHDR" to ByteArray(13),"IDAT" to ByteArray(40) {7},"IEND" to ByteArray(0))
        assertEquals(0,Drawings.chartEngine(plain.copyOf(256)))
        val stamped=Drawings.stamped(plain)
        assertEquals(Drawings.CHART_ENGINE,Drawings.chartEngine(stamped.copyOf(minOf(256,stamped.size))))
        assertEquals("only a note is added",plain.size+12+"AlphaChart".length+1+Drawings.CHART_ENGINE.toString().length,stamped.size)
        assertTrue("the picture itself is untouched",stamped.toList().containsAll(plain.toList().takeLast(60)) && stamped.takeLast(60)==plain.takeLast(60))
        assertEquals("an older stamp reads as that older version",1,Drawings.chartEngine(Drawings.stamped(plain,1)))
        assertEquals("not a PNG: left alone",3,Drawings.stamped(byteArrayOf(1,2,3)).size)
        assertEquals("garbage reads as old",0,Drawings.chartEngine(ByteArray(64) {1}))
    }

    @Test fun theImportantPointsAreTheEndsTheHighestAndTheLowest() {
        assertEquals("the first and last point, the highest (here the last) and the lowest",listOf(0,23,6),ChartLayout.important(monthly))
        assertEquals(emptyList<Int>(),ChartLayout.important(emptyList()))
    }
}
