package com.civitared.promptdataset.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter

class DatasetExporter(
    private val contentResolver: ContentResolver,
) {
    suspend fun exportJsonl(uri: Uri, entries: List<DatasetEntry>): Int =
        withContext(Dispatchers.IO) {
            val stream = contentResolver.openOutputStream(uri, "wt")
                ?: error("Не удалось открыть выбранный файл")
            BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8)).use { writer ->
                entries.forEach { entry ->
                    val row = JSONObject()
                        .put("command", entry.command)
                        .put("input", entry.input)
                        .put("output", entry.output)
                    writer.write(row.toString())
                    writer.newLine()
                }
            }
            entries.size
        }
}
