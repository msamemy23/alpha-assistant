package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

class ChartTest {
    @Test fun chartBlocksBecomeRealCharts() {
        val blocks=DocumentPdf.blocks("""
            # Shop revenue
            Where the money came from.
            ```chart
            type: pie
            title: Revenue mix
            Repairs: 40%
            Parts: 30
            - Labor: ${'$'}1,200.50
            Maintenance | 10
            ```
            After the chart.
        """.trimIndent())
        val chart=blocks.filterIsInstance<DocBlock.Chart>().single()
        assertEquals("pie",chart.type)
        assertEquals("Revenue mix",chart.title)
        assertEquals(listOf("Repairs" to 40.0,"Parts" to 30.0,"Labor" to 1200.5,"Maintenance" to 10.0),chart.points)
        assertTrue(blocks.last() is DocBlock.Paragraph)
    }
    @Test fun chartTypeCanSitOnTheFenceAndDefaultsToBars() {
        assertEquals("line",(DocumentPdf.blocks("```chart line\nJan: 5\nFeb: 9\n```").single() as DocBlock.Chart).type)
        assertEquals("bar",(DocumentPdf.blocks("```chart\nMon: 3\nTue: 4\n```").single() as DocBlock.Chart).type)
        assertTrue(runCatching {DocumentPdf.blocks("```chart\ntype: pie\nno numbers here\n```")}.isFailure)
    }
    @Test fun numbersReadNaturally() {
        assertEquals("1,200",DocumentPdf.number(1200.0))
        assertEquals("12.5",DocumentPdf.number(12.5))
    }
}
