package me.lingci.lib.archive

import me.lingci.lib.base.util.Log
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IArchiveOpenCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Date

/**
 * 封装类，用于管理 RandomAccessFile 和 IInArchive 的生命周期
 * 解决过早关闭流导致的 Native Crash 问题
 */
private class ArchiveHandle(
    private val randomAccessFile: RandomAccessFile,
    val archive: IInArchive
) : Closeable {

    override fun close() {
        try {
            archive.close()
        } catch (e: Exception) {
            Log.d(this, "Error closing archive", e)
        }
        try {
            randomAccessFile.close()
        } catch (e: Exception) {
            Log.d(this, "Error closing RAF", e)
        }
    }
}

object ZArchiveFile {

    /** 十六进制字符表（rawNameHex 用） */
    private const val HEX_CHARS = "0123456789abcdef"

    /**
     * 打开阶段的密码回调：7-Zip 在解析加密头（如 7z header 加密）时会通过
     * [ICryptoGetTextPassword] 索要密码，这里返回调用方传入的密码。
     */
    private class PasswordOpenCallback(private val password: String?) :
        IArchiveOpenCallback, ICryptoGetTextPassword {

        /** 7-Zip 是否在打开阶段索要过密码（用于区分头加密与文件损坏） */
        var passwordRequested = false
            private set

        override fun setTotal(files: Long?, bytes: Long?) {}
        override fun setCompleted(files: Long?, bytes: Long?) {}

        override fun cryptoGetTextPassword(): String? {
            passwordRequested = true
            return password
        }
    }

    private fun isPasswordError(error: Throwable): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return message.contains("password") || message.contains("encrypted")
    }

    fun listZEntry(zPath: String, password: String): List<ZArchiveEntry> {
        val lowerPath = zPath.lowercase()
        return when {
            lowerPath.endsWith(".zip") -> listSevenZEntry(zPath, password)
            lowerPath.endsWith(".7z") -> listSevenZEntry(zPath, password)
            lowerPath.endsWith(".rar") -> RarFile.listZEntry(zPath, password)
            else -> emptyList()
        }
    }

    private fun openArchiveHandle(
        path: String,
        passwordCallback: PasswordOpenCallback,
        onOpenFailure: ((Exception) -> Unit)? = null
    ): ArchiveHandle? {
        return try {
            // 只读场景，使用 "r" 模式即可；"rw" 会要求文件可写，反而限制只读介质
            val raf = RandomAccessFile(path, "r")
            val inStream = RandomAccessFileInStream(raf)
            // 始终传入打开回调：头加密包会通过 ICryptoGetTextPassword 索要密码
            val archive = SevenZip.openInArchive(null, inStream, passwordCallback)
            ArchiveHandle(raf, archive)
        } catch (e: Exception) {
            Log.d(this, "openArchiveHandle failed", e)
            // 回调打开失败的异常，供调用方区分「密码问题」与「文件损坏/格式错误」
            onOpenFailure?.invoke(e)
            null
        }
    }

    fun listSevenZEntry(zipPath: String, password: String): List<ZArchiveEntry> {
        val list = mutableListOf<ZArchiveEntry>()
        // 使用自定义的 Handle 管理生命周期，确保流在读取完毕后才关闭
        openArchiveHandle(zipPath, PasswordOpenCallback(password.ifBlank { null }))?.use { handle ->
            val inArchive = handle.archive
            for (i in 0 until inArchive.numberOfItems) {
                // 安全获取 Size，防止空指针崩溃
                val sizeStr = inArchive.getStringProperty(i, PropID.SIZE)
                val size = sizeStr?.toLongOrNull() ?: 0L

                val rawPath = inArchive.getStringProperty(i, PropID.PATH)
                list.add(
                    ZArchiveEntry(
                        name = TextFixer.fixGarbledFilename(rawPath),
                        isDirectory = inArchive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false,
                        size = size,
                        index = i,
                        lastModified = readLastModified(inArchive, i),
                        encrypted = readEncryptedProperty(inArchive, i) ?: false,
                        rawName = rawNameHex(rawPath)
                    )
                )
            }
        }
        return list
    }

    fun checkPassword(zPath: String): Boolean {
        val callback = PasswordOpenCallback(null)
        var openError: Exception? = null
        val handle = openArchiveHandle(zPath, callback) { openError = it }
        if (handle == null) {
            // 头加密包会在打开阶段索要密码；文件损坏/非压缩包则不会触发密码回调
            return callback.passwordRequested || (openError != null && isPasswordError(openError!!))
        }
        return try {
            handle.use { opened ->
                // 主路径：读取 ENCRYPTED 属性（zip/7z/rar 在 7-Zip 中均支持）
                detectEncryptedByProperty(opened.archive) ?: probeEncryptedByExtract(opened.archive)
            }
        } catch (e: Exception) {
            Log.d(this, "checkPassword failed", e)
            isPasswordError(e)
        }
    }

    /**
     * 读取条目的加密属性。
     *
     * @return true/false 表示属性可读；null 表示当前条目或格式不支持该属性
     */
    private fun readEncryptedProperty(archive: IInArchive, index: Int): Boolean? {
        return try {
            when (val value = archive.getProperty(index, PropID.ENCRYPTED)) {
                is Boolean -> value
                is String -> value == "+" || value.equals("true", ignoreCase = true)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 通过 ENCRYPTED 属性判断是否加密。
     *
     * @return true=存在加密条目；false=属性可读且所有条目均未加密；null=属性不可读，需兜底
     */
    private fun detectEncryptedByProperty(archive: IInArchive): Boolean? {
        var propertyReadable = true
        for (i in 0 until archive.numberOfItems) {
            when (val encrypted = readEncryptedProperty(archive, i)) {
                true -> return true
                false -> Unit
                null -> propertyReadable = false
            }
        }
        return if (propertyReadable) false else null
    }

    /**
     * 旧的探测方式：试解压第一个非目录条目并检查 WRONG_PASSWORD。
     * 仅在 ENCRYPTED 属性不可读时作为兜底使用。
     */
    private fun probeEncryptedByExtract(archive: IInArchive): Boolean {
        var isEncrypted = false
        val fileIndex = (0 until archive.numberOfItems).firstOrNull {
            (archive.getProperty(it, PropID.IS_FOLDER) as? Boolean) == false
        } ?: return false

        try {
            val callback = object : IArchiveExtractCallback {
                override fun getStream(index: Int, extractAskMode: ExtractAskMode?): ISequentialOutStream {
                    return ISequentialOutStream { data -> data?.size ?: 0 }
                }

                override fun prepareOperation(extractAskMode: ExtractAskMode?) {}
                override fun setOperationResult(extractOperationResult: ExtractOperationResult?) {
                    if (extractOperationResult == ExtractOperationResult.WRONG_PASSWORD) {
                        isEncrypted = true
                    }
                }
                override fun setTotal(total: Long) {}
                override fun setCompleted(completed: Long) {}
            }
            archive.extract(intArrayOf(fileIndex), false, callback)
        } catch (e: Exception) {
            if (isPasswordError(e)) {
                isEncrypted = true
            } else {
                // 其他异常（如 CRC 错误、文件损坏）不应视为需要密码
                Log.d(this, "Probe encrypted failed (corrupted?)", e)
                return false
            }
        }
        return isEncrypted
    }

    /** 读取最后修改时间（epoch 毫秒），属性不可用时返回 0 */
    private fun readLastModified(archive: IInArchive, index: Int): Long {
        return try {
            (archive.getProperty(index, PropID.LAST_MODIFICATION_TIME) as? Date)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun verifyPassword(zPath: String, password: String): ArchivePasswordVerifyResult {
        val lowerPath = zPath.lowercase()
        return when {
            lowerPath.endsWith(".zip") -> verifySevenZPassword(zPath, password)
            lowerPath.endsWith(".7z") -> verifySevenZPassword(zPath, password)
            lowerPath.endsWith(".rar") -> RarFile.verifyPassword(zPath, password)
            else -> ArchivePasswordVerifyResult.ERROR
        }
    }

    fun openZEntryResult(zPath: String, password: String, entryName: String): ArchiveEntryOpenResult {
        val lowerPath = zPath.lowercase()
        return when {
            lowerPath.endsWith(".zip") -> openSevenZEntryResult(zPath, password, entryName)
            lowerPath.endsWith(".7z") -> openSevenZEntryResult(zPath, password, entryName)
            lowerPath.endsWith(".rar") -> RarFile.openZEntryResult(zPath, password, entryName)
            else -> ArchiveEntryOpenResult.Error
        }
    }

    /**
     * 带条目 key 的回退打开（新增重载，旧三参调用行为不变）：
     * raw 原始字节 → 名称精确 → 名称忽略大小写/空白 → 索引（校验后） → 旧 title。
     * 用于兼容旧持久化数据：旧记录可能只有 title，或因 TextFixer 版本变化导致名称失配。
     */
    fun openZEntryResult(
        zPath: String,
        password: String,
        entryName: String,
        entryRaw: String,
        entryIndex: Int,
        legacyTitle: String
    ): ArchiveEntryOpenResult {
        val lowerPath = zPath.lowercase()
        return when {
            lowerPath.endsWith(".zip") -> openSevenZEntryResultByKey(
                zPath, password, entryName, entryRaw, entryIndex, legacyTitle
            )
            lowerPath.endsWith(".7z") -> openSevenZEntryResultByKey(
                zPath, password, entryName, entryRaw, entryIndex, legacyTitle
            )
            lowerPath.endsWith(".rar") -> RarFile.openZEntryResult(
                zPath, password, entryName, entryRaw, entryIndex, legacyTitle
            )
            else -> ArchiveEntryOpenResult.Error
        }
    }

    fun openZEntry(zPath: String, password: String, entryName: String): InputStream? {
        return when (val result = openZEntryResult(zPath, password, entryName)) {
            is ArchiveEntryOpenResult.Success -> result.stream
            else -> null
        }
    }

    private fun verifySevenZPassword(
        zipPath: String,
        password: String
    ): ArchivePasswordVerifyResult {
        var openError: Exception? = null
        val openCallback = PasswordOpenCallback(password.ifBlank { null })
        val openedHandle = openArchiveHandle(zipPath, openCallback) { openError = it }
        if (openedHandle == null) {
            // 头加密包会索要密码；损坏文件不会触发密码回调，借此区分两类失败
            return if (openCallback.passwordRequested ||
                (openError != null && isPasswordError(openError!!))
            ) {
                ArchivePasswordVerifyResult.WRONG_PASSWORD
            } else {
                ArchivePasswordVerifyResult.ERROR
            }
        }

        return openedHandle.use { handle ->
            val inArchive = handle.archive
            val fileIndex = (0 until inArchive.numberOfItems).firstOrNull {
                (inArchive.getProperty(it, PropID.IS_FOLDER) as? Boolean) == false
            } ?: return@use ArchivePasswordVerifyResult.ENTRY_NOT_FOUND

            var status = ArchivePasswordVerifyResult.ERROR
            val callback = object : IArchiveExtractCallback, ICryptoGetTextPassword {
                // 提取阶段 7-Zip 可能再次索要密码（如 AES 加密的 zip）
                override fun cryptoGetTextPassword(): String? = password

                override fun getStream(index: Int, extractAskMode: ExtractAskMode?) =
                    ISequentialOutStream { data -> data?.size ?: 0 }

                override fun prepareOperation(extractAskMode: ExtractAskMode?) {}

                override fun setOperationResult(extractOperationResult: ExtractOperationResult?) {
                    status = when (extractOperationResult) {
                        ExtractOperationResult.OK -> ArchivePasswordVerifyResult.VALID
                        ExtractOperationResult.WRONG_PASSWORD -> ArchivePasswordVerifyResult.WRONG_PASSWORD
                        ExtractOperationResult.UNSUPPORTEDMETHOD -> ArchivePasswordVerifyResult.UNSUPPORTED_METHOD
                        else -> ArchivePasswordVerifyResult.ERROR
                    }
                }

                override fun setTotal(total: Long) {}
                override fun setCompleted(completed: Long) {}
            }

            try {
                inArchive.extract(intArrayOf(fileIndex), false, callback)
            } catch (e: Exception) {
                return@use if (isPasswordError(e)) {
                    ArchivePasswordVerifyResult.WRONG_PASSWORD
                } else {
                    Log.d(this, "verify password failed", e)
                    ArchivePasswordVerifyResult.ERROR
                }
            }

            status
        }
    }

    private fun openSevenZEntryResult(
        zipPath: String,
        password: String,
        entryName: String
    ): ArchiveEntryOpenResult {
        return openSevenZEntryResultInternal(zipPath, password) { inArchive ->
            findEntryIndexForName(inArchive, entryName)
        }
    }

    private fun openSevenZEntryResultByKey(
        zipPath: String,
        password: String,
        entryName: String,
        entryRaw: String,
        entryIndex: Int,
        legacyTitle: String
    ): ArchiveEntryOpenResult {
        return openSevenZEntryResultInternal(zipPath, password) { inArchive ->
            findEntryIndex(inArchive, entryName, entryRaw, entryIndex, legacyTitle)
        }
    }

    /** 旧三参路径的名称匹配：与改造前一致（只精确匹配 TextFixer 修复后的名称） */
    private fun findEntryIndexForName(archive: IInArchive, entryName: String): Int {
        for (i in 0 until archive.numberOfItems) {
            val path = TextFixer.fixGarbledFilename(archive.getStringProperty(i, PropID.PATH))
            if (path == entryName) {
                return i
            }
        }
        return -1
    }

    /**
     * zip/7z/rar 共用的条目回退匹配：raw → 名称精确 → 名称忽略大小写/空白 → 索引（校验后） → 旧 title。
     * 找不到返回 -1。
     */
    internal fun findEntryIndex(
        archive: IInArchive,
        entryName: String,
        entryRaw: String,
        entryIndex: Int,
        legacyTitle: String
    ): Int {
        val count = archive.numberOfItems
        fun fixedName(i: Int): String =
            TextFixer.fixGarbledFilename(archive.getStringProperty(i, PropID.PATH))

        // 1. 原始字节 key：跨 TextFixer 版本最稳定
        if (entryRaw.isNotBlank()) {
            for (i in 0 until count) {
                if (rawNameHex(archive.getStringProperty(i, PropID.PATH)).equals(entryRaw, true)) {
                    return i
                }
            }
        }
        // 2. 名称精确匹配
        if (entryName.isNotBlank()) {
            for (i in 0 until count) {
                if (fixedName(i) == entryName) {
                    return i
                }
            }
        }
        // 3. 名称忽略大小写/空白
        val normalizedName = normalizeEntryName(entryName)
        if (normalizedName.isNotEmpty()) {
            for (i in 0 until count) {
                if (normalizeEntryName(fixedName(i)) == normalizedName) {
                    return i
                }
            }
        }
        // 4. 索引：校验范围与目录属性，避免拿到目录或越界索引
        if (entryIndex in 0 until count &&
            !(archive.getProperty(entryIndex, PropID.IS_FOLDER) as? Boolean ?: false)
        ) {
            return entryIndex
        }
        // 5. 旧 title（旧数据 title 即条目名）
        if (legacyTitle.isNotBlank() && legacyTitle != entryName) {
            for (i in 0 until count) {
                if (fixedName(i) == legacyTitle) {
                    return i
                }
            }
            val normalizedTitle = normalizeEntryName(legacyTitle)
            if (normalizedTitle.isNotEmpty()) {
                for (i in 0 until count) {
                    if (normalizeEntryName(fixedName(i)) == normalizedTitle) {
                        return i
                    }
                }
            }
        }
        return -1
    }

    /** 名称归一化：统一分隔符、忽略大小写与空白，用于宽松回退匹配 */
    internal fun normalizeEntryName(value: String): String {
        return value.trim().replace('\\', '/').lowercase().filterNot { it.isWhitespace() }
    }

    /** 原始路径名转十六进制 key；ISO-8859-1 是 7-Zip String 的无损字节视图 */
    internal fun rawNameHex(rawPath: String?): String {
        if (rawPath.isNullOrEmpty()) return ""
        val bytes = rawPath.toByteArray(Charsets.ISO_8859_1)
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX_CHARS[value ushr 4]).append(HEX_CHARS[value and 0x0F])
        }
        return builder.toString()
    }

    private fun openSevenZEntryResultInternal(
        zipPath: String,
        password: String,
        findIndex: (IInArchive) -> Int
    ): ArchiveEntryOpenResult {
        Log.d(this, "open", zipPath)

        var openError: Exception? = null
        val openCallback = PasswordOpenCallback(password.ifBlank { null })
        val openedHandle = openArchiveHandle(zipPath, openCallback) { openError = it }
        if (openedHandle == null) {
            // 头加密包会索要密码；损坏文件不会触发密码回调，借此区分两类失败
            return if (openCallback.passwordRequested ||
                (openError != null && isPasswordError(openError!!))
            ) {
                ArchiveEntryOpenResult.WrongPassword
            } else {
                ArchiveEntryOpenResult.Error
            }
        }

        return openedHandle.use { handle ->
            val inArchive = handle.archive
            val targetIndex = findIndex(inArchive)

            if (targetIndex == -1) {
                Log.d(this, "Entry not found")
                return@use ArchiveEntryOpenResult.EntryNotFound
            }

            // 警告：对于大文件，ByteArrayOutputStream 会消耗大量内存
            // 生产环境建议改为解压到临时文件，返回 FileInputStream
            val output = ByteArrayOutputStream()
            var operationResult: ExtractOperationResult? = null

            val callback = object : IArchiveExtractCallback, ICryptoGetTextPassword {
                // 提取阶段 7-Zip 可能再次索要密码（如 AES 加密的 zip）
                override fun cryptoGetTextPassword(): String? = password

                override fun getStream(index: Int, extractAskMode: ExtractAskMode?): ISequentialOutStream {
                    return ISequentialOutStream { data ->
                        data?.let {
                            output.write(it)
                        }
                        data?.size ?: 0
                    }
                }

                override fun prepareOperation(extractAskMode: ExtractAskMode?) {}
                override fun setOperationResult(extractOperationResult: ExtractOperationResult?) {
                    operationResult = extractOperationResult
                    if (extractOperationResult == ExtractOperationResult.OK) {
                        Log.d(this, "Extract success, size: ${output.size()}")
                    } else if (extractOperationResult == ExtractOperationResult.WRONG_PASSWORD) {
                        Log.d(this, "Wrong password")
                    } else {
                        Log.d(this, "Extract failed: ${extractOperationResult?.name}")
                    }
                }

                override fun setTotal(total: Long) {}
                override fun setCompleted(complete: Long) {}
            }

            try {
                inArchive.extract(intArrayOf(targetIndex), false, callback)
            } catch (e: Exception) {
                Log.d(this, "Extract exception", e)
                return@use if (isPasswordError(e)) {
                    ArchiveEntryOpenResult.WrongPassword
                } else {
                    ArchiveEntryOpenResult.Error
                }
            }

            when (operationResult) {
                ExtractOperationResult.OK -> {
                    if (output.size() > 0) {
                        ArchiveEntryOpenResult.Success(ByteArrayInputStream(output.toByteArray()))
                    } else {
                        ArchiveEntryOpenResult.Error
                    }
                }

                ExtractOperationResult.WRONG_PASSWORD -> ArchiveEntryOpenResult.WrongPassword
                ExtractOperationResult.UNSUPPORTEDMETHOD -> ArchiveEntryOpenResult.UnsupportedMethod
                else -> ArchiveEntryOpenResult.Error
            }
        }
    }

}
