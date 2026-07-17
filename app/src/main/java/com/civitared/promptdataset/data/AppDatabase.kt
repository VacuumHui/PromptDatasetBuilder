package com.civitared.promptdataset.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE dataset_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id TEXT NOT NULL UNIQUE,
                command_text TEXT NOT NULL,
                input_text TEXT NOT NULL,
                output_text TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE image_state (
                source_id TEXT PRIMARY KEY,
                status TEXT NOT NULL,
                processed_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_entries_updated ON dataset_entries(updated_at DESC)")
        db.execSQL("CREATE INDEX idx_image_state_status ON image_state(status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun getProcessedIds(): Set<String> {
        val result = LinkedHashSet<String>()
        readableDatabase.query(
            "image_state",
            arrayOf("source_id"),
            "status IN (?, ?)",
            arrayOf(STATUS_COMPLETED, STATUS_IGNORED),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.getString(0)
        }
        return result
    }

    fun saveEntry(
        sourceImageId: String,
        command: String,
        input: String,
        output: String,
    ): Long {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existingId = findEntryId(db, sourceImageId)
            val values = ContentValues().apply {
                put("source_id", sourceImageId)
                put("command_text", command.trim())
                put("input_text", input.trim())
                put("output_text", output.trim())
                put("updated_at", now)
                if (existingId == null) put("created_at", now)
            }

            val entryId = if (existingId == null) {
                db.insertOrThrow("dataset_entries", null, values)
            } else {
                db.update(
                    "dataset_entries",
                    values,
                    "id = ?",
                    arrayOf(existingId.toString()),
                )
                existingId
            }

            upsertState(db, sourceImageId, STATUS_COMPLETED, now)
            db.setTransactionSuccessful()
            return entryId
        } finally {
            db.endTransaction()
        }
    }

    fun markIgnored(sourceImageId: String) {
        upsertState(writableDatabase, sourceImageId, STATUS_IGNORED, System.currentTimeMillis())
    }

    fun getEntries(): List<DatasetEntry> {
        val entries = ArrayList<DatasetEntry>()
        readableDatabase.query(
            "dataset_entries",
            null,
            null,
            null,
            null,
            null,
            "updated_at DESC",
        ).use { cursor ->
            val id = cursor.getColumnIndexOrThrow("id")
            val sourceId = cursor.getColumnIndexOrThrow("source_id")
            val command = cursor.getColumnIndexOrThrow("command_text")
            val input = cursor.getColumnIndexOrThrow("input_text")
            val output = cursor.getColumnIndexOrThrow("output_text")
            val created = cursor.getColumnIndexOrThrow("created_at")
            val updated = cursor.getColumnIndexOrThrow("updated_at")
            while (cursor.moveToNext()) {
                entries += DatasetEntry(
                    id = cursor.getLong(id),
                    sourceImageId = cursor.getString(sourceId),
                    command = cursor.getString(command),
                    input = cursor.getString(input),
                    output = cursor.getString(output),
                    createdAt = cursor.getLong(created),
                    updatedAt = cursor.getLong(updated),
                )
            }
        }
        return entries
    }

    fun deleteEntry(entryId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val sourceId = db.query(
                "dataset_entries",
                arrayOf("source_id"),
                "id = ?",
                arrayOf(entryId.toString()),
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            db.delete("dataset_entries", "id = ?", arrayOf(entryId.toString()))
            if (sourceId != null) {
                db.delete("image_state", "source_id = ?", arrayOf(sourceId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun countEntries(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM dataset_entries", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun findEntryId(db: SQLiteDatabase, sourceImageId: String): Long? {
        return db.query(
            "dataset_entries",
            arrayOf("id"),
            "source_id = ?",
            arrayOf(sourceImageId),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun upsertState(
        db: SQLiteDatabase,
        sourceImageId: String,
        status: String,
        timestamp: Long,
    ) {
        val values = ContentValues().apply {
            put("source_id", sourceImageId)
            put("status", status)
            put("processed_at", timestamp)
        }
        db.insertWithOnConflict(
            "image_state",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private companion object {
        const val DATABASE_NAME = "prompt_dataset.db"
        const val DATABASE_VERSION = 1
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_IGNORED = "IGNORED"
    }
}
