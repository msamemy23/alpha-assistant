package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

/** The Library is the one list of everything made or kept: what counts as which kind, and what stays out of it. */
class LibraryKindsTest {
    @Test fun whatTheBotsMakeKeepsItsKinds() {
        assertEquals("document",Library.kindOf("invoice-12.pdf"))
        assertEquals("page",Library.kindOf("index.html"))
        assertEquals("sheet",Library.kindOf("customers.csv"))
        assertNull(Library.kindOf("notes.txt"))
    }

    @Test fun photosNotesAndOtherFilesJoinThemInTheLibrary() {
        assertEquals("photo",Library.otherKind("photo-1700000000.jpg"))
        assertEquals("photo",Library.otherKind("gif-5.gif"))
        assertEquals("note",Library.otherKind("notes.txt"))
        assertEquals("note",Library.otherKind("plan.md"))
        assertEquals("note",Library.otherKind("settings.json"))
        assertEquals("file",Library.otherKind("backup.zip"))
    }

    @Test fun aBotsOwnWorkingFilesStayOut() {
        assertNull(Library.otherKind("shot-3.png"))
        assertNull(Library.otherKind("sheet-1.png"))
        assertNull(Library.otherKind("_draft.html"))
        assertNull(Library.otherKind(".incoming-123"))
    }

    @Test fun everyKindHasAPlainName() {
        assertEquals("Photo",Library.label("photo"))
        assertEquals("Note",Library.label("note"))
        assertEquals("File",Library.label("file"))
        assertEquals("Document",Library.label("document"))
        assertEquals("Website or app",Library.label("page"))
        assertEquals("Spreadsheet",Library.label("sheet"))
    }
}
