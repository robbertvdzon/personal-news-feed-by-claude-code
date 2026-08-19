package com.vdzon.newsfeedbackend.admin.domain

import com.vdzon.newsfeedbackend.auth.AuthService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.nio.file.Files
import java.nio.file.Path

/**
 * `deleteAudioDir` is private; deze tests lopen er via [AdminServiceImpl.deleteUser] naartoe.
 */
@ExtendWith(MockitoExtension::class)
class AdminServiceImplDeleteAudioDirTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `a traversal username deletes nothing outside the users directory`() {
        val dataDir = root.resolve("data")
        // Bestand náást de datadirectory, precies waar de traversal-naam naartoe wijst.
        val outsideDir = root.resolve("outside").resolve("audio")
        Files.createDirectories(outsideDir)
        val outsideFile = outsideDir.resolve("secret.mp3")
        Files.write(outsideFile, byteArrayOf(1, 2, 3))

        val auth = mock(AuthService::class.java)
        `when`(auth.deleteUser("../../outside")).thenReturn(true)
        val service = AdminServiceImpl(auth, dataDir.toString())

        service.deleteUser("../../outside", "admin")

        assertTrue(Files.exists(outsideFile), "bestand naast de datadirectory mag niet verwijderd zijn")
        assertTrue(Files.exists(outsideDir), "map naast de datadirectory mag niet verwijderd zijn")
        // Het account zelf wordt nog steeds verwijderd.
        verify(auth).deleteUser("../../outside")
    }

    @Test
    fun `a valid username still cleans up its audio directory`() {
        val dataDir = root.resolve("data")
        val audioDir = dataDir.resolve("users").resolve("alice").resolve("audio")
        Files.createDirectories(audioDir.resolve("nested"))
        Files.write(audioDir.resolve("a.mp3"), byteArrayOf(1))
        Files.write(audioDir.resolve("nested").resolve("b.mp3"), byteArrayOf(2))

        val auth = mock(AuthService::class.java)
        `when`(auth.deleteUser("alice")).thenReturn(true)
        val service = AdminServiceImpl(auth, dataDir.toString())

        service.deleteUser("alice", "admin")

        assertFalse(Files.exists(audioDir), "audio-map van een geldige naam hoort volledig opgeruimd te zijn")
        assertTrue(Files.exists(dataDir.resolve("users").resolve("alice")), "alleen de audio-map wordt opgeruimd")
    }
}
