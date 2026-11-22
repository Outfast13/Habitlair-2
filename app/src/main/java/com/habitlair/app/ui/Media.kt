@file:OptIn(ExperimentalLayoutApi::class)

package com.habitlair.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.habitlair.app.data.Attachment
import com.habitlair.app.data.AttachmentChanges
import com.habitlair.app.data.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Editor-side working copy of an owner's attachments; applied on Save, cleaned up on cancel. */
class AttachmentDraft {
    val items: SnapshotStateList<Attachment> = mutableStateListOf()
    private val added = mutableListOf<Attachment>()
    private val removed = mutableListOf<Attachment>()

    fun load(list: List<Attachment>) { items.clear(); items.addAll(list) }
    fun add(a: Attachment) { items.add(a); added.add(a) }
    fun remove(a: Attachment) {
        items.remove(a)
        if (added.remove(a)) File(a.path).delete() else removed.add(a)
    }
    fun changes() = AttachmentChanges(added.toList(), removed.toList())
    fun discard() { added.forEach { File(it.path).delete() }; added.clear() }
}

@Composable
fun AttachmentGrid(items: List<Attachment>, onRemove: ((Attachment) -> Unit)? = null) {
    if (items.isEmpty()) return
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { a ->
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                when (a.kind) {
                    MediaKind.PHOTO -> AsyncImage(File(a.path), a.name, Modifier.fillMaxSize().clickable { viewing = a }, contentScale = ContentScale.Crop)
                    MediaKind.VIDEO -> Column(Modifier.fillMaxSize().clickable { viewing = a }.padding(6.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.PlayCircle, "Play video", Modifier.size(36.dp), MaterialTheme.colorScheme.primary)
                        Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    }
                    else -> AudioTile(a)
                }
                if (onRemove != null) Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = .6f)).clickable { onRemove(a) },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Close, "Remove attachment", tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
    }
    viewing?.let { a ->
        Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (a.kind == MediaKind.PHOTO) AsyncImage(File(a.path), a.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).also { v ->
                            val mc = MediaController(ctx); mc.setAnchorView(v)
                            v.setMediaController(mc); v.setVideoPath(a.path)
                            v.setOnPreparedListener { it.start() }
                            v.setOnErrorListener { _, _, _ -> true }
                        }
                    },
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(16 / 9f)
                )
                IconButton({ viewing = null }, Modifier.align(Alignment.TopEnd).padding(12.dp)) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun AudioTile(a: Attachment) {
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val player = remember { MediaPlayer() }
    var prepared by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }
    Column(
        Modifier.fillMaxSize().clickable {
            try {
                if (playing) { player.pause(); playing = false }
                else {
                    if (!prepared) {
                        player.setDataSource(a.path); player.prepare()
                        player.setOnCompletionListener { playing = false; player.seekTo(0) }
                        prepared = true
                    }
                    player.start(); playing = true
                }
            } catch (e: Exception) { failed = true; playing = false }
        }.padding(6.dp),
        Arrangement.Center, Alignment.CenterHorizontally
    ) {
        Icon(if (playing) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle, if (playing) "Pause audio" else "Play audio", Modifier.size(36.dp), MaterialTheme.colorScheme.primary)
        Text(if (failed) "Can't play" else a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun AttachmentSection(draft: AttachmentDraft) {
    SectionLabel("Attachments")
    AttachmentGrid(draft.items, onRemove = { draft.remove(it) })
    AttachmentAdder(draft)
}

@Composable
fun AttachmentAdder(draft: AttachmentDraft) {
    val ctx = LocalContext.current
    val media = LocalContainer.current.media
    val scope = rememberCoroutineScope()
    var camFile by remember { mutableStateOf<File?>(null) }
    var recording by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun importUri(uri: Uri?, kind: String) {
        if (uri == null) return
        scope.launch {
            try { draft.add(withContext(Dispatchers.IO) { media.copyIn(uri, kind) }) }
            catch (e: Exception) { msg = "Couldn't attach that file: ${e.message}" }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { importUri(it, MediaKind.PHOTO) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { importUri(it, MediaKind.VIDEO) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importUri(it, MediaKind.AUDIO) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = camFile
        if (ok && f != null && f.length() > 0) draft.add(media.attachmentFor(f, MediaKind.PHOTO)) else f?.delete()
        camFile = null
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val f = camFile
        if (ok && f != null && f.length() > 0) draft.add(media.attachmentFor(f, MediaKind.VIDEO)) else f?.delete()
        camFile = null
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recording = true else msg = "Microphone permission denied."
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            label = { Text("Photo") }, leadingIcon = { Icon(Icons.Filled.Image, null, Modifier.size(18.dp)) })
        AssistChip(onClick = {
            val f = media.newFile("jpg"); camFile = f
            try { takePhoto.launch(media.uriFor(f)) } catch (e: Exception) { camFile = null; f.delete(); msg = "No camera app available." }
        }, label = { Text("Camera") }, leadingIcon = { Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp)) })
        AssistChip(onClick = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            label = { Text("Video") }, leadingIcon = { Icon(Icons.Filled.VideoLibrary, null, Modifier.size(18.dp)) })
        AssistChip(onClick = {
            val f = media.newFile("mp4"); camFile = f
            try { takeVideo.launch(media.uriFor(f)) } catch (e: Exception) { camFile = null; f.delete(); msg = "No camera app available." }
        }, label = { Text("Record video") }, leadingIcon = { Icon(Icons.Filled.Videocam, null, Modifier.size(18.dp)) })
        AssistChip(onClick = { audioPicker.launch(arrayOf("audio/*")) },
            label = { Text("Audio file") }, leadingIcon = { Icon(Icons.Filled.AudioFile, null, Modifier.size(18.dp)) })
        AssistChip(onClick = {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recording = true
            else micPerm.launch(Manifest.permission.RECORD_AUDIO)
        }, label = { Text("Record audio") }, leadingIcon = { Icon(Icons.Filled.Mic, null, Modifier.size(18.dp)) })
    }
    msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    if (recording) AudioRecorderDialog(
        onDone = { f -> draft.add(media.attachmentFor(f, MediaKind.AUDIO, "Recording")); recording = false },
        onError = { msg = it; recording = false },
        onCancel = { recording = false }
    )
}

@Composable
private fun AudioRecorderDialog(onDone: (File) -> Unit, onError: (String) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val media = LocalContainer.current.media
    val file = remember { media.newFile("m4a") }
    var seconds by remember { mutableIntStateOf(0) }
    var rec by remember { mutableStateOf<MediaRecorder?>(null) }

    LaunchedEffect(Unit) {
        try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setOutputFile(file.absolutePath)
            r.prepare(); r.start()
            rec = r
            while (true) { delay(1000); seconds++ }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            file.delete(); onError("Couldn't start recording: ${e.message}")
        }
    }
    DisposableEffect(Unit) { onDispose { rec?.let { r -> runCatching { r.release() } }; rec = null } }

    AlertDialog(
        onDismissRequest = { file.delete(); onCancel() },
        title = { Text("Recording audio") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FiberManualRecord, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("%d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.headlineMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val r = rec
                if (r == null) { onCancel() } else {
                    val ok = runCatching { r.stop() }.isSuccess
                    runCatching { r.release() }; rec = null
                    if (ok && file.length() > 0) onDone(file) else { file.delete(); onError("Recording was too short.") }
                }
            }) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(4.dp)); Text("Stop & attach") }
        },
        dismissButton = { TextButton({ file.delete(); onCancel() }) { Text("Cancel") } }
    )
}
