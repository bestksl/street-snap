package dev.streetsnap.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle

class MainActivity : ComponentActivity() {
    var volumeShutter: ((Boolean) -> Boolean)? = null
    private val handledVolumeKeys = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFE2FF79),
                onPrimary = Color(0xFF172000),
                surface = Color(0xFF101110),
                surfaceContainer = Color(0xFF1B1D19),
                background = Color(0xFF101110),
            )) {
                CameraScreen(this)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolumeKey(keyCode) && acceptsVolumeShutter()) {
            if (keyCode in handledVolumeKeys) return true
            if (event.repeatCount == 0 && volumeShutter?.invoke(true) == true) {
                handledVolumeKeys += keyCode
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val handled = handledVolumeKeys.remove(keyCode)
        if (handled && acceptsVolumeShutter()) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) handledVolumeKeys.clear()
    }

    override fun onPause() {
        handledVolumeKeys.clear()
        super.onPause()
    }

    private fun isVolumeKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    private fun acceptsVolumeShutter() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        hasWindowFocus() && volumeShutter?.invoke(false) == true

    fun hasCameraPermission() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    }

    fun openPhoto(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "未找到可查看照片的应用", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "暂时无法打开这张照片", Toast.LENGTH_SHORT).show()
        }
    }
}
