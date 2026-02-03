package com.mcaw.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

object PublicLogWriter {
    fun writeTextFile(
        context: Context,
        fileName: String,
        content: String
    ): Uri? {
        return runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/MCAW"
                )
            }
            val uri =
                resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return null
            resolver.openOutputStream(uri, "w")?.use { out ->
                out.write(content.toByteArray())
            }
            uri
        }.getOrElse {
            writeInternalFallback(context, fileName, content)
            null
        }
    }

    private fun writeInternalFallback(context: Context, fileName: String, content: String) {
        runCatching {
            context.openFileOutput(fileName, Context.MODE_PRIVATE).use { out ->
                out.write(content.toByteArray())
            }
        }
    }
}
