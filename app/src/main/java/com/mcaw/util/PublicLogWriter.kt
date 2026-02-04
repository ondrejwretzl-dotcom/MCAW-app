package com.mcaw.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

object PublicLogWriter {
    private val RELATIVE_DIR = "${Environment.DIRECTORY_DOWNLOADS}/MCAW"

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
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
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

    fun appendLogLine(
        context: Context,
        fileName: String,
        line: String
    ) {
        val lineWithBreak = if (line.endsWith('\n')) line else "$line\n"
        runCatching {
            val resolver = context.contentResolver
            val uri = findExistingUri(context, fileName) ?: createLogFile(context, fileName)
            if (uri != null) {
                val output = runCatching { resolver.openOutputStream(uri, "wa") }.getOrNull()
                    ?: resolver.openOutputStream(uri, "w")
                output?.use { out ->
                    out.write(lineWithBreak.toByteArray())
                }
                return
            }
        }.getOrElse {
            appendInternalFallback(context, fileName, lineWithBreak)
            return
        }
    }

    private fun writeInternalFallback(context: Context, fileName: String, content: String) {
        runCatching {
            context.openFileOutput(fileName, Context.MODE_PRIVATE).use { out ->
                out.write(content.toByteArray())
            }
        }
    }

    private fun appendInternalFallback(context: Context, fileName: String, content: String) {
        runCatching {
            context.openFileOutput(fileName, Context.MODE_APPEND).use { out ->
                out.write(content.toByteArray())
            }
        }
    }

    private fun createLogFile(context: Context, fileName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
        }
        return resolver.insert(MediaStore.Files.getContentUri("external"), values)
    }

    private fun findExistingUri(context: Context, fileName: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(fileName, "$RELATIVE_DIR/")
        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return android.content.ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }
}
