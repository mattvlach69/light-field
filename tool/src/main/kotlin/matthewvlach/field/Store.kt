package com.matthewvlach.field

import com.thelightphone.sdk.SealedLightContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

const val RECORDING_DIR = "recordings"
const val NOTE_DIR = "notes"

/** A .wav plus its sidecar .json (name, note, markers). */
data class Recording(
    val file: File,
    val name: String,
    val note: String,
    val markersMs: List<Long>,
    val createdAt: Long,
    val durationMs: Long,
) {
    val id: String get() = file.nameWithoutExtension
}

/** A plain .txt note. The first line is its title. */
data class Note(val file: File, val text: String, val modifiedAt: Long) {
    val id: String get() = file.nameWithoutExtension
    val title: String get() = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "Empty note"
}

sealed interface Entry {
    val sortTime: Long
    data class Rec(val r: Recording) : Entry { override val sortTime get() = r.createdAt }
    data class Txt(val n: Note) : Entry { override val sortTime get() = n.modifiedAt }
}

/**
 * Recordings live in shared/recordings and notes in shared/notes, so both can be reached
 * from the computer (Tool Manager or the get-recordings script).
 */
object Store {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var recDir: File
    private lateinit var noteDir: File

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes

    fun load(ctx: SealedLightContext) {
        if (!::recDir.isInitialized) {
            val shared = File(ctx.filesDir, "shared")
            recDir = File(shared, RECORDING_DIR).apply { mkdirs() }
            noteDir = File(shared, NOTE_DIR).apply { mkdirs() }
        }
        io.launch { rescan() }
    }

    fun rescan() {
        _recordings.value = recDir.listFiles { f -> f.extension.equals("wav", true) }.orEmpty()
            .map(::readRecording).sortedByDescending { it.createdAt }
        _notes.value = noteDir.listFiles { f -> f.extension == "txt" }.orEmpty()
            .map { Note(it, runCatching { it.readText() }.getOrDefault(""), it.lastModified()) }
            .sortedByDescending { it.modifiedAt }
    }

    /** A new file named after now, like 2026-11-06 15-22-08.wav */
    fun newRecordingFile(): File =
        File(recDir, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")) + ".wav")

    fun recording(id: String) = _recordings.value.firstOrNull { it.id == id }
    fun note(id: String) = _notes.value.firstOrNull { it.id == id }

    /** Save name, note and markers for a recording. */
    fun saveMeta(file: File, name: String, note: String, markersMs: List<Long>) {
        val json = JSONObject().put("name", name).put("note", note)
            .put("markers", JSONArray(markersMs.sorted()))
        io.launch {
            sidecar(file).writeText(json.toString(2))
            rescan()
        }
    }

    fun delete(r: Recording) = io.launch {
        r.file.delete()
        sidecar(r.file).delete()
        rescan()
    }

    fun saveNote(existing: Note?, text: String): Note {
        val file = existing?.file ?: File(noteDir, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")) + ".txt")
        file.writeText(text)
        io.launch { rescan() }
        return Note(file, text, System.currentTimeMillis())
    }

    fun deleteNote(n: Note) = io.launch {
        n.file.delete()
        rescan()
    }

    private fun sidecar(wav: File) = File(wav.parentFile, wav.nameWithoutExtension + ".json")

    private fun readRecording(wav: File): Recording {
        val meta = runCatching { JSONObject(sidecar(wav).readText()) }.getOrNull()
        val markers = meta?.optJSONArray("markers")?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty()
        return Recording(
            file = wav,
            name = meta?.optString("name").orEmpty().ifBlank { wav.nameWithoutExtension },
            note = meta?.optString("note").orEmpty(),
            markersMs = markers,
            createdAt = parseCreated(wav),
            durationMs = wavDurationMs(wav),
        )
    }

    private fun parseCreated(f: File): Long = runCatching {
        LocalDateTime.parse(f.nameWithoutExtension, DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"))
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrDefault(f.lastModified())

    /** Duration from the file size and the header's sample rate (mono 16-bit). */
    private fun wavDurationMs(f: File): Long = runCatching {
        java.io.RandomAccessFile(f, "r").use { r ->
            r.seek(24)
            val rate = Integer.reverseBytes(r.readInt())
            ((f.length() - 44) / 2) * 1000 / rate
        }
    }.getOrDefault(0)
}
