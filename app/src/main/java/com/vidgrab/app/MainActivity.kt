package com.vidgrab.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {
    private var shared by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadNotifications.createChannel(this)
        shared = extractUrl(intent)
        setContent { MaterialTheme { Screen(shared) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shared = extractUrl(intent)
    }

    private fun extractUrl(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_SEND) return ""
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        return Regex("https?://\\S+").find(text)?.value ?: text
    }
}

private enum class VideoQuality(val label: String, val maxHeight: Int?) {
    BEST("Meilleure disponible", null),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480)
}

private data class DownloadItem(
    val id: Int,
    val processId: String,
    val url: String,
    val progress: Float = 0f,
    val status: String = "En attente",
    val active: Boolean = true,
    val downloadedUri: Uri? = null
)

private data class DownloadResult(val fileName: String, val uri: Uri)

private object ActiveDownloadProcesses {
    private val watermarkProcesses = ConcurrentHashMap<String, Process>()
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()

    fun cancel(processId: String) {
        cancelledIds.add(processId)
        YoutubeDL.getInstance().destroyProcessById(processId)
        watermarkProcesses.remove(processId)?.destroyForcibly()
    }

    fun registerWatermark(processId: String, process: Process) {
        watermarkProcesses[processId] = process
        if (cancelledIds.contains(processId)) {
            watermarkProcesses.remove(processId, process)
            process.destroyForcibly()
        }
    }

    fun finish(processId: String) {
        watermarkProcesses.remove(processId)
        cancelledIds.remove(processId)
    }
}

@Composable
private fun Screen(initial: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var linksText by remember(initial) { mutableStateOf(initial) }
    var audioOnly by remember { mutableStateOf(false) }
    var frenchSubtitles by remember { mutableStateOf(true) }
    var quality by remember { mutableStateOf(VideoQuality.BEST) }
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var pendingLinks by remember { mutableStateOf(emptyList<String>()) }
    var pendingQuality by remember { mutableStateOf(VideoQuality.BEST) }
    var pendingAudioOnly by remember { mutableStateOf(false) }
    var pendingFrenchSubtitles by remember { mutableStateOf(true) }
    val items = remember { mutableStateListOf<DownloadItem>() }
    val downloadJobs = remember { mutableStateMapOf<Int, Job>() }

    val startDownloads: (List<String>, VideoQuality, Boolean, Boolean, Boolean) -> Unit =
        { links, selectedQuality, audio, includeFrenchSubtitles, notificationsEnabled ->
            message = if (notificationsEnabled) "" else
                "Notifications désactivées : la progression reste visible dans l’application."
            links.forEach { link ->
                val id = UUID.randomUUID().hashCode() and Int.MAX_VALUE
                val processId = UUID.randomUUID().toString()
                items.add(
                    DownloadItem(id = id, processId = processId, url = link, status = "Téléchargement")
                )
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    fun updateItem(progress: Float? = null, status: String? = null) {
                        val index = items.indexOfFirst { it.id == id }
                        if (index >= 0 && items[index].active) {
                            items[index] = items[index].copy(
                                progress = progress ?: items[index].progress,
                                status = status ?: items[index].status
                            )
                        }
                    }

                    try {
                        val result = download(
                            context,
                            link,
                            audio,
                            selectedQuality,
                            includeFrenchSubtitles,
                            processId,
                            onProgress = { value ->
                                scope.launch { updateItem(progress = value) }
                                if (notificationsEnabled) {
                                    DownloadNotifications.showProgress(context, id, link, value)
                                }
                            },
                            onProcessing = {
                                scope.launch { updateItem(status = "Ajout du filigrane…") }
                                if (notificationsEnabled) {
                                    DownloadNotifications.showProcessing(context, id, link)
                                }
                            }
                        )
                        val index = items.indexOfFirst { it.id == id }
                        if (index >= 0 && items[index].active) {
                            items[index] = items[index].copy(
                                progress = 100f,
                                status = "Terminé · ${result.fileName}",
                                active = false,
                                downloadedUri = result.uri
                            )
                        }
                        if (notificationsEnabled) {
                            DownloadNotifications.showCompleted(context, id, result.fileName)
                        }
                    } catch (e: CancellationException) {
                        val index = items.indexOfFirst { it.id == id }
                        if (index >= 0 && items[index].active) {
                            items[index] = items[index].copy(status = "Arrêté", active = false)
                        }
                        DownloadNotifications.cancel(context, id)
                        throw e
                    } catch (e: Exception) {
                        val index = items.indexOfFirst { it.id == id }
                        if (index >= 0 && items[index].active) {
                            items[index] = items[index].copy(
                                status = "Erreur · ${e.message ?: "échec du téléchargement"}",
                                active = false
                            )
                            if (notificationsEnabled) {
                                DownloadNotifications.showFailed(
                                    context,
                                    id,
                                    e.message ?: "Échec du téléchargement"
                                )
                            }
                        }
                    } finally {
                        downloadJobs.remove(id)
                        ActiveDownloadProcesses.finish(processId)
                    }
                }
                downloadJobs[id] = job
                job.start()
            }
        }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        startDownloads(
            pendingLinks,
            pendingQuality,
            pendingAudioOnly,
            pendingFrenchSubtitles,
            granted && DownloadNotifications.areNotificationsEnabled(context)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("VidGrab", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Télécharge plusieurs liens à la fois, avec le filigrane Adama Faye.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = linksText,
            onValueChange = { linksText = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            label = { Text("Liens vidéo (un par ligne)") },
            supportingText = { Text("Colle plusieurs liens pour les télécharger en parallèle.") }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = audioOnly, onCheckedChange = { audioOnly = it })
            Spacer(Modifier.width(12.dp))
            Text("Audio uniquement (MP3)")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = frenchSubtitles,
                onCheckedChange = { frenchSubtitles = it },
                enabled = !audioOnly
            )
            Spacer(Modifier.width(12.dp))
            Text("Sous-titres français activables", color = if (audioOnly) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Qualité vidéo :")
            Spacer(Modifier.width(12.dp))
            Button(onClick = { qualityMenuExpanded = true }, enabled = !audioOnly) {
                Text(if (audioOnly) "Sans objet (audio)" else quality.label)
            }
            DropdownMenu(
                expanded = qualityMenuExpanded,
                onDismissRequest = { qualityMenuExpanded = false }
            ) {
                VideoQuality.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            quality = option
                            qualityMenuExpanded = false
                        }
                    )
                }
            }
        }
        Button(
            onClick = {
                val links = parseLinks(linksText)
                if (links.isEmpty()) {
                    message = "Ajoute au moins un lien vidéo valide."
                } else {
                    pendingLinks = links
                    pendingQuality = quality
                    pendingAudioOnly = audioOnly
                    pendingFrenchSubtitles = frenchSubtitles
                    linksText = ""
                    message = ""
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        startDownloads(
                            links,
                            quality,
                            audioOnly,
                            frenchSubtitles,
                            DownloadNotifications.areNotificationsEnabled(context)
                        )
                    }
                }
            },
            enabled = linksText.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Télécharger ${parseLinks(linksText).size.coerceAtLeast(1)} lien(s)")
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        items.forEach { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(item.url, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Text(item.status, style = MaterialTheme.typography.bodyMedium)
                    if (item.status == "Téléchargement" || item.status.startsWith("Ajout du filigrane")) {
                        LinearProgressIndicator(
                            progress = { item.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${item.progress.toInt()} %")
                    }
                    Button(
                        onClick = {
                            if (item.active) {
                                ActiveDownloadProcesses.cancel(item.processId)
                                downloadJobs[item.id]?.cancel()
                                DownloadNotifications.cancel(context, item.id)
                                val index = items.indexOfFirst { it.id == item.id }
                                if (index >= 0) {
                                    items[index] = items[index].copy(
                                        status = "Arrêté",
                                        active = false
                                    )
                                }
                            } else {
                                try {
                                    item.downloadedUri?.let { uri ->
                                        check(context.contentResolver.delete(uri, null, null) > 0) {
                                            "Le fichier téléchargé n’a pas pu être supprimé."
                                        }
                                    }
                                    items.removeAll { it.id == item.id }
                                    DownloadNotifications.cancel(context, item.id)
                                } catch (e: Exception) {
                                    message = "Erreur de suppression : ${e.message ?: "échec"}"
                                }
                            }
                        }
                    ) {
                        Text(if (item.active) "Arrêter" else "Supprimer")
                    }
                }
            }
        }
    }
}

private fun parseLinks(text: String): List<String> =
    Regex("https?://[^\\s,;]+")
        .findAll(text)
        .map { it.value.trimEnd('.', ',', ';', ')', ']') }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()

private object DownloadNotifications {
    private const val CHANNEL_ID = "download_progress"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Téléchargements",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progression des téléchargements VidGrab"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permissionGranted &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    fun showProgress(context: Context, id: Int, url: String, progress: Float) {
        val percent = progress.toInt().coerceIn(0, 100)
        post(context, id, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("VidGrab · téléchargement")
            .setContentText("${hostName(url)} · $percent %")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent(context))
            .build())
    }

    fun showProcessing(context: Context, id: Int, url: String) {
        post(context, id, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("VidGrab · filigrane")
            .setContentText(hostName(url))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent(context))
            .build())
    }

    fun showCompleted(context: Context, id: Int, fileName: String) {
        post(context, id, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Téléchargement terminé")
            .setContentText(fileName)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build())
    }

    fun showFailed(context: Context, id: Int, message: String) {
        post(context, id, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Échec du téléchargement")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build())
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(id)
    }

    private fun hostName(url: String): String =
        android.net.Uri.parse(url).host ?: "Vidéo"

    private fun post(context: Context, id: Int, notification: Notification) {
        if (areNotificationsEnabled(context)) {
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        }
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

private suspend fun download(
    context: Context,
    url: String,
    audioOnly: Boolean,
    quality: VideoQuality,
    includeFrenchSubtitles: Boolean,
    processId: String,
    onProgress: (Float) -> Unit,
    onProcessing: () -> Unit
): DownloadResult = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "dl/${UUID.randomUUID()}").apply {
        check(mkdirs()) { "Impossible de préparer le téléchargement." }
    }
    try {
        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("-o", "${dir.absolutePath}/%(title).80s.%(ext)s")
        if (audioOnly) {
            request.addOption("-x")
            request.addOption("--audio-format", "mp3")
        } else {
            val height = quality.maxHeight
            request.addOption(
                "-f",
                if (height == null) "bv*+ba/b" else "bv*[height<=$height]+ba/b[height<=$height]"
            )
            request.addOption("--merge-output-format", "mp4")
            if (includeFrenchSubtitles) {
                request.addOption("--write-subs")
                request.addOption("--write-auto-subs")
                request.addOption("--sub-langs", "fr.*,fr")
                request.addOption("--embed-subs")
            }
        }
        currentCoroutineContext().ensureActive()
        YoutubeDL.getInstance().execute(request, processId) { progress, _, _ ->
            onProgress(progress.coerceIn(0f, 100f))
        }
        currentCoroutineContext().ensureActive()
        val videoExtensions = setOf("mp4", "mkv", "webm", "m4v", "mov", "flv", "avi")
        val downloadedFile = dir.listFiles()?.firstOrNull {
            it.isFile && it.extension.lowercase() in videoExtensions
        }
            ?: error("Le fichier téléchargé est introuvable.")
        val outputFile = if (audioOnly) downloadedFile else {
            onProcessing()
            addWatermark(context, downloadedFile, dir, processId)
        }
        currentCoroutineContext().ensureActive()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, outputFile.name)
            put(
                MediaStore.Downloads.MIME_TYPE,
                if (audioOnly) "audio/mpeg" else "video/mp4"
            )
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Impossible d’enregistrer le fichier dans Téléchargements.")
        try {
            val output = context.contentResolver.openOutputStream(uri)
                ?: error("Impossible d’ouvrir le fichier de destination.")
            output.use { stream -> outputFile.inputStream().use { it.copyTo(stream) } }
            currentCoroutineContext().ensureActive()
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
        DownloadResult(outputFile.name, uri)
    } finally {
        dir.deleteRecursively()
        ActiveDownloadProcesses.finish(processId)
    }
}

private fun addWatermark(context: Context, input: File, directory: File, processId: String): File {
    val ffmpeg = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
    check(ffmpeg.isFile) { "Le moteur vidéo FFmpeg est introuvable." }
    val ffmpegLibraries = File(
        context.noBackupFilesDir,
        "youtubedl-android/packages/ffmpeg/usr/lib"
    )
    check(ffmpegLibraries.isDirectory) { "Les bibliothèques FFmpeg ne sont pas initialisées." }
    val pythonLibraries = File(
        context.noBackupFilesDir,
        "youtubedl-android/packages/python/usr/lib"
    )
    check(pythonLibraries.isDirectory) { "Les bibliothèques Python ne sont pas initialisées." }
    val appNativeLibraries = File(context.filesDir, "native-libraries").apply {
        check(isDirectory || mkdirs()) { "Impossible de préparer les bibliothèques natives." }
    }
    listOf(
        "libcrypto.so.3",
        "libssl.so.3",
        "libexpat.so.1",
        "libandroid-posix-semaphore.so"
    ).forEach { name ->
        val library = File(appNativeLibraries, name)
        if (!library.isFile || library.length() == 0L) {
            context.assets.open("native-libs/$name").use { source ->
                library.outputStream().use { destination -> source.copyTo(destination) }
            }
        }
        check(library.isFile && library.length() > 0) { "Bibliothèque native incomplète : $name" }
    }

    val watermark = File(directory, "watermark.png")
    createWatermark(watermark)
    val output = File(directory, "${input.nameWithoutExtension}_Adama_Faye.mp4")
    val command = listOf(
        ffmpeg.absolutePath,
        "-y",
        "-i", input.absolutePath,
        "-i", watermark.absolutePath,
        "-filter_complex",
        "[1:v]scale=iw*0.24:-1[wm];[0:v][wm]overlay=(main_w-overlay_w)/2:(main_h-overlay_h)/2:format=auto",
        "-map", "0:v:0",
        "-map", "0:a?",
        "-map", "0:s?",
        "-c:v", "libx264",
        "-preset", "ultrafast",
        "-crf", "23",
        "-c:a", "copy",
        "-c:s", "mov_text",
        "-movflags", "+faststart",
        output.absolutePath
    )
    val process = ProcessBuilder(command).redirectErrorStream(true).apply {
        environment()["LD_LIBRARY_PATH"] =
            "${appNativeLibraries.absolutePath}:${ffmpegLibraries.absolutePath}:${pythonLibraries.absolutePath}:${context.applicationInfo.nativeLibraryDir}"
    }.start()
    ActiveDownloadProcesses.registerWatermark(processId, process)
    val logTail = ArrayDeque<String>()
    process.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { line ->
            if (logTail.size == 20) logTail.removeFirst()
            logTail.addLast(line)
        }
    }
    val exitCode = process.waitFor()
    watermark.delete()
    check(exitCode == 0 && output.isFile && output.length() > 0) {
        "Impossible d’ajouter le filigrane : ${logTail.joinToString("\n").takeLast(1200)}"
    }
    return output
}

private fun createWatermark(file: File) {
    val bitmap = Bitmap.createBitmap(1000, 260, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 178f
        textScaleX = 1.12f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        strokeJoin = Paint.Join.ROUND
    }
    val baseline = bitmap.height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f

    textPaint.style = Paint.Style.STROKE
    textPaint.strokeWidth = 16f
    textPaint.color = Color.argb(105, 95, 95, 95)
    canvas.drawText("VibGrab", bitmap.width / 2f, baseline, textPaint)

    textPaint.strokeWidth = 10f
    textPaint.color = Color.argb(180, 160, 160, 160)
    canvas.drawText("VibGrab", bitmap.width / 2f, baseline, textPaint)

    textPaint.strokeWidth = 4f
    textPaint.color = Color.argb(220, 245, 245, 245)
    canvas.drawText("VibGrab", bitmap.width / 2f, baseline, textPaint)

    textPaint.style = Paint.Style.FILL
    textPaint.color = Color.argb(95, 125, 125, 125)
    canvas.drawText("VibGrab", bitmap.width / 2f, baseline, textPaint)

    textPaint.style = Paint.Style.STROKE
    textPaint.strokeWidth = 1.5f
    textPaint.color = Color.argb(210, 220, 220, 220)
    canvas.drawText("VibGrab", bitmap.width / 2f, baseline, textPaint)

    file.outputStream().use { stream ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
            "Impossible de créer le filigrane."
        }
    }
    bitmap.recycle()
}
