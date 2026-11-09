package com.example.pocketcraft

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.example.pocketcraft.engine.AndroidPdfManual
import com.example.pocketcraft.engine.FontRegistry
import com.example.pocketcraft.engine.PixelAutoCaptioner
import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.model.CustomFontEntry
import com.example.pocketcraft.platform.PlatformHooks
import com.example.pocketcraft.ui.TrimAwareVideoPlayer
import java.io.File

/** Android-Umsetzung der plattformabhängigen Funktionen. */
class AndroidPlatformHooks(context: Context) : PlatformHooks {

    private val appContext = context.applicationContext

    override val isMobile: Boolean = true

    private val clipboard: ClipboardManager?
        get() = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun copyToClipboard(text: String) {
        clipboard?.setPrimaryClip(ClipData.newPlainText("PocketScript", text))
    }

    override fun readClipboardText(): String? =
        clipboard?.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()

    override fun saveManualPdf(): File = AndroidPdfManual.generatePdfFile(appContext)

    override fun startAutoCaptions(engine: ProjectEngine) {
        PixelAutoCaptioner.startLiveRecognitionOrFallback(appContext, engine)
    }

    override fun customFontFamily(entry: CustomFontEntry): FontFamily =
        FontFamily(FontRegistry.resolveAndroidTypeface(entry.id, listOf(entry)))

    @Composable
    override fun VideoPreview(engine: ProjectEngine, videoUri: String, modifier: Modifier) {
        TrimAwareVideoPlayer(engine = engine, videoUri = videoUri, modifier = modifier)
    }
}
