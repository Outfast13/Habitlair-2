@file:OptIn(ExperimentalLayoutApi::class)

package com.habitlair.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

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

// ------------------------------------------------------------------------------------------------
// Voice typing (speech to text) for any text field
// ------------------------------------------------------------------------------------------------

fun appendSpoken(old: String, spoken: String): String = if (old.isBlank()) spoken else old.trimEnd() + " " + spoken

/** Returns a function that opens the system voice-typing dialog and delivers the recognised text. */
@Composable
fun rememberDictation(onText: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val current by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) current(text)
    }
    return {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
        try { launcher.launch(i) } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "No voice typing service found on this device", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun DictateButton(onText: (String) -> Unit) {
    val start = rememberDictation(onText)
    IconButton(start) { Icon(Icons.Filled.Mic, "Voice typing") }
}

// ------------------------------------------------------------------------------------------------
// Attachment display
// ------------------------------------------------------------------------------------------------

private fun openExternally(ctx: Context, media: com.habitlair.app.data.MediaFiles, a: Attachment) {
    try {
        val f = File(a.path)
        val ext = f.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(media.uriFor(f), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, a.name))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun AttachmentGrid(items: List<Attachment>, onRemove: ((Attachment) -> Unit)? = null) {
    if (items.isEmpty()) return
    val ctx = LocalContext.current
    val media = LocalContainer.current.media
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
                    MediaKind.FILE -> Column(Modifier.fillMaxSize().clickable { openExternally(ctx, media, a) }.padding(6.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Description, "Open file", Modifier.size(34.dp), MaterialTheme.colorScheme.primary)
                        Text(a.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
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
                    factory = { c ->
                        VideoView(c).also { v ->
                            val mc = MediaController(c); mc.setAnchorView(v)
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
    AttachmentBar(draft)
}

// ------------------------------------------------------------------------------------------------
// Attachment buttons: Media · Camera · Audio · Files (icons only unless Accessibility says otherwise)
// ------------------------------------------------------------------------------------------------

private fun kindOf(mime: String?): String = when {
    mime == null -> MediaKind.FILE
    mime.startsWith("image/") -> MediaKind.PHOTO
    mime.startsWith("video/") -> MediaKind.VIDEO
    mime.startsWith("audio/") -> MediaKind.AUDIO
    else -> MediaKind.FILE
}

@Composable
private fun AttachButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, showLabel: Boolean, onClick: () -> Unit) {
    if (showLabel) FilledTonalButton(onClick, contentPadding = PaddingValues(horizontal = 14.dp)) {
        Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(label)
    } else FilledTonalIconButton(onClick, Modifier.size(48.dp)) { Icon(icon, label) }
}

@Composable
fun AttachmentBar(draft: AttachmentDraft) {
    val ctx = LocalContext.current
    val media = LocalContainer.current.media
    val scope = rememberCoroutineScope()
    val labels = LocalIconLabels.current
    var showCamera by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun importUri(uri: Uri) {
        scope.launch {
            try {
                val kind = kindOf(ctx.contentResolver.getType(uri))
                draft.add(withContext(Dispatchers.IO) { media.copyIn(uri, kind) })
            } catch (e: Exception) { msg = "Couldn't attach that file: ${e.message}" }
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> uris.forEach { importUri(it) } }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach { importUri(it) } }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recording = true else msg = "Microphone permission denied."
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AttachButton(Icons.Filled.PhotoLibrary, "Media", labels) { mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
        AttachButton(Icons.Filled.CameraAlt, "Camera", labels) { showCamera = true }
        AttachButton(Icons.Filled.Mic, "Audio", labels) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recording = true
            else micPerm.launch(Manifest.permission.RECORD_AUDIO)
        }
        AttachButton(Icons.Filled.AttachFile, "Files", labels) { filePicker.launch(arrayOf("*/*")) }
    }
    msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    if (showCamera) CameraCaptureDialog(
        onCaptured = { f, kind -> draft.add(media.attachmentFor(f, kind)); showCamera = false },
        onDismiss = { showCamera = false }
    )
    if (recording) AudioRecorderDialog(
        onDone = { f -> draft.add(media.attachmentFor(f, MediaKind.AUDIO, "Recording")); recording = false },
        onError = { msg = it; recording = false },
        onCancel = { recording = false }
    )
}

// ------------------------------------------------------------------------------------------------
// In-app camera (CameraX): photo + video, torch, zoom, front/back
// ------------------------------------------------------------------------------------------------

private fun granted(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

@Composable
fun CameraCaptureDialog(onCaptured: (File, String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var camOk by remember { mutableStateOf(granted(ctx, Manifest.permission.CAMERA)) }
    var micOk by remember { mutableStateOf(granted(ctx, Manifest.permission.RECORD_AUDIO)) }
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it; if (!it) onDismiss() }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it }
    LaunchedEffect(Unit) { if (!camOk) camLauncher.launch(Manifest.permission.CAMERA) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (camOk) CameraBody(micOk, { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }, onCaptured, onDismiss)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CameraBody(micOk: Boolean, requestMic: () -> Unit, onCaptured: (File, String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val media = LocalContainer.current.media
    val executor = remember { ContextCompat.getMainExecutor(ctx) }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var front by remember { mutableStateOf(false) }
    var videoMode by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recSecs by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(front, videoMode) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                if (videoMode) {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                        .build()
                    val vc = VideoCapture.withOutput(recorder)
                    camera = provider.bindToLifecycle(owner, selector, preview, vc)
                    videoCapture = vc; imageCapture = null
                } else {
                    val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                    camera = provider.bindToLifecycle(owner, selector, preview, ic)
                    imageCapture = ic; videoCapture = null
                }
                hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                torch = false
                zoom = 0f
                error = null
            } catch (e: Exception) {
                error = "Camera unavailable: ${e.message}"
            }
        }, executor)
        onDispose {
            if (future.isDone) runCatching { future.get().unbindAll() }
        }
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    LaunchedEffect(zoom, camera) { camera?.cameraControl?.setLinearZoom(zoom) }

    fun takePhoto() {
        val ic = imageCapture ?: return
        val f = media.newFile("jpg")
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) { onCaptured(f, MediaKind.PHOTO) }
            override fun onError(exception: ImageCaptureException) { f.delete(); error = "Couldn't take the photo: ${exception.message}" }
        })
    }

    fun toggleRecording() {
        val active = recording
        if (active != null) { active.stop(); return }
        val vc = videoCapture ?: return
        val f = media.newFile("mp4")
        var pending = vc.output.prepareRecording(ctx, FileOutputOptions.Builder(f).build())
        if (micOk) pending = pending.withAudioEnabled()
        recording = pending.start(executor) { ev ->
            if (ev is VideoRecordEvent.Status) recSecs = (ev.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()
            if (ev is VideoRecordEvent.Finalize) {
                recording = null; recSecs = 0
                if (!ev.hasError() && f.length() > 0) onCaptured(f, MediaKind.VIDEO) else { f.delete(); error = "Recording failed or was too short." }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize().pointerInput(camera) {
            detectTransformGestures { _, _, g, _ -> if (g != 1f) zoom = (zoom + (g - 1f) * 0.6f).coerceIn(0f, 1f) }
        })

        Row(Modifier.fillMaxWidth().systemBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ recording?.stop(); onClose() }) { Icon(Icons.Filled.Close, "Close camera", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            if (recording != null) Text("● %d:%02d".format(recSecs / 60, recSecs % 60), color = Color(0xFFFF5252), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (hasFlash) IconButton({ torch = !torch }) { Icon(if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff, if (torch) "Torch off" else "Torch on", tint = if (torch) Color(0xFFFFD600) else Color.White) }
            IconButton({ if (recording == null) front = !front }, enabled = recording == null) { Icon(Icons.Filled.FlipCameraAndroid, "Switch camera", tint = Color.White) }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            error?.let { Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ZoomOut, null, tint = Color.White)
                Slider(zoom, { zoom = it }, Modifier.weight(1f).padding(horizontal = 8.dp))
                Icon(Icons.Filled.ZoomIn, null, tint = Color.White)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!videoMode, { if (recording == null) videoMode = false }, { Text("Photo") }, leadingIcon = { Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp)) })
                FilterChip(videoMode, { if (recording == null) { videoMode = true; if (!micOk) requestMic() } }, { Text("Video") }, leadingIcon = { Icon(Icons.Filled.Videocam, null, Modifier.size(18.dp)) })
            }
            Spacer(Modifier.height(12.dp))
            val rec = recording != null
            Box(
                Modifier.size(76.dp).clip(CircleShape).background(Color.White).clickable { if (videoMode) toggleRecording() else takePhoto() },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(if (rec) 28.dp else 60.dp).clip(if (rec) RoundedCornerShape(6.dp) else CircleShape).background(if (videoMode) Color(0xFFE53935) else Color(0xFFE0E0E0)))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Audio recorder: waveform, timer, pause/resume, stop (styled like a dedicated recorder app)
// ------------------------------------------------------------------------------------------------

private val RecBg = Color(0xFF00202B)
private val RecAccent = Color(0xFF9FCBFF)
private val RecText = Color(0xFFBEE3FF)
private val RecWave = Color(0xFF8AB4F8)
private val RecYellow = Color(0xFFFFD600)

private fun fmtClock(ms: Long): String { val s = ms / 1000; return "%02d:%02d".format(s / 60, s % 60) }
private fun fmtSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} kB"
    else -> "%.1f MB".format(b / 1048576.0)
}

@Composable
fun AudioRecorderDialog(onDone: (File) -> Unit, onError: (String) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val media = LocalContainer.current.media
    val file = remember { media.newFile("m4a") }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var paused by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var sizeBytes by remember { mutableLongStateOf(0L) }
    val amps = remember { mutableStateListOf<Float>() }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            var last = SystemClock.elapsedRealtime()
            var sinceSize = 0L
            while (true) {
                delay(50)
                val now = SystemClock.elapsedRealtime()
                if (!paused) {
                    elapsedMs += now - last
                    amps.add(sqrt((r.maxAmplitude / 32767f).coerceIn(0f, 1f)))
                    sinceSize += now - last
                    if (sinceSize >= 500) { sizeBytes = file.length(); sinceSize = 0 }
                }
                last = now
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            file.delete()
            onError("Couldn't start recording: ${e.message}")
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (!finished) { recorder?.let { runCatching { it.release() } }; recorder = null; file.delete() }
        }
    }

    Dialog(onDismissRequest = { onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier.fillMaxSize().background(RecBg).systemBarsPadding().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Audio Recorder", color = RecText, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            Waveform(amps, elapsedMs)
            Spacer(Modifier.weight(1f))
            Text(fmtClock(elapsedMs), color = RecText, fontSize = 72.sp, style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(8.dp))
            Text(if (paused) "Paused" else "Recording…", color = RecText, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text("${fmtSize(sizeBytes)}, m4a, 128 kbps, 44 kHz", color = Color(0xFFB0BEC5), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(20.dp))
            Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF0B4F8A)))
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton({ onCancel() }, Modifier.size(56.dp)) { Icon(Icons.Filled.Delete, "Discard recording", tint = RecAccent, modifier = Modifier.size(30.dp)) }
                Box(
                    Modifier.size(110.dp).clip(CircleShape).background(RecAccent).clickable {
                        val r = recorder
                        if (r != null) runCatching { if (paused) r.resume() else r.pause() }.onSuccess { paused = !paused }
                    },
                    contentAlignment = Alignment.Center
                ) { Text(if (paused) "Resume" else "Pause", color = Color(0xFF00315A), style = MaterialTheme.typography.titleLarge) }
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFBEE3FF)).clickable {
                        val r = recorder
                        if (r != null) {
                            val ok = runCatching { r.stop() }.isSuccess
                            runCatching { r.release() }
                            recorder = null
                            finished = true
                            if (ok && file.length() > 0) onDone(file) else { file.delete(); onError("The recording was too short.") }
                        }
                    },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Stop, "Stop and attach", tint = Color(0xFF00315A), modifier = Modifier.size(30.dp)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Waveform(amps: List<Float>, elapsedMs: Long) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = RecText, fontSize = 13.sp)
    Canvas(Modifier.fillMaxWidth().height(260.dp)) {
        val pps = 90.dp.toPx()                // pixels per second
        val cx = size.width / 2f
        val sec = elapsedMs / 1000f
        val midY = size.height / 2f
        val startT = sec - cx / pps
        val endT = sec + (size.width - cx) / pps

        var t = floor(startT).toInt()
        while (t <= ceil(endT).toInt()) {
            if (t >= 0) {
                val x = cx + (t - sec) * pps
                val major = t % 2 == 0
                val lineTop = if (major) 40.dp.toPx() else 70.dp.toPx()
                val lineBottom = size.height - lineTop
                drawLine(RecWave.copy(alpha = if (major) 0.55f else 0.35f), Offset(x, lineTop), Offset(x, lineBottom), strokeWidth = if (major) 2f else 1.5f)
                if (major) {
                    val label = fmtClock(t * 1000L)
                    drawText(measurer, label, Offset(x - 24.dp.toPx(), 8.dp.toPx()), labelStyle)
                    drawText(measurer, label, Offset(x - 24.dp.toPx(), size.height - 30.dp.toPx()), labelStyle)
                }
            }
            t++
        }
        val maxH = size.height * 0.55f
        val first = max(0, (startT / 0.05f).toInt())
        for (i in first until amps.size) {
            val x = cx + (i * 0.05f - sec) * pps
            val h = max(2f, amps[i] * maxH)
            drawLine(RecWave, Offset(x, midY - h / 2f), Offset(x, midY + h / 2f), strokeWidth = 3f)
        }
        drawLine(RecYellow, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 5f)
    }
}
