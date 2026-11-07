package com.matthewvlach.field

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.field.LightFieldRecorder
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServerData
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FileBrowserSpec
import kotlin.math.log10
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) = Unit
    override suspend fun onPushNotification(data: ByteArray) = Unit
    override fun getToolManagerManifest(): ClientToolManifest = ClientToolManifest(
        title = "Field",
        roots = listOf(
            ClientLeafNode(FileBrowserSpec(label = "Recordings", path = RECORDING_DIR,
                headerText = "48 kHz mono WAV files. Each has a .json file with its name, note and markers.")),
            ClientLeafNode(FileBrowserSpec(label = "Notes", path = NOTE_DIR, headerText = "Plain text notes.")),
        ),
    )
}

private const val MIC = "android.permission.RECORD_AUDIO"

/** Markers dropped during the current take. */
private object Take {
    val markers = mutableListOf<Long>()
}

/** The recorder: two reels, the time, a level meter. Record, mark, stop. */
@InitialScreen
class RecorderScreen(host: SealedLightActivity) : FieldScreen(host) {

    override fun willShow() {
        super.willShow()
        Settings.load(lightContext)
    }

    override fun willHide() {
        LightFieldRecorder.stopMonitor()
    }

    override fun onAppPause() {
        LightFieldRecorder.stopMonitor()
    }

    @Composable
    override fun Content() {
        val state by LightFieldRecorder.state.collectAsState()
        val level by LightFieldRecorder.level.collectAsState()
        val clipped by LightFieldRecorder.clipped.collectAsState()
        val settings by Settings.state.collectAsState()
        val askMic = rememberPermissionRequestLauncher(MIC)

        LaunchedEffect(state.recording, state.error) {
            if (!state.recording && LightFieldRecorder.hasMicPermission(lightContext)) LightFieldRecorder.startMonitor(lightContext)
        }

        // When a take ends, open it to name it and add a note.
        var wasRecording by remember { androidx.compose.runtime.mutableStateOf(state.recording) }
        LaunchedEffect(state.recording) {
            val file = state.file
            if (wasRecording && !state.recording && file != null && file.length() > 44) {
                Store.saveMeta(file, file.nameWithoutExtension, "", Take.markers.toList())
                Take.markers.clear()
                navigateTo({ RecordingScreen(it, file.nameWithoutExtension) })
            }
            wasRecording = state.recording
        }

        Frame(
            title = "Field",
            onBack = null,
            right = LightBarButton.LightIcon(LightIcons.LIST, onClick = { navigateTo({ LibraryScreen(it) }) }),
            bottom = if (state.recording) listOf(
                LightBarButton.LightIcon(LightIcons.STAR, onClick = { Take.markers += state.elapsedMs }),
                LightBarButton.LightIcon(LightIcons.STOP, onClick = { LightFieldRecorder.stop(lightContext) }),
            ) else listOf(
                LightBarButton.LightIcon(LightIcons.CIRCLE, onClick = {
                    LightFieldRecorder.clearError()
                    if (!LightFieldRecorder.hasMicPermission(lightContext)) {
                        // Ask LightOS for the mic; the status line explains if nothing appears.
                        askMic?.launch()
                        LightFieldRecorder.start(lightContext, Store.newRecordingFile()) // sets the "needs the microphone" message
                    } else {
                        Take.markers.clear()
                        LightFieldRecorder.start(lightContext, Store.newRecordingFile())
                    }
                }),
            ),
        ) {
            Reels(spinning = state.recording, elapsedMs = state.elapsedMs)
            Column(Modifier.fillMaxWidth().padding(horizontal = 1.5f.gridUnitsAsDp())) {
                LightText(formatTime(if (state.recording) state.elapsedMs else 0, tenths = true), LightTextVariant.Title)
                LightText(
                    when {
                        state.error != null -> state.error!!
                        state.recording -> "Recording" + if (Take.markers.isNotEmpty()) " · ${Take.markers.size} marks" else ""
                        else -> "Ready"
                    },
                    LightTextVariant.Copy,
                    lighten = true,
                )
                Meter(level, clipped, Modifier.padding(top = 2f.gridUnitsAsDp()).fillMaxWidth().height(28.dp))
                if (!state.recording) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(top = 1f.gridUnitsAsDp()),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2f.gridUnitsAsDp()),
                    ) {
                        LightText(
                            "Mic: ${settings.source.label}",
                            LightTextVariant.Copy,
                            modifier = Modifier.lightClickable { Settings.nextSource(lightContext) },
                        )
                        LightText(
                            "Boost: +${settings.gainDb} dB",
                            LightTextVariant.Copy,
                            modifier = Modifier.lightClickable { Settings.nextGain(lightContext) },
                        )
                    }
                }
                LightText(
                    if (clipped) "Too loud. Move back from the sound." else " ",
                    LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
            }
        }
    }

}

/** One disk, spinning while recording: a groove, a label and a notch so the turn shows. */
@Composable
private fun Reels(spinning: Boolean, elapsedMs: Long) {
    val ink = LightThemeTokens.colors.content
    val bg = LightThemeTokens.colors.background
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(spinning) {
        var last = 0L
        while (spinning) {
            withFrameMillis { t ->
                // 33 rpm
                if (last != 0L) angle = (angle + (t - last) * 0.2f) % 360f
                last = t
            }
        }
    }
    Canvas(Modifier.fillMaxWidth().height(180.dp).padding(vertical = 1f.gridUnitsAsDp())) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.height / 2
        drawCircle(ink, r, c)
        drawCircle(bg, r * 0.72f, c, style = Stroke(1.5f))
        drawCircle(bg, r * 0.34f, c)
        drawCircle(ink, r * 0.06f, c)
        rotate(angle, c) {
            // notch on the label
            drawLine(ink, Offset(c.x, c.y - r * 0.32f), Offset(c.x, c.y - r * 0.14f), strokeWidth = 6f)
            // a bright mark on the rim
            drawLine(bg, Offset(c.x, c.y - r), Offset(c.x, c.y - r * 0.86f), strokeWidth = 6f)
        }
    }
}

/** Segmented level meter, -60 dB to 0 dB. The last segments are the danger zone. */
@Composable
private fun Meter(level: Float, clipped: Boolean, modifier: Modifier) {
    val ink = LightThemeTokens.colors.content
    val dim = LightThemeTokens.colors.contentSecondary
    var held by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(level) { held = maxOf(level, held * 0.85f) }
    val db = if (held <= 0f) -60f else (20 * log10(held)).coerceIn(-60f, 0f)
    val fill = (db + 60f) / 60f
    Canvas(modifier) {
        val n = 30
        val gap = 4f
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val on = (i + 0.5f) / n <= fill
            val x = i * (w + gap)
            val loud = i >= n - 3
            if (on || (loud && clipped)) {
                drawRect(ink, Offset(x, 0f), Size(w, size.height))
            } else {
                drawRect(dim, Offset(x, size.height * 0.35f), Size(w, size.height * 0.3f))
            }
        }
    }
}

/** Mic choice and boost, remembered between sessions. */
object Settings {
    data class S(val source: LightFieldRecorder.Source, val gainDb: Int)
    private val gains = listOf(0, 6, 12, 18, 24, 30)
    val state = kotlinx.coroutines.flow.MutableStateFlow(S(LightFieldRecorder.source, LightFieldRecorder.gainDb))
    private var file: java.io.File? = null

    fun load(ctx: com.thelightphone.sdk.SealedLightContext) {
        if (file != null) return
        val f = java.io.File(ctx.filesDir, "recorder-settings.txt").also { file = it }
        runCatching {
            val (src, g) = f.readText().trim().split(' ')
            apply(S(LightFieldRecorder.Source.valueOf(src), g.toInt()))
        }
    }

    fun nextSource(ctx: com.thelightphone.sdk.SealedLightContext) {
        val all = LightFieldRecorder.Source.entries
        set(ctx, state.value.copy(source = all[(state.value.source.ordinal + 1) % all.size]))
    }

    fun nextGain(ctx: com.thelightphone.sdk.SealedLightContext) {
        set(ctx, state.value.copy(gainDb = gains[(gains.indexOf(state.value.gainDb) + 1).mod(gains.size)]))
    }

    private fun set(ctx: com.thelightphone.sdk.SealedLightContext, s: S) {
        apply(s)
        runCatching { file?.writeText("${s.source.name} ${s.gainDb}") }
        LightFieldRecorder.restartMonitor(ctx)
    }

    private fun apply(s: S) {
        state.value = s
        LightFieldRecorder.source = s.source
        LightFieldRecorder.gainDb = s.gainDb
    }
}
