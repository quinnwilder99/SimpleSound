package com.simplesound.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Whether the app may read the device's audio library. Without it a MediaStore query
 * does not throw -- it just returns only this app's own files (i.e. nothing) -- which
 * a library sync would otherwise mistake for "every track was removed".
 */
fun hasAudioPermission(context: Context): Boolean {
    val permission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
