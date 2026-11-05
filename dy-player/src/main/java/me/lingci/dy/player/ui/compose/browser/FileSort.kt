package me.lingci.dy.player.ui.compose.browser

import androidx.annotation.StringRes
import me.lingci.dy.player.R
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.util.Comparators
import java.util.Locale

/**
 * 浏览页排序字段。
 *
 * 名字排序复用 [Comparators.stringComparator]（自然序），与旧版
 * `Comparators.fileEntityFolderComparator` 的目录内顺序一致，
 * 所以默认的「名称 / 升序」看到的顺序与改造前完全相同。
 */
enum class FileSortKey(@StringRes val labelRes: Int) {
    Name(R.string.sort_field_name),
    Modified(R.string.sort_field_modified),
    Size(R.string.sort_field_size),
    Kind(R.string.sort_field_kind)
}

private fun FileEntity.kindKey(): String =
    if (isFile) name.substringAfterLast('.', "").lowercase(Locale.ROOT) else ""

/**
 * 目录恒在前，其余按字段排序；升降序只作用于同类项（与文件管理器惯例一致）。
 *
 * 纯函数，不碰 IO：调用方用 `remember(files, key, ascending)` 包一层即可。
 */
fun sortFiles(files: List<FileEntity>, key: FileSortKey, ascending: Boolean): List<FileEntity> {
    val nameComparator = Comparators.stringComparator()
    val comparator: Comparator<FileEntity> = when (key) {
        FileSortKey.Name -> Comparator { a, b -> nameComparator.compare(a.name, b.name) }
        FileSortKey.Modified -> compareBy { it.lastModified }
        FileSortKey.Size -> compareBy { it.size }
        FileSortKey.Kind -> Comparator { a, b ->
            val byKind = nameComparator.compare(a.kindKey(), b.kindKey())
            if (byKind != 0) byKind else nameComparator.compare(a.name, b.name)
        }
    }
    val ordered = if (ascending) comparator else comparator.reversed()
    return files.sortedWith(compareByDescending<FileEntity> { !it.isFile }.then(ordered))
}
