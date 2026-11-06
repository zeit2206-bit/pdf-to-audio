package com.zeit2206.audiobook

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

data class Cue(val startMs: Long, val endMs: Long, val text: String)

data class Chapter(val number: Int, val title: String, val audio: Uri, val vtt: Uri?, val durationMs: Long)

data class Book(val name: String, val tree: Uri, val chapters: List<Chapter>) {
    val durationMs get() = chapters.sumOf { it.durationMs }
}

/** [endMs] > 0: the video ends in silence (e.g. a TikTok outro) and playback stops there. */
data class Video(val name: String, val uri: Uri, val endMs: Long = 0)

private val AUDIO_EXT = setOf("mp3", "m4a", "m4b", "ogg", "wav")
private val VIDEO_EXT = setOf("mp4", "webm", "mkv", "mov", "3gp")

/** A file in a picked folder. Same URI form as DocumentFile's, so saved positions/trims still match. */
private class Doc(val name: String, val uri: Uri) {
    fun ext() = name.substringAfterLast('.', "").lowercase()
    fun base() = name.substringBeforeLast('.')
}

/**
 * Lists a picked folder with one query. DocumentFile.listFiles() + .name costs one query per file,
 * which takes seconds with hundreds of videos.
 */
private fun listDocs(context: Context, tree: Uri): List<Doc> {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    return try {
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(Doc(c.getString(1).orEmpty(), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))) }
        } ?: emptyList()
    } catch (e: Exception) {
        emptyList() // folder gone or permission revoked
    }
}

/** Folders the user picked, persisted with their read permission. */
class Store(private val context: Context) {
    private val prefs = context.getSharedPreferences("audiobook", Context.MODE_PRIVATE)

    fun folders(kind: String): List<Uri> =
        prefs.getStringSet("folders_$kind", emptySet())!!.sorted().map(Uri::parse)

    fun addFolder(kind: String, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putStringSet("folders_$kind", prefs.getStringSet("folders_$kind", emptySet())!! + uri.toString()).apply()
    }

    fun position(audio: Uri): Long = prefs.getLong("pos_$audio", 0L)
    fun duration(audio: Uri): Long = prefs.getLong("dur_$audio", 0L)
    fun savePosition(audio: Uri, positionMs: Long, durationMs: Long) {
        prefs.edit().putLong("pos_$audio", positionMs).apply {
            if (durationMs > 0) putLong("dur_$audio", durationMs)
        }.apply()
    }

    /** Detected end of a video's content (0 = play whole), or null if not analysed yet. */
    fun trimEnd(video: Uri): Long? = prefs.getLong("trim2_$video", -1L).takeIf { it >= 0 }
    fun setTrimEnd(video: Uri, endMs: Long) = prefs.edit().putLong("trim2_$video", endMs).apply()
    var trimSilentEndings: Boolean
        get() = prefs.getBoolean("trim_on", true)
        set(v) = prefs.edit().putBoolean("trim_on", v).apply()

    var speed: Float
        get() = prefs.getFloat("speed", 1f)
        set(v) = prefs.edit().putFloat("speed", v).apply()
}

/** "01_chapter_1_introduction_to_psychology" -> (1, "Introduction to Psychology"). */
fun chapterTitle(fileBase: String, index: Int): Pair<Int, String> {
    val num = Regex("^(\\d+)_").find(fileBase)?.groupValues?.get(1)?.toInt() ?: (index + 1)
    val words = fileBase.replace(Regex("^\\d+_"), "").replace(Regex("^chapter_\\d+_"), "").split('_')
    val small = setOf("a", "an", "and", "of", "the", "to", "in", "on", "for", "with")
    val title = words.mapIndexed { i, w -> if (i > 0 && w in small) w else w.replaceFirstChar(Char::titlecase) }
        .joinToString(" ")
    return num to title
}

fun scanBook(context: Context, tree: Uri): Book? {
    val dir = DocumentFile.fromTreeUri(context, tree) ?: return null
    val files = listDocs(context, tree)
    val vtts = files.filter { it.ext() == "vtt" }.associateBy { it.base() }
    val chapters = files.filter { it.ext() in AUDIO_EXT }.sortedBy { it.name }.mapIndexed { i, f ->
        val (num, title) = chapterTitle(f.base(), i)
        val vtt = vtts[f.base()]?.uri
        // Last cue end ≈ audio length; avoids opening every MP3 just to list the library.
        Chapter(num, title, f.uri, vtt, readCues(context, vtt).lastOrNull()?.endMs ?: 0L)
    }
    val name = dir.name.orEmpty().removeSuffix("_chunks").replace('_', ' ')
    return Book(name, tree, chapters)
}

fun scanVideos(context: Context, tree: Uri): List<Video> {
    return listDocs(context, tree).filter { it.ext() in VIDEO_EXT }.sortedBy { it.name }.map { Video(it.name, it.uri) }
}

fun readCues(context: Context, vtt: Uri?): List<Cue> {
    if (vtt == null) return emptyList()
    val text = context.contentResolver.openInputStream(vtt)?.use { it.readBytes().decodeToString() } ?: return emptyList()
    return parseVtt(text)
}

fun parseVtt(text: String): List<Cue> {
    val cues = mutableListOf<Cue>()
    val lines = text.replace("\r", "").lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if ("-->" in line) {
            val (a, b) = line.split("-->").map { parseTimestamp(it.trim().substringBefore(' ')) }
            val body = StringBuilder()
            i++
            while (i < lines.size && lines[i].isNotBlank()) {
                if (body.isNotEmpty()) body.append(' ')
                body.append(lines[i].trim()); i++
            }
            cues += Cue(a, b, body.toString())
        }
        i++
    }
    return cues
}

private fun parseTimestamp(s: String): Long {
    val parts = s.split(':')
    val sec = parts.last().toDouble()
    val min = parts.getOrNull(parts.size - 2)?.toLong() ?: 0
    val hour = if (parts.size == 3) parts[0].toLong() else 0
    return ((hour * 3600 + min * 60) * 1000 + sec * 1000).toLong()
}

/**
 * Groups cues into paragraphs. tts.py pauses 0.25 s between sentences and 0.6 s between
 * paragraphs, so a gap above ~0.45 s marks a paragraph break.
 */
fun paragraphs(cues: List<Cue>): List<List<Int>> {
    val out = mutableListOf<MutableList<Int>>()
    cues.forEachIndexed { i, cue ->
        if (i == 0 || cue.startMs - cues[i - 1].endMs > 450) out += mutableListOf<Int>()
        out.last() += i
    }
    return out
}

/** Index of the cue playing at [positionMs] (last cue that has started), or -1. */
fun cueAt(cues: List<Cue>, positionMs: Long): Int {
    var lo = 0; var hi = cues.size - 1; var ans = -1
    while (lo <= hi) {
        val m = (lo + hi) ushr 1
        if (cues[m].startMs <= positionMs) { ans = m; lo = m + 1 } else hi = m - 1
    }
    return ans
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatDuration(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)} min" else "$m min"
}
