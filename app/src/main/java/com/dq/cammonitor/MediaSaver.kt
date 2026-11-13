package com.dq.cammonitor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Lưu ảnh chụp màn hình và video preview vào thư viện của điện thoại. */
object MediaSaver {

    private const val FOLDER = "CamMonitor"

    /** Trả về mô tả nơi lưu, hoặc null nếu lỗi. */
    fun saveImage(context: Context, bitmap: Bitmap, name: String): String? {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                val stream = resolver.openOutputStream(uri) ?: return null
                stream.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it) }
                return "Thư viện ảnh, album " + FOLDER
            }
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
            val file = File(dir, name)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it) }
            return file.absolutePath
        } catch (e: Exception) {
            return null
        }
    }

    /** Chuyển file video tạm vào thư viện. Trả về mô tả nơi lưu, hoặc null nếu lỗi. */
    fun publishVideo(context: Context, file: File): String? {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER)
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                val out = resolver.openOutputStream(uri) ?: return null
                out.use { o -> FileInputStream(file).use { i -> i.copyTo(o) } }
                file.delete()
                return "Thư viện video, album " + FOLDER
            }
            return file.absolutePath
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Chuyển file ảnh vừa tải từ máy ảnh vào thư viện, giữ nguyên dữ liệu gốc.
     * Trả về mô tả nơi lưu, hoặc null nếu lỗi. File tạm luôn được xoá.
     */
    fun publishPhoto(context: Context, file: File, name: String): String? {
        try {
            val lower = name.lowercase()
            val mime = when {
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".hif") || lower.endsWith(".heif") || lower.endsWith(".heic") -> "image/heif"
                lower.endsWith(".arw") -> "image/x-sony-arw"
                else -> "application/octet-stream"
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
                val resolver = context.contentResolver
                val isImage = mime.startsWith("image/")
                val collection = if (isImage) {
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                } else {
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }
                val uri = resolver.insert(collection, values) ?: return null
                val out = resolver.openOutputStream(uri) ?: return null
                out.use { o -> FileInputStream(file).use { i -> i.copyTo(o) } }
                return if (isImage) "Thư viện ảnh, album " + FOLDER else "Thư mục Download/" + FOLDER
            }
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
            val dest = File(dir, name)
            FileOutputStream(dest).use { o -> FileInputStream(file).use { i -> i.copyTo(o) } }
            return dest.absolutePath
        } catch (e: Exception) {
            return null
        } finally {
            file.delete()
        }
    }
}
