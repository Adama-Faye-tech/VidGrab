package com.vidgrab.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yausername.youtubedl_android.DownloadProgressCallback
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var shared by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

@Composable
fun Screen(initial: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember(initial) { mutableStateOf(initial) }
    var audio by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("VidGrab", style = MaterialTheme.typography.headlineLarge)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Lien de la vidéo") }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = audio, onCheckedChange = { audio = it })
            Spacer(Modifier.width(12.dp))
            Text("Audio uniquement (MP3)")
        }
        Button(
            onClick = {
                busy = true
                progress = 0f
                status = ""
                scope.launch {
                    status = try {
                        "Enregistré dans Téléchargements : " + download(ctx, url.trim(), audio) { progress = it }
                    } catch (e: Exception) {
                        "Erreur : " + (e.message ?: "échec")
                    }
                    busy = false
                }
            },
            enabled = !busy && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Téléchargement…" else "Télécharger") }
        if (busy) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        Text(status)
    }
}

suspend fun download(ctx: Context, url: String, audio: Boolean, onProgress: (Float) -> Unit): String =
    withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("-o", "${dir.absolutePath}/%(title).80s.%(ext)s")
        if (audio) {
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
        } else {
            req.addOption("-f", "bv*+ba/b")
            req.addOption("--merge-output-format", "mp4")
        }
        YoutubeDL.getInstance().execute(req, null, DownloadProgressCallback { p, _, _ ->
            onProgress(p.coerceIn(0f, 100f))
        })
        val file = dir.listFiles()!!.first()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, if (audio) "audio/mpeg" else "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        file.delete()
        file.name
    }
