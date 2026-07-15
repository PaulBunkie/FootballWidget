package com.shrewd.bet.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

object LogoManager {
    private const val LOGO_DIR = "logos"
    private const val BASE_URL = "https://aitube.fly.dev/api/team-logo/"

    fun getLogo(context: Context, teamId: Int?): Bitmap? {
        if (teamId == null || teamId == 0) return null
        val file = getLogoFile(context, teamId)
        return if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)
        } else {
            null
        }
    }

    fun downloadLogo(context: Context, client: OkHttpClient, teamId: Int?) {
        if (teamId == null || teamId == 0) return
        val file = getLogoFile(context, teamId)
        if (file.exists() && file.length() > 0) return

        val url = "$BASE_URL$teamId"
        Log.d("LogoManager", "Starting download: $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.code != 204) {
                    val body = response.body
                    if (body == null) {
                        Log.e("LogoManager", "Empty body for $teamId")
                        return
                    }
                    val bytes = body.bytes()
                    if (bytes.isEmpty()) {
                        Log.d("LogoManager", "Logo body is empty (0 bytes) for team $teamId, skipping save")
                        return
                    }
                    val parent = file.parentFile
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs()
                    }
                    FileOutputStream(file).use { it.write(bytes) }
                    Log.d("LogoManager", "Successfully downloaded logo for team $teamId (${bytes.size} bytes)")
                } else {
                    Log.d("LogoManager", "Skipping logo for $teamId: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e("LogoManager", "Exception downloading logo for $teamId", e)
        }
    }

    private fun getLogoFile(context: Context, teamId: Int): File {
        val dir = File(context.filesDir, LOGO_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$teamId.png")
    }
}
