package com.zeit2206.audiobook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SPEEDS = listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)

@Composable
fun PlayerScreen(book: Book, chapter: Chapter, ui: PlayerUi, controller: MediaController, store: Store, onClose: () -> Unit) {
    val context = LocalContext.current
    val cues by produceState(emptyList<Cue>(), chapter.vtt) {
        value = withContext(Dispatchers.IO) { readCues(context, chapter.vtt) }
    }
    val paras = remember(cues) { paragraphs(cues) }
    val paraOf = remember(paras) { IntArray(cues.size).also { a -> paras.forEachIndexed { p, ids -> ids.forEach { a[it] = p } } } }
    val currentCue = cueAt(cues, ui.positionMs)

    // Character offset where each cue starts inside its paragraph's text (sentences joined by one space).
    val starts = remember(cues, paras) {
        IntArray(cues.size).also { a -> paras.forEach { ids -> var o = 0; ids.forEach { a[it] = o; o += cues[it].text.length + 1 } } }
    }
    val layouts = remember(paras) { HashMap<Int, TextLayoutResult>() }
    val topGapPx = with(LocalDensity.current) { 140.dp.toPx() }

    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) follow = false }
    // Keep the current *sentence* (not just its paragraph) near the top: paragraphs can be taller than the screen.
    LaunchedEffect(currentCue, follow, paras) {
        if (!follow || currentCue < 0 || paras.isEmpty()) return@LaunchedEffect
        val p = paraOf[currentCue]
        if (layouts[p] == null) { listState.scrollToItem(p + 1); withFrameNanos { } }
        val y = layouts[p]?.let { it.getLineTop(it.getLineForOffset(starts[currentCue])) } ?: 0f
        listState.animateScrollToItem(p + 1, (y - topGapPx).toInt().coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize().background(Palette.bg)) {
        // Header
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Down, "Close", tint = Palette.muted, modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose).padding(8.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CHAPTER ${chapter.number}", fontSize = 11.sp, letterSpacing = 0.9.sp, fontWeight = FontWeight.SemiBold, color = Palette.muted)
                Text(chapter.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(40.dp))
        }

        // Read-along text
        Box(Modifier.weight(1f)) {
            if (chapter.vtt == null) {
                Text("No .vtt file next to this MP3, so there's no text to show.", color = Palette.muted,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center)
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 320.dp)) {
                item { Spacer(Modifier.height(120.dp)) } // lets the current paragraph sit below the top edge
                itemsIndexed(paras) { p, ids ->
                    val text = buildAnnotatedString {
                        ids.forEachIndexed { k, i ->
                            if (k > 0) append(" ")
                            val color = when {
                                i == currentCue -> Palette.hlText
                                i < currentCue -> Palette.dim
                                else -> Palette.muted
                            }
                            pushStringAnnotation("cue", i.toString())
                            withStyle(SpanStyle(color = color, background = if (i == currentCue) Palette.hl else Color.Transparent)) {
                                append(cues[i].text)
                            }
                            pop()
                        }
                    }
                    val heading = ids.size == 1 && cues[ids[0]].text.length < 60 && !cues[ids[0]].text.trimEnd().endsWith('.')
                    ClickableText(
                        text = text,
                        style = TextStyle(fontFamily = Literata, fontSize = if (heading) 15.sp else 19.sp, lineHeight = if (heading) 22.sp else 32.sp,
                            fontWeight = if (heading) FontWeight.SemiBold else FontWeight.Normal),
                        modifier = Modifier.padding(bottom = 22.dp),
                        onTextLayout = { layouts[p] = it },
                    ) { offset ->
                        text.getStringAnnotations("cue", offset, offset).firstOrNull()?.let {
                            controller.seekTo(cues[it.item.toInt()].startMs + 10)
                            controller.play()
                            follow = true
                        }
                    }
                }
            }
            // Fade text out above the controls
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.bg))))
            if (!follow && currentCue >= 0) {
                Text("Back to current", color = Palette.accentInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp))
                        .background(Palette.accent).clickable { follow = true }.padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }

        // Seek bar
        var dragPos by remember { mutableStateOf<Float?>(null) }
        val dur = ui.durationMs.coerceAtLeast(1)
        Column(Modifier.padding(horizontal = 16.dp)) {
            Slider(
                value = dragPos ?: (ui.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragPos = it },
                onValueChangeFinished = { dragPos?.let { controller.seekTo((it * dur).toLong()) }; dragPos = null },
                colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.surface2),
            )
            Row(Modifier.padding(horizontal = 8.dp)) {
                val pos = dragPos?.let { (it * dur).toLong() } ?: ui.positionMs
                Text(formatTime(pos), fontSize = 12.sp, color = Palette.muted, modifier = Modifier.weight(1f))
                Text("-" + formatTime(ui.durationMs - pos), fontSize = 12.sp, color = Palette.muted)
            }
        }

        // Transport
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(speedLabel(ui.speed), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.text,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    val next = SPEEDS[(SPEEDS.indexOfFirst { it >= ui.speed - 0.01f }.coerceAtLeast(0) + 1) % SPEEDS.size]
                    controller.setPlaybackSpeed(next); store.speed = next
                }.padding(10.dp))
            SkipButton(Icons.Back15, "15") { controller.seekBack() }
            Box(Modifier.size(64.dp).clip(CircleShape).background(Palette.accent).clickable {
                if (controller.isPlaying) controller.pause() else controller.play()
            }, contentAlignment = Alignment.Center) {
                Icon(if (ui.isPlaying) Icons.Pause else Icons.Play, "Play/pause", tint = Palette.accentInk, modifier = Modifier.size(26.dp))
            }
            SkipButton(Icons.Fwd30, "30") { controller.seekForward() }
            Icon(Icons.Next, "Next chapter", tint = if (controller.hasNextMediaItem()) Palette.text else Palette.dim,
                modifier = Modifier.size(44.dp).clip(CircleShape).clickable { if (controller.hasNextMediaItem()) controller.seekToNextMediaItem() }.padding(10.dp))
        }
        Text("${book.name}  ·  Tap any sentence to jump", fontSize = 12.sp, color = Palette.dim,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp))
    }
}

@Composable
private fun SkipButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, "Skip $label", tint = Palette.text, modifier = Modifier.size(30.dp))
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Palette.text)
    }
}
