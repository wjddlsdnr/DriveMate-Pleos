package com.example.pleosconnect.domain.rest

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A-owned minimal checkpoint in an app-private directory supplied by the composition owner. */
class FileRestSessionStore(private val file: File) : RestSessionStore {
    @Synchronized override fun load(): RestCheckpoint? {
        if (!file.exists()) return null
        return try {
            val fields = file.readText().split(':')
            require(fields.size == 3 && fields[0] == "1")
            val remaining = fields[1].toLong()
            require(remaining in 0..10_800_000L && fields[2] in setOf("0", "1"))
            RestCheckpoint(remaining, fields[2] == "1")
        } catch (_: IllegalArgumentException) { file.delete(); null }
    }
    @Synchronized override fun save(value: RestCheckpoint?) {
        if (value == null) { Files.deleteIfExists(file.toPath()); return }
        require(value.remainingMillis in 0..10_800_000L)
        val pending = File(file.parentFile, file.name + ".pending")
        try {
            pending.outputStream().use { stream ->
                stream.write("1:${value.remainingMillis}:${if (value.alarmConsumed) 1 else 0}".toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }
}
