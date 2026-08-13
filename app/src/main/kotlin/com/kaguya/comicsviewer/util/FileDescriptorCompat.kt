package com.kaguya.comicsviewer.util

import android.content.Context
import android.database.Cursor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile

/**
 * 提取 SAF DocumentFile 的真实文件系统路径等辅助工具。
 * 仅对外部存储（com.android.externalstorage.documents，docId 以 "primary:" 开头）有效，
 * 其它情况返回 null（需走 SAF 流）。
 */
object FileDescriptorCompat {

    /**
     * 提取外部存储上文档的真实文件系统路径，如 /storage/emulated/0/...。
     * 仅对 authority=com.android.externalstorage.documents 且 docId 以 "primary:" 开头的文档有效，
     * 其它情况返回 null（需走 SAF 流）。
     */
    fun path(file: DocumentFile): String? {
        val uri = file.uri
        if (uri.authority != "com.android.externalstorage.documents") {
            Log.d("FileDescriptorCompat", "path: unsupported authority=${uri.authority}")
            return null
        }
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        if (docId == null) {
            Log.d("FileDescriptorCompat", "path: getDocumentId failed, uri=$uri")
            return null
        }
        // docId 形如 "primary:<relative>" 或 "<uuid>:<relative>"
        val (volume, relative) = docId.split(":", limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
        val base = when {
            volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
            else -> "/storage/$volume"
        }
        val full = "$base/$relative".replace("//", "/")
        Log.d("FileDescriptorCompat", "path: docId='$docId' -> '$full'")
        // 验证文件确实可读，否则回退到 SAF 流（复制）路径
        return if (java.io.File(full).canRead()) {
            Log.d("FileDescriptorCompat", "path: verified readable: '$full'")
            full
        } else {
            Log.w("FileDescriptorCompat", "path: file not readable, fallback to copy: '$full'")
            null
        }
    }

    fun length(context: Context, file: DocumentFile): Long {
        // 1. 最可靠：对外部存储 provider，直接提取文件路径用 java.io.File 获取大小
        val filePathLen = runCatching {
            val p = path(file)
            if (p != null) java.io.File(p).length() else 0L
        }.getOrDefault(0L)
        if (filePathLen > 0) return filePathLen
        // 2. 尝试 DocumentFile.length()
        val docLen = runCatching { file.length() }.getOrDefault(0L)
        if (docLen > 0) return docLen
        // 3. 回退：使用 ContentResolver 查询 OpenableColumns.SIZE
        val resolverSize = runCatching {
            context.contentResolver.query(
                file.uri,
                arrayOf(OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) cursor.getLong(idx) else 0L
                } else 0L
            } ?: 0L
        }.getOrDefault(0L)
        if (resolverSize > 0) return resolverSize
        // 4. 回退：通过 InputStream 读取字节计数
        return runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { it.available().toLong() } ?: 0L
        }.getOrDefault(0L)
    }
}
