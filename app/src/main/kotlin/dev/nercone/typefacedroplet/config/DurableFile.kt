package dev.nercone.typefacedroplet.config

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Replaces files that must survive a power loss whole, such as the configuration and the font
 * library. The new content is written to a temporary file in the same directory and synced to
 * the storage before it replaces the old file by a rename, so the file is always either the old
 * content or the new one. A rename alone is not enough: f2fs, the usual file system of `/data`,
 * may otherwise keep the rename and lose the content, leaving an empty file.
 */
object DurableFile {
    fun write(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IOException("cannot replace $file")
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    fun write(file: File, text: String) = write(file, text.toByteArray())
}
