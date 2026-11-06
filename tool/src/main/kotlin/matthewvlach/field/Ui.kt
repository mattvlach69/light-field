package com.matthewvlach.field

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

const val ROW_UNITS = 3.5f

@Composable
fun Frame(
    title: String?,
    onBack: (() -> Unit)?,
    right: LightBarButton? = null,
    bottom: List<LightBarButton>? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
            LightTopBar(
                leftButton = onBack?.let { LightBarButton.LightIcon(LightIcons.BACK, onClick = it) },
                center = title?.let { LightTopBarCenter.Text(it) },
                rightButton = right,
            )
            Column(Modifier.weight(1f).fillMaxWidth()) { body() }
            if (!bottom.isNullOrEmpty()) LightBottomBar(items = bottom)
        }
    }
}

@Composable
fun ColumnScope.RowList(content: LazyListScope.() -> Unit) {
    LightLazyScrollView(
        modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1f.gridUnitsAsDp()),
        uniformItemHeightGridUnits = ROW_UNITS,
        content = content,
    )
}

@Composable
fun ListRow(primary: String, secondary: String? = null, trailing: String? = null, dim: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(ROW_UNITS.gridUnitsAsDp()).padding(end = 1f.gridUnitsAsDp()).lightClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            LightText(primary, LightTextVariant.Copy, lighten = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!secondary.isNullOrEmpty()) {
                LightText(secondary, LightTextVariant.Detail, lighten = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) LightText(trailing, LightTextVariant.Detail, lighten = true)
    }
}

@Composable
fun ColumnScope.Message(text: String) {
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1.5f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
    ) { LightText(text, LightTextVariant.Paragraph, lighten = true) }
}

@Composable
fun rememberNow(intervalMs: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(intervalMs)
        }
    }
    return now
}

/** 1:02:03 or 2:03, with tenths if asked. */
fun formatTime(ms: Long, tenths: Boolean = false): String {
    val t = ms.coerceAtLeast(0) / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    val base = if (h > 0) "$h:${"%02d".format(m)}:${"%02d".format(s)}" else "$m:${"%02d".format(s)}"
    return if (tenths) "$base.${(ms.coerceAtLeast(0) % 1000) / 100}" else base
}

private val WHEN = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)
fun formatWhen(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(WHEN)

/** Full-screen keyboard. Returns the text, or null on back. */
class TextEntryScreen(
    host: SealedLightActivity,
    private val title: String,
    private val initial: String = "",
    private val singleLine: Boolean = true,
) : SimpleLightScreen<String?>(host) {
    @Composable
    override fun Content() {
        val keyboard = rememberKeyboardOptions()
        val text = rememberTextFieldState(initial)
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            LightTextInputEditor(
                title = title,
                state = text,
                keyboardOptionsFlow = keyboard,
                onSubmit = { goBack(it.toString().trimEnd()) },
                onBack = { goBack(null) },
                modifier = Modifier.background(LightThemeTokens.colors.background),
                submitLabel = "SAVE",
                singleLine = singleLine,
                initialCaps = true,
            )
        }
    }
}

abstract class FieldScreen(protected val host: SealedLightActivity) : SimpleLightScreen<Unit>(host) {
    override fun willShow() { Store.load(lightContext) }
}
