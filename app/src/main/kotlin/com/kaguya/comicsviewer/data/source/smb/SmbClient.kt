package com.kaguya.comicsviewer.data.source.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.kaguya.comicsviewer.domain.model.ComicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class SmbSession(
    private val connection: Connection,
    private val session: Session,
    val share: DiskShare
) : Closeable {
    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
    }
}

@Singleton
class SmbClient @Inject constructor() {
    private val config = SmbConfig.builder()
        .withTimeout(20, TimeUnit.SECONDS)
        .withSoTimeout(60, TimeUnit.SECONDS)
        .withMultiProtocolNegotiate(true)
        .build()
    private val client = SMBClient(config)

    suspend fun open(source: ComicSource): SmbSession = withContext(Dispatchers.IO) {
        val host = source.host ?: error("host missing")
        val shareName = source.share ?: error("share missing")
        val port = host.substringAfterLast(':', "445").toIntOrNull() ?: 445
        val hostOnly = host.substringBeforeLast(':')
        val connection = client.connect(hostOnly, port)
        val auth = AuthenticationContext(
            source.username.orEmpty(),
            (source.password ?: "").toCharArray(),
            source.domain.orEmpty()
        )
        val session = connection.authenticate(auth)
        val share = session.connectShare(shareName) as DiskShare
        SmbSession(connection, session, share)
    }

    suspend fun listDir(session: SmbSession, path: String): List<SmbEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<SmbEntry>()
        val listing = session.share.list(path)
        for (info in listing) {
            val name = info.fileName
            if (name == "." || name == "..") continue
            val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            entries += SmbEntry(
                name = name,
                size = if (info.endOfFile >= 0) info.endOfFile else 0L,
                isDirectory = isDir
            )
        }
        entries
    }

    suspend fun exists(session: SmbSession, path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { session.share.fileExists(path) }.getOrDefault(false)
    }

    suspend fun openInput(session: SmbSession, path: String): InputStream = withContext(Dispatchers.IO) {
        val entry = session.share.open(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.noneOf(FileAttributes::class.java),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.noneOf(SMB2CreateOptions::class.java)
        )
        (entry as File).inputStream
    }

    suspend fun getFileSize(session: SmbSession, path: String): Long = withContext(Dispatchers.IO) {
        val info = session.share.getFileInformation(path)
        info.standardInformation.endOfFile
    }
}

data class SmbEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean
)
