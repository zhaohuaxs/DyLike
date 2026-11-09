package me.lingci.lib.dm.view.util

import me.lingci.lib.archive.ArchivePasswordVerifyResult
import me.lingci.lib.archive.ArchiveEntryOpenResult
import me.lingci.lib.archive.ZArchiveFile
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.util.Comparators
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.md5
import java.io.File
import java.io.InputStream

object ZipXmlLoader {

    const val ZIP = "zip"
    const val SEVEN_Z: String = "7z"
    const val RAR: String = "rar"
    const val ZIP_EXTENSION: String = ".zip"
    const val SEVEN_Z_EXTENSION: String = ".7z"
    const val RAR_EXTENSION: String = ".rar"

    /** 压缩包条目（附带稳定的条目 key，供 DmTrack 持久化） */
    data class XmlEntry(
        val file: FileEntity,
        /** 原始路径名的十六进制，跨 TextFixer 版本稳定 */
        val rawName: String = "",
        /** 条目在压缩包中的索引，-1 表示未知 */
        val index: Int = -1
    )

    /** 按路径扩展名返回压缩包 mineType（小写）；非压缩包返回空串 */
    fun archiveMimeType(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "zip" -> ZIP
            "7z" -> SEVEN_Z
            "rar" -> RAR
            else -> ""
        }
    }

    enum class PasswordVerifyResult {
        VALID,
        WRONG_PASSWORD,
        UNSUPPORTED_METHOD,
        ENTRY_NOT_FOUND,
        ERROR
    }

    enum class OpenResultState {
        SUCCESS,
        WRONG_PASSWORD,
        UNSUPPORTED_METHOD,
        ENTRY_NOT_FOUND,
        ERROR
    }

    data class OpenResult(
        val state: OpenResultState,
        val stream: InputStream? = null
    )

    fun listXmlEntries(file: File, password: String = ""): List<FileEntity> {
        return listXmlEntriesDetailed(file, password).map { it.file }
    }

    /**
     * 列出压缩包内的 XML 条目，并附带原始名/索引。
     * 供选择面板把“打开的是包内哪个条目”写入 DmTrack，避免重启后只能靠 title 猜测。
     */
    fun listXmlEntriesDetailed(file: File, password: String = ""): List<XmlEntry> {
        Log.d(this, file.name)
        return try {
            ZArchiveFile.listZEntry(file.path, password)
                .filter { !it.isDirectory }
                .filter { isXml(it.name) }
                .map {
                    Log.d(this, it.name)
                    val mimeType = archiveMimeType(file.path).ifBlank { ZIP }
                    XmlEntry(
                        file = FileEntity(
                            mimeType = mimeType,
                            isFile = true,
                            title = it.name,
                            name = it.name,
                            size = it.size,
                            path = file.path,
                            // id 加入所在目录（file.path），避免不同目录下同名压缩包的条目碰撞
                            id = "${file.path}/${it.name}".md5()
                        ),
                        rawName = it.rawName,
                        index = it.index
                    )
                }
                .sortedWith { first, second ->
                    Comparators.fileEntityNameComparator().compare(first.file, second.file)
                }
                .toList()
        } catch (e: Exception) {
            Log.d("list zip failed", e)
            emptyList()
        }
    }

    private fun isXml(name: String): Boolean {
        val ext = name.substringAfterLast(".", "").lowercase()
        return ext in setOf("xml")
    }

    fun loadXmlFromZip(fileEntity: FileEntity, password: String = ""): InputStream? {
        try {
            return ZArchiveFile.openZEntry(fileEntity.path, password, fileEntity.name)
        } catch (e: Exception) {
            Log.d("load zip xml failed", e)
            return null
        }
    }

    fun loadXmlFromZipResult(fileEntity: FileEntity, password: String = ""): OpenResult {
        return try {
            when (val result = ZArchiveFile.openZEntryResult(fileEntity.path, password, fileEntity.name)) {
                is ArchiveEntryOpenResult.Success -> OpenResult(OpenResultState.SUCCESS, result.stream)
                ArchiveEntryOpenResult.WrongPassword -> OpenResult(OpenResultState.WRONG_PASSWORD)
                ArchiveEntryOpenResult.UnsupportedMethod -> OpenResult(OpenResultState.UNSUPPORTED_METHOD)
                ArchiveEntryOpenResult.EntryNotFound -> OpenResult(OpenResultState.ENTRY_NOT_FOUND)
                ArchiveEntryOpenResult.Error -> OpenResult(OpenResultState.ERROR)
            }
        } catch (e: Exception) {
            Log.d("load zip xml result failed", e)
            OpenResult(OpenResultState.ERROR)
        }
    }

    /**
     * 带条目 key 的回退打开：raw → 名称精确 → 名称忽略大小写/空白 → 索引（校验后） → 旧 title。
     * 旧三参重载保持不变；此重载用于兼容旧持久化数据。
     */
    fun loadXmlFromZipResult(
        fileEntity: FileEntity,
        password: String,
        entryRaw: String,
        entryIndex: Int,
        legacyTitle: String
    ): OpenResult {
        return try {
            when (val result = ZArchiveFile.openZEntryResult(
                fileEntity.path,
                password,
                fileEntity.name,
                entryRaw,
                entryIndex,
                legacyTitle
            )) {
                is ArchiveEntryOpenResult.Success -> OpenResult(OpenResultState.SUCCESS, result.stream)
                ArchiveEntryOpenResult.WrongPassword -> OpenResult(OpenResultState.WRONG_PASSWORD)
                ArchiveEntryOpenResult.UnsupportedMethod -> OpenResult(OpenResultState.UNSUPPORTED_METHOD)
                ArchiveEntryOpenResult.EntryNotFound -> OpenResult(OpenResultState.ENTRY_NOT_FOUND)
                ArchiveEntryOpenResult.Error -> OpenResult(OpenResultState.ERROR)
            }
        } catch (e: Exception) {
            Log.d("load zip xml result failed", e)
            OpenResult(OpenResultState.ERROR)
        }
    }

    fun checkPassword(file: File): Boolean {
        return ZArchiveFile.checkPassword(file.path)
    }

    fun verifyPassword(file: File, password: String): PasswordVerifyResult {
        return when (ZArchiveFile.verifyPassword(file.path, password)) {
            ArchivePasswordVerifyResult.VALID -> PasswordVerifyResult.VALID
            ArchivePasswordVerifyResult.WRONG_PASSWORD -> PasswordVerifyResult.WRONG_PASSWORD
            ArchivePasswordVerifyResult.UNSUPPORTED_METHOD -> PasswordVerifyResult.UNSUPPORTED_METHOD
            ArchivePasswordVerifyResult.ENTRY_NOT_FOUND -> PasswordVerifyResult.ENTRY_NOT_FOUND
            ArchivePasswordVerifyResult.ERROR -> PasswordVerifyResult.ERROR
        }
    }

}
