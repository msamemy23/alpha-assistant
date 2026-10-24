package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GamesTest {
    private val assets=listOf(File("src/main/assets/games"),File("app/src/main/assets/games")).first {it.isDirectory}

    @Test fun everyKindHasATemplateThatWorksFromTheBotsFlatFiles() {
        for(kind in Games.kinds) {
            val html=Games.rewrite(File(assets,"templates/${kind.id}.html").readText())
            assertTrue("${kind.id} has CONFIG",html.contains("const CONFIG"))
            assertFalse("${kind.id} still points at ../",html.contains("../"))
            assertTrue("${kind.id} loads juice.js",html.contains("<script src=\"juice.js\">"))
            val config=Games.configOf(html)
            assertTrue("${kind.id} config block",config.startsWith("const CONFIG") && config.trimEnd().endsWith("};"))
            assertTrue("${kind.id} says what to customize",config.contains("CUSTOMIZE:"))
        }
        assertTrue(File(assets,"juice.js").length()>10_000)
    }

    @Test fun theThreeDGameFindsItsEngineBesideThePage() {
        val html=Games.rewrite(File(assets,"templates/runner3d.html").readText())
        assertTrue(html.contains("{\"imports\":{\"three\":\"three.module.min.js\"}}"))
        assertFalse(html.contains("addons"))
        Games.libraries.getValue("three").forEach {assertTrue("$it bundled",File(assets,"lib/$it").isFile)}
        // three.module imports three.core.js by a relative name, so they have to sit side by side.
        assertTrue(File(assets,"lib/three.module.min.js").readText().contains("from\"./three.core.js\""))
    }

    @Test fun everyLibraryIsBundled() {
        for((name,files) in Games.libraries) files.forEach {assertTrue("$name: $it",File(assets,"lib/$it").isFile)}
    }

    @Test fun ratherThanGuessingTheKindMustBeExact() {
        val e=assertThrows(IllegalArgumentException::class.java) {Games.kindOf("flappy")}
        assertTrue(e.message!!.contains("arcade"))
        assertEquals("puzzle",Games.kindOf(" Puzzle ").id)
    }
}
