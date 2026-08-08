package com.kaguya.comicsviewer.data.source.smb

import android.util.Log
import com.kaguya.comicsviewer.domain.model.ComicSource
import jcifs.CIFSContext
import jcifs.CIFSException
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmbClient @Inject constructor() {

    companion object {
        private const val TAG = "SmbClient"
        private const val SMB_TIMEOUT_MS = 10_000L
    }

    private fun createProps(): Properties = Properties().apply {
        setProperty("jcifs.smb.client.disablePlainTextPasswords", "false")
        setProperty("jcifs.smb.client.minVersion", "SMB202")
        setProperty("jcifs.smb.client.maxVersion", "SMB311")
        setProperty("jcifs.smb.client.responseTimeout", "10000")
        setProperty("jcifs.smb.client.soTimeout", "10000")
        setProperty("jcifs.smb.client.connTimeout", "10000")
        setProperty("jcifs.smb.client.dfs.disabled", "true")
        setProperty("jcifs.netbios.cachePolicy", "0")
        setProperty("jcifs.smb.client.useBatching", "false")
    }

    private fun createCtx(host: String, port: Int, username: String, password: String): CIFSContext {
        val base = BaseContext(PropertyConfiguration(createProps()))
        val auth = NtlmPasswordAuthenticator(username, password)
        return base.withCredentials(auth)
    }

    private fun createContext(source: ComicSource): CIFSContext {
        val host = source.host?.ifBlank { null } ?: error("host missing")
        val port = host.substringAfterLast(':', "445").toIntOrNull() ?: 445
        val hostOnly = host.substringBeforeLast(':')
        return createCtx(hostOnly, port, source.username.orEmpty(), source.password ?: "")
    }

    private fun buildSmbUrl(host: String, share: String, path: String?): String {
        val port = host.substringAfterLast(':', "445").toIntOrNull() ?: 445
        val hostOnly = host.substringBeforeLast(':')
        val sb = StringBuilder("smb://$hostOnly:$port/$share/")
        if (!path.isNullOrBlank()) {
            sb.append(path.trim('/')).append('/')
        }
        return sb.toString()
    }

    /** 测试连接：尝试连接主机并列出共享 */
    suspend fun testConnection(
        host: String,
        port: Int,
        username: String,
        password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(SMB_TIMEOUT_MS) {
                val ctx = createCtx(host, port, username, password)
                val smbUrl = "smb://$host:$port/"
                Log.d(TAG, "testConnection: connecting to $smbUrl")
                val file = SmbFile(smbUrl, ctx)
                file.connect()
                Log.d(TAG, "testConnection: connected successfully")
                "连接成功"
            }
        }
    }

    /** 列出服务器上的所有共享名 */
    suspend fun listShares(
        host: String,
        port: Int,
        username: String,
        password: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(SMB_TIMEOUT_MS) {
                val ctx = createCtx(host, port, username, password)
                val smbUrl = "smb://$host:$port/"
                Log.d(TAG, "listShares: listing $smbUrl")
                val file = SmbFile(smbUrl, ctx)
                val listing = file.listFiles() ?: emptyArray()
                Log.d(TAG, "listShares: found ${listing.size} entries")
                listing.map { it.name.trimEnd('/') }.filter { it.isNotBlank() }.sorted()
            }
        }
    }

    /** 目录条目 */
    data class DirEntry(val name: String, val isDirectory: Boolean)

    /** 列出指定共享下某个路径的所有条目（目录+文件） */
    suspend fun listDirEntries(
        host: String,
        port: Int,
        username: String,
        password: String,
        share: String,
        path: String
    ): Result<List<DirEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(SMB_TIMEOUT_MS) {
                val ctx = createCtx(host, port, username, password)
                val pathPart = if (path.isNotBlank()) "${path.trim('/')}/" else ""
                val smbUrl = "smb://$host:$port/$share/$pathPart"
                Log.d(TAG, "listDirEntries: listing $smbUrl")
                val file = SmbFile(smbUrl, ctx)
                val listing = file.listFiles() ?: emptyArray()
                Log.d(TAG, "listDirEntries: found ${listing.size} entries")
                listing.filter { it.name != "." && it.name != ".." }
                    .map { DirEntry(name = it.name.trimEnd('/'), isDirectory = it.isDirectory) }
                    .sortedBy { if (it.isDirectory) "0_${it.name}" else "1_${it.name}" }
            }
        }
    }

    /** 扫描漫画文件 */
    suspend fun scanRecursive(source: ComicSource, path: String?): List<String> = withContext(Dispatchers.IO) {
        withTimeout(SMB_TIMEOUT_MS * 6) { // 扫描给 60 秒超时
            val ctx = createContext(source)
            val share = source.share?.ifBlank { null } ?: error("share missing")
            val hostStr = source.host ?: error("host missing")
            val port = hostStr.substringAfterLast(':', "445").toIntOrNull() ?: 445
            val url = buildSmbUrl(hostStr, share, path)
            Log.d(TAG, "scanRecursive: scanning $url")
            val root = SmbFile(url, ctx)
            val result = mutableListOf<String>()
            // 构建 URL 前缀用于计算相对路径
            val urlPrefix = "smb://${hostStr.substringBefore(':')}:$port/$share/"
            collectArchives(root, result, urlPrefix)
            Log.d(TAG, "scanRecursive: found ${result.size} archives")
            result
        }
    }

    private fun collectArchives(dir: SmbFile, result: MutableList<String>, urlPrefix: String) {
        val children = try {
            dir.listFiles() ?: return
        } catch (e: CIFSException) {
            Log.w(TAG, "collectArchives: listFiles failed for ${dir.url}: ${e.message}")
            return
        }
        for (child in children) {
            if (child.name == "." || child.name == "..") continue
            try {
                when {
                    child.isDirectory -> {
                        Log.d(TAG, "collectArchives: entering dir ${child.name}")
                        collectArchives(child, result, urlPrefix)
                    }
                    child.isFile -> {
                        val name = child.name.lowercase()
                        if (name.endsWith(".cbz") || name.endsWith(".zip") ||
                            name.endsWith(".cbr") || name.endsWith(".rar")
                        ) {
                            val relPath = child.url.toString().removePrefix(urlPrefix)
                            result.add(relPath)
                            Log.d(TAG, "collectArchives: found archive $relPath")
                        }
                    }
                }
            } catch (e: CIFSException) {
                Log.w(TAG, "collectArchives: error processing ${child.url}: ${e.message}")
            }
        }
    }

    /** 获取文件大小 */
    suspend fun getFileSize(source: ComicSource, remotePath: String): Long = withContext(Dispatchers.IO) {
        val ctx = createContext(source)
        val share = source.share?.ifBlank { null } ?: error("share missing")
        val url = buildSmbUrl(source.host ?: error("host missing"), share, null) + remotePath.trimStart('/')
        runCatching { SmbFile(url, ctx).length() }.getOrDefault(-1L)
    }

    /** 打开输入流读取远程文件 */
    suspend fun readFile(source: ComicSource, remotePath: String, block: suspend (java.io.InputStream) -> Unit) = withContext(Dispatchers.IO) {
        val ctx = createContext(source)
        val share = source.share?.ifBlank { null } ?: error("share missing")
        val url = buildSmbUrl(source.host ?: error("host missing"), share, null) + remotePath.trimStart('/')
        val file = SmbFile(url, ctx)
        file.inputStream.use { block(it) }
    }
}
