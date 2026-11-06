package com.zeit2206.audiobook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ScreenHeader(title: String, onAddFolder: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.surface2).clickable(onClick = onAddFolder)
            .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Add, null, tint = Palette.text, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Folder", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.text)
        }
    }
}

/** Shown while saved folders are being read, so a slow scan never looks like "no folder picked". */
@Composable
fun LoadingState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.muted, fontSize = 15.sp)
    }
}

@Composable
fun EmptyState(text: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Palette.muted, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
        Spacer(Modifier.height(16.dp))
        Text(button, color = Palette.accentInk, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Palette.accent).clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

@Composable
fun LibraryScreen(
    books: List<Book>, loading: Boolean, store: Store, ui: PlayerUi, current: Chapter?,
    onAddFolder: () -> Unit, onOpen: (Book, Chapter) -> Unit, onOpenPlayer: () -> Unit, onToggle: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Library", onAddFolder)
        if (books.isEmpty() && loading) { LoadingState("Loading library…"); return }
        if (books.isEmpty()) {
            EmptyState("Pick a folder with chapter MP3s and their .vtt files\n(the *_chunks folder made on the PC).",
                "Choose folder", onAddFolder)
            return
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            books.forEach { book ->
                item(key = book.tree.toString()) {
                    Column(Modifier.padding(start = 8.dp, top = 12.dp, bottom = 10.dp)) {
                        Text(book.name.uppercase(), fontSize = 11.sp, letterSpacing = 0.9.sp,
                            fontWeight = FontWeight.SemiBold, color = Palette.muted)
                        val n = book.chapters.size
                        Text("$n chapter${if (n == 1) "" else "s"} · ${formatDuration(book.durationMs)}",
                            fontSize = 13.sp, color = Palette.dim)
                    }
                }
                items(book.chapters, key = { it.audio.toString() }) { ch ->
                    val active = ch.audio == current?.audio
                    val dur = if (active && ui.durationMs > 0) ui.durationMs else store.duration(ch.audio).takeIf { it > 0 } ?: ch.durationMs
                    val pos = if (active) ui.positionMs else store.position(ch.audio)
                    ChapterRow(ch, active, if (dur > 0) pos.toFloat() / dur else 0f, dur) { onOpen(book, ch) }
                }
            }
        }
        if (current != null) MiniPlayer(current, ui, onOpenPlayer, onToggle)
    }
}

@Composable
private fun ChapterRow(ch: Chapter, active: Boolean, progress: Float, durationMs: Long, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
        .background(if (active) Palette.surface else Color.Transparent).clickable(onClick = onClick)
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (active) Palette.accent else Palette.surface2),
            contentAlignment = Alignment.Center) {
            Text("%02d".format(ch.number), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = if (active) Palette.accentInk else Palette.muted)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (durationMs > 0) formatDuration(durationMs) else "—", fontSize = 12.sp, color = Palette.muted)
            if (progress > 0.005f) {
                Spacer(Modifier.height(6.dp))
                ProgressLine(progress.coerceIn(0f, 1f), Modifier.fillMaxWidth(0.9f))
            }
        }
        if (progress <= 0.005f) Box(Modifier.size(12.dp).border(1.5.dp, Palette.dim, CircleShape))
        else if (progress >= 0.98f) Text("✓", color = Palette.accent, fontSize = 14.sp)
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, height: Int = 3, track: Color = Palette.surface2, fill: Color = Palette.accent) {
    Box(modifier.height(height.dp).clip(RoundedCornerShape(2.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(progress).height(height.dp).background(fill))
    }
}

@Composable
private fun MiniPlayer(ch: Chapter, ui: PlayerUi, onOpen: () -> Unit, onToggle: () -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp))
        .background(Palette.surface2).clickable(onClick = onOpen)) {
        ProgressLine(if (ui.durationMs > 0) ui.positionMs.toFloat() / ui.durationMs else 0f, Modifier.fillMaxWidth(),
            height = 2, track = Color.Transparent)
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ch.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatTime(ui.positionMs)} / ${formatTime(ui.durationMs)} · ${speedLabel(ui.speed)}",
                    fontSize = 12.sp, color = Palette.muted)
            }
            Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.accent).clickable(onClick = onToggle),
                contentAlignment = Alignment.Center) {
                Icon(if (ui.isPlaying) Icons.Pause else Icons.Play, "Play/pause", tint = Palette.accentInk, modifier = Modifier.size(18.dp))
            }
        }
    }
}

fun speedLabel(s: Float) = (if (s % 1f == 0f) "%.0f" else if ((s * 10) % 1f == 0f) "%.1f" else "%.2f").format(s) + "×"

@Composable
fun NavBar(isVideos: Boolean, onSelect: (Boolean) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp)) {
            listOf(false to "Audiobooks", true to "Videos").forEach { (videos, label) ->
                val on = videos == isVideos
                Column(Modifier.weight(1f).clickable { onSelect(videos) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(64.dp).height(30.dp).clip(RoundedCornerShape(15.dp))
                        .background(if (on) Palette.hl else Color.Transparent), contentAlignment = Alignment.Center) {
                        Icon(if (videos) Icons.Video else Icons.Book, label, tint = if (on) Palette.accent else Palette.muted,
                            modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(label, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) Palette.text else Palette.muted)
                }
            }
        }
    }
}
