package com.example.burplite.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shares the generated root CA .pem via the system share sheet, so the
 * user can send it to "Install a certificate" (Settings > Security)
 * or airdrop/share it to another device, without pulling files
 * manually with adb.
 */
object CertShare {
    fun shareRootCa(context: Context, pemPath: String) {
        val file = File(pemPath)
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-pem-file"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_TEXT,
                "BurpLite root CA — install this under Settings > Security > " +
                    "Encryption & credentials > Install a certificate > CA certificate, " +
                    "on the SAME device you're testing on (lab traffic only)."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share/install BurpLite root CA"))
    }
}
