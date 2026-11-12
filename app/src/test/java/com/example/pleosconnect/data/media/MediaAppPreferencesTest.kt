package com.example.pleosconnect.data.media

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaAppPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun defaultsSurviveReloadAndStayIsolatedByProfile() {
        val file = File(temporary.root, "prefs.bin")
        MediaAppPreferences(file).apply { save("driver-a", "music.a/Service"); save("driver-b", "music.b/Service") }
        val reloaded = MediaAppPreferences(file)
        assertEquals(mapOf("driver-a" to "music.a/Service", "driver-b" to "music.b/Service"), reloaded.read())
        reloaded.save("driver-a", null)
        assertEquals(mapOf("driver-b" to "music.b/Service"), reloaded.read())
        reloaded.retainProfiles(emptySet()); assertTrue(reloaded.read().isEmpty())
    }
    @Test fun corruptFileIsNotSilentlyReplacedByNewDefault() {
        val file = File(temporary.root, "prefs.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val before = file.readBytes()
        assertTrue(runCatching { MediaAppPreferences(file).save("driver", "music/Service") }.isFailure)
        assertArrayEquals(before, file.readBytes())
    }
}
