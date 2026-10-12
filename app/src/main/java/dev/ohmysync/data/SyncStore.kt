package dev.ohmysync.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.syncData by preferencesDataStore("sync")

class SyncStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("state")
    val state = context.syncData.data.map { prefs ->
        prefs[key]?.let { json.decodeFromString<SyncState>(it) } ?: SyncState()
    }
    suspend fun current() = state.first()
    private suspend fun update(block: (SyncState) -> SyncState) {
        context.syncData.edit { prefs ->
            val old = prefs[key]?.let { json.decodeFromString<SyncState>(it) } ?: SyncState()
            prefs[key] = json.encodeToString(block(old))
        }
    }
    suspend fun saveTask(task: SyncTask) = update { old ->
        old.copy(tasks = old.tasks.filterNot { it.id == task.id } + task)
    }
    suspend fun record(record: SyncRecord) = update { it.copy(records = (listOf(record) + it.records).take(100)) }
    suspend fun stamp(key: String, value: FileStamp) = update { it.copy(stamps = it.stamps + (key to value)) }
    suspend fun libraryStamp(key: String, value: LibraryStamp) = update { it.copy(library = it.library + (key to value)) }
    suspend fun version(value: LibraryVersion) = update { old ->
        if (old.versions.any { it.taskId == value.taskId && it.path == value.path && it.hash == value.hash }) old
        else old.copy(versions = listOf(value) + old.versions)
    }
}
