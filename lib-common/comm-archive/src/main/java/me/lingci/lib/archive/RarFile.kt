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
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Date

/**
 * 最终优化版
 * 解决 RAR 无法随机读取第二个文件的问题
 * 原理：利用 Extract 回调机制，按解压器的顺序“拦截”数据流，而不是强制寻址。
 */
object RarFile {

    private fun isPasswordError(error: Throwable): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return message.contains("password") || message.contains("encrypted")
    }

    /**
     * 打开阶段的密码回调：记录 7-Zip 是否索要过密码。
     * 头加密包会触发该回调；文件损坏/非压缩包不会，借此区分两类打开失败。
     */
    private class OpenCallback(private val password: String?) :
        IArchiveOpenCallback, ICryptoGetTextPassword {

        var passwordRequested = false
            private set

        override fun setTotal(files: Long?, bytes: Long?) {}
        override fun setCompleted(files: Long?, bytes: Long?) {}

        override fun cryptoGetTextPassword(): String? {
            passwordRequested = true
            return password
        }
    }

    /**
     * 一次性打开 Archive，自动管理资源生命周期
     */
    private inline fun <T> withArchive(
        path: String,
        password: String?,
        onOpenFailure: (Exception, Boolean) -> Unit = { _, _ -> },
        block: (IInArchive) -> T?
    ): T? {
        val openCallback = OpenCallback(password)
        return try {
            // 注意：这里不能在 block 执行完就 close，必须等 extract 结束
            // 但为了防止内存泄漏，这里使用 RAII 模式，block 内部必须同步执行完 extract
            val raf = RandomAccessFile(path, "r")
            val inStream = RandomAccessFileInStream(raf)
            val archive = SevenZip.openInArchive(null, inStream, openCallback)

            try {
                block(archive)
            } finally {
                // 确保 block 执行完（解压结束）后再关闭
                try { archive.close() } catch (e: Exception) { Log.d(this, "close archive error", e) }
                try { raf.close() } catch (e: Exception) { Log.d(this, "close raf error", e) }
            }
        } catch (e: Exception) {
            Log.d(this, "withArchive failed", e)
            // 回调打开失败的异常与是否索要过密码，供调用方区分「密码问题」与「文件损坏」
            onOpenFailure(e, openCallback.passwordRequested)
            null
        }
    }

    /** 读取最后修改时间（epoch 毫秒），属性不可用时返回 0 */
    private fun readLastModified(archive: IInArchive, index: Int): Long {
        return try {
            (archive.getProperty(index, PropID.LAST_MODIFICATION_TIME) as? Date)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** 读取条目加密标记，属性不可用时按未加密处理 */
    private fun readEncrypted(archive: IInArchive, index: Int): Boolean {
        return try {
            when (val value = archive.getProperty(index, PropID.ENCRYPTED)) {
                is Boolean -> value
                is String -> value == "+" || value.equals("true", ignoreCase = true)
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun listZEntry(zPath: String, password: String): List<ZArchiveEntry> {
        return withArchive(zPath, password.ifBlank { null }) { archive ->
            val list = mutableListOf<ZArchiveEntry>()
            for (i in 0 until archive.numberOfItems) {
                list.add(
                    ZArchiveEntry(
                        name = TextFixer.fixGarbledFilename(archive.getStringProperty(i, PropID.PATH)),
                        isDirectory = archive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false,
                        size = archive.getStringProperty(i, PropID.SIZE)?.toLongOrNull() ?: 0L,
                        index = i,
                        lastModified = readLastModified(archive, i),
                        encrypted = readEncrypted(archive, i)
                    )
                )
            }
            list
        } ?: emptyList()
    }

    fun checkPassword(zPath: String): Boolean {
        return withArchive(zPath, null) { archive ->
            var isEncrypted = false
            // 找第一个非文件夹文件进行测试
            val testIndex = (0 until archive.numberOfItems).find {
                archive.getProperty(it, PropID.IS_FOLDER) as? Boolean == false
            } ?: return@withArchive false

            val callback = object : IArchiveExtractCallback {
                override fun getStream(index: Int, extractAskMode: ExtractAskMode?) = ISequentialOutStream { it?.size ?: 0 }
                override fun setOperationResult(result: ExtractOperationResult?) {
                    if (result == ExtractOperationResult.WRONG_PASSWORD) isEncrypted = true
                }
                override fun prepareOperation(extractAskMode: ExtractAskMode?) {}
                override fun setTotal(total: Long) {}
                override fun setCompleted(completed: Long) {}
            }

            try {
                archive.extract(intArrayOf(testIndex), false, callback)
            } catch (e: Exception) {
                if (isPasswordError(e)) isEncrypted = true
            }
            isEncrypted
        } ?: false
    }

    fun verifyPassword(zPath: String, password: String): ArchivePasswordVerifyResult {
        var openError: Exception? = null
        var openRequested = false
        val result = withArchive(
            zPath,
            password.ifBlank { null },
            { error, requested -> openError = error; openRequested = requested }
        ) { archive ->
            val testIndex = (0 until archive.numberOfItems).find {
                archive.getProperty(it, PropID.IS_FOLDER) as? Boolean == false
            } ?: return@withArchive ArchivePasswordVerifyResult.ENTRY_NOT_FOUND

            var status = ArchivePasswordVerifyResult.ERROR
            val callback = object : IArchiveExtractCallback, ICryptoGetTextPassword {
                // 提取阶段 7-Zip 会再次索要密码（RAR 条目加密场景）
                override fun cryptoGetTextPassword(): String? = password

                override fun getStream(index: Int, extractAskMode: ExtractAskMode?) =
                    ISequentialOutStream { data -> data?.size ?: 0 }

                override fun setOperationResult(result: ExtractOperationResult?) {
                    status = when (result) {
                        ExtractOperationResult.OK -> ArchivePasswordVerifyResult.VALID
                        ExtractOperationResult.WRONG_PASSWORD -> ArchivePasswordVerifyResult.WRONG_PASSWORD
                        ExtractOperationResult.UNSUPPORTEDMETHOD -> ArchivePasswordVerifyResult.UNSUPPORTED_METHOD
                        else -> ArchivePasswordVerifyResult.ERROR
                    }
                }

                override fun prepareOperation(extractAskMode: ExtractAskMode?) {}
                override fun setTotal(total: Long) {}
                override fun setCompleted(completed: Long) {}
            }

            try {
                archive.extract(intArrayOf(testIndex), false, callback)
            } catch (e: Exception) {
                return@withArchive if (isPasswordError(e)) {
                    ArchivePasswordVerifyResult.WRONG_PASSWORD
                } else {
                    Log.d(this, "verify password failed", e)
                    ArchivePasswordVerifyResult.ERROR
                }
            }

            status
        }
        if (result == null) {
            // 打开阶段就失败：头加密包会在打开阶段索要密码；文件损坏/非压缩包不会
            return if (openRequested || (openError != null && isPasswordError(openError!!))) {
                ArchivePasswordVerifyResult.WRONG_PASSWORD
            } else {
                ArchivePasswordVerifyResult.ERROR
            }
        }
        return result
    }

    fun openZEntry(zPath: String, password: String, entryName: String): InputStream? {
        return when (val result = openZEntryResult(zPath, password, entryName)) {
            is ArchiveEntryOpenResult.Success -> result.stream
            else -> null
        }
    }

    fun openZEntryResult(zPath: String, password: String, entryName: String): ArchiveEntryOpenResult {
        var openError: Exception? = null
        var openRequested = false
        val entryResult = withArchive(
            zPath,
            password.ifBlank { null },
            { error, requested -> openError = error; openRequested = requested }
        ) { archive ->
            var result: ArchiveEntryOpenResult = ArchiveEntryOpenResult.Error

            // 1. 查找目标索引
            val targetIndex = (0 until archive.numberOfItems).find { i ->
                TextFixer.fixGarbledFilename(archive.getStringProperty(i, PropID.PATH)) == entryName
            }

            if (targetIndex == null) {
                Log.d(this, "Entry not found: $entryName")
                return@withArchive ArchiveEntryOpenResult.EntryNotFound
            }

            // 2. 关键：准备拦截数据的 ByteArrayOutputStream
            // 避免内存溢出建议：此处可替换为 FileOutputStream 写入临时文件
            val buffer = ByteArrayOutputStream()

            // 3. 定义回调：当解压器读到数据时，写入 buffer
            val callback = object : IArchiveExtractCallback, ICryptoGetTextPassword {
                // 提取阶段 7-Zip 会再次索要密码（RAR 条目加密场景）
                override fun cryptoGetTextPassword(): String? = password

                override fun getStream(index: Int, extractAskMode: ExtractAskMode?): ISequentialOutStream {
                    return ISequentialOutStream { data ->
                        if (index == targetIndex) {
                            data?.let { buffer.write(it) }
                        }
                        data?.size ?: 0
                    }
                }

                override fun setOperationResult(operationResult: ExtractOperationResult?) {
                    if (operationResult == ExtractOperationResult.OK && buffer.size() > 0) {
                        result = ArchiveEntryOpenResult.Success(ByteArrayInputStream(buffer.toByteArray()))
                    } else if (operationResult == ExtractOperationResult.WRONG_PASSWORD) {
                        Log.d(this, "Wrong password for $entryName")
                        result = ArchiveEntryOpenResult.WrongPassword
                    } else if (operationResult == ExtractOperationResult.UNSUPPORTEDMETHOD) {
                        Log.d(this, "Unsupported method for $entryName")
                        result = ArchiveEntryOpenResult.UnsupportedMethod
                    } else {
                        result = ArchiveEntryOpenResult.Error
                    }
                }

                override fun prepareOperation(extractAskMode: ExtractAskMode?) {}
                override fun setTotal(total: Long) {}
                override fun setCompleted(completed: Long) {}
            }

            // 4. 执行解压
            // extract 方法会处理 RAR 的复杂指针跳动，我们只需静静等待回调结果
            try {
                archive.extract(intArrayOf(targetIndex), false, callback)
            } catch (e: Exception) {
                Log.d(this, "Extract failed", e)
                result = if (isPasswordError(e)) {
                    ArchiveEntryOpenResult.WrongPassword
                } else {
                    ArchiveEntryOpenResult.Error
                }
            }

            result
        }
        if (entryResult == null) {
            // 打开阶段就失败：头加密包会在打开阶段索要密码；文件损坏/非压缩包不会
            return if (openRequested || (openError != null && isPasswordError(openError!!))) {
                ArchiveEntryOpenResult.WrongPassword
            } else {
                ArchiveEntryOpenResult.Error
            }
        }
        return entryResult
    }

}
