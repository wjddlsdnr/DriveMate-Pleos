package com.example.pleosconnect.data.profile

import com.example.pleosconnect.contract.model.DriverPreferences
import com.example.pleosconnect.contract.model.DrivingMode
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ProfileSnapshot(val profiles: List<DriverPreferences> = emptyList(), val selectedId: String? = null)

interface ProfileStorage {
    fun load(): ProfileSnapshot
    /** Return only after the complete snapshot is committed; failure must preserve the old file. */
    fun save(snapshot: ProfileSnapshot)
}

/** Versioned, bounded local data. The caller supplies a file inside Android noBackupFilesDir. */
class FileProfileStorage(private val file: File) : ProfileStorage {
    override fun load(): ProfileSnapshot {
        if (!file.exists()) return ProfileSnapshot()
        require(file.length() in 1..1_048_576) { "Invalid profile storage size" }
        return DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readInt() == 0x444D5031 && input.readInt() == 1) { "Unsupported profile storage" }
            val selected = input.nullableText()
            val count = input.readInt().also { require(it in 0..20) }
            val profiles = List(count) {
                DriverPreferences(input.readUTF(), input.readUTF(), input.nullableText()?.let(DrivingMode::valueOf),
                    input.textList(), input.textList(), input.textList(), input.textList(), input.nullableText())
            }
            require(input.read() == -1) { "Unexpected profile storage data" }
            ProfileSnapshot(profiles, selected)
        }
    }
    override fun save(snapshot: ProfileSnapshot) {
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Profile directory unavailable" }
        val temporary = File(parent, "${file.name}.new")
        try {
            FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(0x444D5031); output.writeInt(1)
                output.nullableText(snapshot.selectedId)
                output.writeInt(snapshot.profiles.size)
                snapshot.profiles.forEach { profile ->
                    output.writeUTF(profile.profileId); output.writeUTF(profile.displayName)
                    output.nullableText(profile.previousMode?.name)
                    output.textList(profile.genres); output.textList(profile.artists)
                    output.textList(profile.playlists); output.textList(profile.contentPreferences)
                    output.nullableText(profile.voiceGuidancePreference)
                }
                output.flush(); stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    private fun DataInputStream.nullableText(): String? = if (readBoolean()) readUTF() else null
    private fun DataOutputStream.nullableText(value: String?) { writeBoolean(value != null); if (value != null) writeUTF(value) }
    private fun DataInputStream.textList(): List<String> = List(readInt().also { require(it in 0..20) }) { readUTF() }
    private fun DataOutputStream.textList(values: List<String>) { writeInt(values.size); values.forEach { writeUTF(it) } }
}
