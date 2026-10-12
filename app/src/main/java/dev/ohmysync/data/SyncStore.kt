package dev.ohmysync.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.syncData by preferencesDataStore("sync")

class SyncStore(context: Context, name: String = "sync") {
    private val dataStore = if (name == "sync") context.syncData else PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile(name)
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("state")
    val state = dataStore.data.map { prefs ->
        prefs[key]?.let { json.decodeFromString<SyncState>(it) } ?: SyncState()
    }
    suspend fun current() = state.first()
    private suspend fun update(block: (SyncState) -> SyncState) {
        dataStore.edit { prefs ->
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
    suspend fun operation(value: LibraryOperation) = update {
        it.copy(operations = it.operations.filterNot { old -> old.id == value.id } + value)
    }
    suspend fun finishOperation(value: LibraryOperation, stamp: LibraryStamp?, artifacts: List<LibraryArtifact> = emptyList(), message: String) = update { old ->
        old.copy(
            operations = old.operations.filterNot { it.id == value.id },
            library = if (stamp == null) old.library else old.library + (value.key to stamp),
            artifacts = old.artifacts.filterNot { existing -> artifacts.any { it.treeUri == existing.treeUri && it.path == existing.path } } + artifacts,
            libraryEvents = listOf(LibraryEvent(value.taskId, value.path, message)) + old.libraryEvents,
            deleteCandidates = old.deleteCandidates.filterNot { it.key == value.key },
        )
    }
    suspend fun deleteCandidates(taskPrefix: String, values: List<DeleteCandidate>) = update {
        it.copy(deleteCandidates = it.deleteCandidates.filterNot { old -> old.key.startsWith(taskPrefix) } + values)
    }
    suspend fun version(value: LibraryVersion) = update { old ->
        if (old.versions.any { it.taskId == value.taskId && it.path == value.path && it.hash == value.hash }) old
        else old.copy(versions = listOf(value) + old.versions)
    }
}
