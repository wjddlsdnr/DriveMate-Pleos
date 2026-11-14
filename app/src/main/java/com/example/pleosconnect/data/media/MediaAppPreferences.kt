package com.example.pleosconnect.data.media

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** B-only, bounded profile -> component IDs in noBackupFilesDir. No account/token/display names. */
class MediaAppPreferences(private val file: File) {
    @Synchronized fun read(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        require(file.length() <= 100_000)
        return DataInputStream(FileInputStream(file)).use { input ->
            require(input.readInt() == 1)
            val count = input.readInt().also { require(it in 0..100) }
            val result = linkedMapOf<String, String>()
            repeat(count) {
                val profile = input.readUTF(); val component = input.readUTF()
                validate(profile, component); require(result.put(profile, component) == null)
            }
            require(input.read() == -1)
            result
        }
    }
    @Synchronized fun save(profile: String, component: String?) {
        require(profile.isNotBlank() && profile.length <= 100)
        component?.let { validate(profile, it) }
        val records = read().toMutableMap()
        if (component == null) records.remove(profile) else records[profile] = component
        write(records)
    }
    @Synchronized fun retainProfiles(ids: Set<String>) { write(read().filterKeys { it in ids }) }
    private fun write(records: Map<String, String>) {
        require(records.size <= 100)
        val parent = requireNotNull(file.parentFile); check(parent.isDirectory || parent.mkdirs())
        val temporary = File(parent, "${file.name}.new")
        try {
            FileOutputStream(temporary).use { stream ->
                val out = DataOutputStream(stream); out.writeInt(1); out.writeInt(records.size)
                records.forEach { (profile, component) -> validate(profile, component); out.writeUTF(profile); out.writeUTF(component) }
                out.flush(); stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    private fun validate(profile: String, component: String) {
        require(profile.isNotBlank() && profile.length <= 100)
        require(component.length in 3..512 && component.count { it == '/' } == 1 && !component.any(Char::isWhitespace))
    }
}
