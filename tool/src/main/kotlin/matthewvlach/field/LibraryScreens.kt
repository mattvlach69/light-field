package com.matthewvlach.field

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.LightPlayerSpec
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** Recordings and notes together, newest first. */
class LibraryScreen(host: SealedLightActivity) : FieldScreen(host) {
    @Composable
    override fun Content() {
        val recordings by Store.recordings.collectAsState()
        val notes by Store.notes.collectAsState()
        val entries = remember(recordings, notes) {
            (recordings.map { Entry.Rec(it) } + notes.map { Entry.Txt(it) }).sortedByDescending { it.sortTime }
        }
        Frame(
            "Library",
            onBack = { goBack() },
            right = LightBarButton.LightIcon(LightIcons.PENCIL, onClick = { newNote() }),
        ) {
            if (entries.isEmpty()) {
                Message("Nothing yet.\n\nRecordings show up here after you stop. Tap the pencil to write a note.")
                return@Frame
            }
            RowList {
                items(entries) { e ->
                    when (e) {
                        is Entry.Rec -> ListRow(
                            e.r.name,
                            listOfNotNull(
                                formatWhen(e.r.createdAt),
                                e.r.note.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            trailing = formatTime(e.r.durationMs),
                        ) { navigateTo({ RecordingScreen(it, e.r.id) }) }
                        is Entry.Txt -> ListRow(e.n.title, "Note · ${formatWhen(e.n.modifiedAt)}") {
                            editNote(e.n)
                        }
                    }
                }
            }
        }
    }

    private fun newNote() = editNote(null)

    /** Like Light's Notes: opens straight into the editor. SAVE keeps it, X leaves it as it was,
     *  and saving an empty note deletes it. */
    private fun editNote(n: Note?) {
        navigateTo({ TextEntryScreen(it, n?.title ?: "Untitled", n?.text.orEmpty(), singleLine = false) }) { text ->
            when {
                text == null -> Unit
                text.isBlank() -> if (n != null) Store.deleteNote(n)
                else -> Store.saveNote(n, text)
            }
        }
    }
}

private val SPEEDS = listOf(1f, 0.5f, 2f)

/** Play a take, jump between marks, name it, write a note about it. */
class RecordingScreen(host: SealedLightActivity, private val id: String) : FieldScreen(host) {
    private var player: LightAudioPlayer? = null
    private val playerState = mutableStateOf<LightAudioPlayer?>(null)

    override fun willShow() {
        super.willShow()
        Store.rescan()
    }

    override fun willHide() = releasePlayer()
    override fun onAppPause() = releasePlayer()
    override fun onScreenDestroy() = releasePlayer()

    private fun releasePlayer() {
        player?.release()
        player = null
        playerState.value = null
    }

    private fun playerFor(r: Recording): LightAudioPlayer {
        player?.let { return it }
        val p = DefaultLightAudio(host).newPlayer(LightPlayerSpec(playback = LightAudioPlayback.Attached))
        p.setMediaQueue(
            listOf(LightAudioItem(LightAudioSource.FileSource(r.file), LightMediaMetadata(r.name, durationMs = r.durationMs), id = r.id)),
            0,
        )
        player = p
        playerState.value = p
        return p
    }

    @Composable
    override fun Content() {
        val recordings by Store.recordings.collectAsState()
        val r = recordings.firstOrNull { it.id == id }
        var speedIndex by remember { mutableStateOf(0) }
        var confirm by remember { mutableStateOf(false) }

        val p = playerState.value
        val position by (p?.positionMs ?: kotlinx.coroutines.flow.MutableStateFlow(0L)).collectAsState()
        val playing by (p?.isPlaying ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
        val duration = r?.durationMs ?: 0L

        LaunchedEffect(r?.id) { if (r != null) playerFor(r) }

        Frame(
            r?.name ?: "Recording",
            onBack = { goBack() },
            right = LightBarButton.Text(formatSpeed(SPEEDS[speedIndex]), onClick = {
                speedIndex = (speedIndex + 1) % SPEEDS.size
                player?.speed = SPEEDS[speedIndex]
            }),
            bottom = r?.let { rec ->
                val marks = listOf(0L) + rec.markersMs
                listOf(
                    LightBarButton.LightIcon(LightIcons.REWIND, onClick = {
                        // previous mark (or the start), like skipping back on a tape deck
                        val target = marks.lastOrNull { it < position - 1_500 } ?: 0L
                        player?.seekTo(target)
                    }),
                    LightBarButton.LightIcon(if (playing) LightIcons.PAUSE else LightIcons.PLAY, onClick = {
                        val pl = playerFor(rec)
                        if (playing) pl.pause() else {
                            if (duration > 0 && position >= duration - 200) pl.seekTo(0)
                            pl.speed = SPEEDS[speedIndex]
                            pl.play()
                        }
                    }),
                    LightBarButton.LightIcon(LightIcons.FAST_FORWARD, onClick = {
                        rec.markersMs.firstOrNull { it > position + 200 }?.let { player?.seekTo(it) }
                    }),
                )
            },
        ) {
            if (r == null) {
                Message("This recording was deleted.")
                return@Frame
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp())) {
                LightText(formatTime(position, tenths = true), LightTextVariant.Title)
                LightTouchableProgressBar(
                    colors = LightThemeTokens.colors,
                    progress = if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { f -> playerFor(r).seekTo((f * duration).toLong()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 1f.gridUnitsAsDp()),
                )
                LightText(
                    "${formatWhen(r.createdAt)} · ${formatTime(duration)}",
                    LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
            }
            RowList {
                item {
                    ListRow("Name", r.name) {
                        navigateTo({ TextEntryScreen(it, "Name", r.name) }) { t ->
                            if (!t.isNullOrBlank()) Store.saveMeta(r.file, t, r.note, r.markersMs)
                        }
                    }
                }
                item {
                    ListRow("Note", r.note.ifBlank { "Add a note" }, dim = r.note.isBlank()) {
                        navigateTo({ TextEntryScreen(it, "Note", r.note, singleLine = false) }) { t ->
                            if (t != null) Store.saveMeta(r.file, r.name, t, r.markersMs)
                        }
                    }
                }
                item {
                    ListRow("Mark here", "Add a mark at ${formatTime(position)}", dim = true) {
                        Store.saveMeta(r.file, r.name, r.note, r.markersMs + position)
                    }
                }
                itemsIndexed(r.markersMs) { i, ms ->
                    ListRow("Mark ${i + 1}", trailing = formatTime(ms, tenths = true)) { playerFor(r).seekTo(ms) }
                }
                item {
                    ListRow(if (confirm) "Tap again to delete" else "Delete recording", dim = !confirm) {
                        if (confirm) {
                            releasePlayer()
                            Store.delete(r)
                            goBack()
                        } else confirm = true
                    }
                }
            }
        }
    }
}

private fun formatSpeed(s: Float) = when (s) {
    0.5f -> "½x"
    else -> "${s.toInt()}x"
}
