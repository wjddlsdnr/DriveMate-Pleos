package com.example.pleosconnect.data.profile

import android.content.Context
import java.io.File

/** One repository/mutex per app process; never retains an Activity. */
object AppProfileRepository {
    @Volatile private var instance: LocalDriverProfileRepository? = null
    fun get(context: Context): LocalDriverProfileRepository = instance ?: synchronized(this) {
        instance ?: LocalDriverProfileRepository(FileProfileStorage(
            File(context.applicationContext.noBackupFilesDir, "profiles/profiles.bin")))
            .also { instance = it }
    }
}
