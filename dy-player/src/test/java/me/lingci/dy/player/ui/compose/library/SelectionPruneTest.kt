package me.lingci.dy.player.ui.compose.library

import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.ui.compose.model.MediaUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SelectionPruneTest {

    @Test
    fun invisibleIds_areDropped() {
        // 选中 A 后 A 已从当前页消失（别处删掉 / 换页），只剩 B 参与后续删除
        assertEquals(
            setOf("b"),
            pruneSelection(setOf("a", "b"), listOf("b", "c"))
        )
    }

    @Test
    fun allVisible_returnsSameInstance() {
        val ids = setOf("a", "b")
        // 没被剔除时要复用同一实例，否则每次数据刷新都会让 UiState 变新对象、整列重组
        assertSame(ids, pruneSelection(ids, listOf("c", "b", "a")))
    }

    @Test
    fun emptySelection_staysEmpty() {
        assertEquals(emptySet<String>(), pruneSelection(emptySet(), emptyList()))
    }

    @Test
    fun emptyVisible_dropsEverything() {
        assertEquals(emptySet<String>(), pruneSelection(setOf("a"), emptyList()))
    }

    @Test
    fun selectionState_prunesBackingIds() {
        val selection = SelectionState(active = true, ids = setOf("media:a", "media:b"))
        val visible = listOf(
            MediaUiModel(id = "b", title = "B", type = MediaLibType.LOCAL)
        )

        assertEquals(SelectionState(active = true, ids = setOf("media:b")), selection.prunedTo(visible))
    }

    @Test
    fun loading_doesNotPruneSelection() {
        val selection = SelectionState(active = true, ids = setOf("media:a"))

        assertEquals(
            selection,
            selection.prunedTo(emptyList(), LibraryPhase.Loading, deleting = false)
        )
    }

    @Test
    fun deleting_doesNotPruneSelection() {
        val selection = SelectionState(active = true, ids = setOf("media:a"))

        assertEquals(
            selection,
            selection.prunedTo(emptyList(), LibraryPhase.Content, deleting = true)
        )
    }

    @Test
    fun afterDelete_keepsFailedAndNewSelections() {
        val selection = SelectionState(
            active = true,
            ids = setOf("media:a", "media:b", "media:c")
        )

        assertEquals(
            SelectionState(active = true, ids = setOf("media:a", "media:c")),
            selection.afterDelete(
                attempted = setOf("media:a", "media:b"),
                failed = setOf("media:a")
            )
        )
    }

    @Test
    fun afterDelete_exitsWhenAllSelectedCardsAreGone() {
        val selection = SelectionState(active = true, ids = setOf("media:a"))

        assertEquals(
            SelectionState(),
            selection.afterDelete(attempted = setOf("media:a"), failed = emptySet())
        )
    }

    @Test
    fun afterDelete_doesNotReactivateClearedSelection() {
        val cleared = SelectionState()

        assertEquals(
            cleared,
            cleared.afterDelete(attempted = setOf("media:a"), failed = setOf("media:a"))
        )
    }
}
