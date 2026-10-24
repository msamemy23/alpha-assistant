package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

class FileEditsTest {
    private val page="<h1>Alpha Auto</h1>\n  <p class=\"lead\">Honest repairs.</p>\n  <p class=\"lead\">Fair prices.</p>\n<footer>Houston</footer>\n"

    @Test fun anExactUniqueMatchIsReplaced() {
        val r=FileEdits.replace(page,"<h1>Alpha Auto</h1>","<h1>Acme</h1>") as FileEdits.Result.Done
        assertTrue(r.text.startsWith("<h1>Acme</h1>"))
        assertEquals(1,r.replaced)
        assertEquals("exact",r.how)
    }

    @Test fun aMatchThatAppearsTwiceNeedsMoreContextOrAll() {
        val failed=FileEdits.replace(page,"<p class=\"lead\">","<p class=\"big\">") as FileEdits.Result.Failed
        assertTrue(failed.reason.contains("2 times"))
        val all=FileEdits.replace(page,"<p class=\"lead\">","<p class=\"big\">",all=true) as FileEdits.Result.Done
        assertEquals(2,all.replaced)
        assertFalse(all.text.contains("class=\"lead\""))
    }

    @Test fun differentSpacingStillMatchesWhenItIsUnique() {
        val r=FileEdits.replace(page,"<p class=\"lead\">Honest\n      repairs.</p>","<p>Honest, fast repairs.</p>") as FileEdits.Result.Done
        assertEquals("ignoring spacing",r.how)
        assertTrue(r.text.contains("<p>Honest, fast repairs.</p>"))
        assertTrue(r.text.contains("Fair prices."))
    }

    @Test fun aMissingTextReturnsTheClosestLines() {
        val failed=FileEdits.replace(page,"<footer>Houston Texas location</footer>","<footer>x</footer>") as FileEdits.Result.Failed
        assertTrue(failed.reason.contains("isn't in the file"))
        assertTrue(failed.closest.contains("footer"))
    }

    @Test fun emptyOrUnchangedEditsAreRefused() {
        assertTrue(FileEdits.replace(page,"","x") is FileEdits.Result.Failed)
        assertTrue(FileEdits.replace(page,"Houston","Houston") is FileEdits.Result.Failed)
    }

    @Test fun aSectionCanBeReplacedWithoutTouchingTheRest() {
        val site="<body>\n<!-- section:hero -->\n<h1>Old</h1>\n<!-- /section:hero -->\n<!-- section:services -->\n<p>Brakes</p>\n<!-- /section:services -->\n</body>"
        assertEquals(listOf("hero","services"),FileEdits.sections(site))
        val r=FileEdits.replaceSection(site,"hero","<h1>New hero</h1>") as FileEdits.Result.Done
        assertTrue(r.text.contains("<h1>New hero</h1>"))
        assertFalse(r.text.contains("Old"))
        assertTrue(r.text.contains("<p>Brakes</p>"))
        assertTrue(r.text.contains("<!-- section:hero -->") && r.text.contains("<!-- /section:hero -->"))
        val missing=FileEdits.replaceSection(site,"gallery","<div/>") as FileEdits.Result.Failed
        assertTrue(missing.reason.contains("hero, services"))
        assertThrows(IllegalArgumentException::class.java) {FileEdits.replaceSection(site,"bad name!","x")}
    }
}
