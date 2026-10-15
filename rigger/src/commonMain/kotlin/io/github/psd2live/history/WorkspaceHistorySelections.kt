package io.github.psd2live.history

/** Immutable append-only pages: capturing a history shares its pages, and an append copies at most one tail. */
class WorkspaceHistorySelections<T> private constructor(
    private val pages: List<List<WorkspaceHistorySelection<T>>>,
    private val tail: List<WorkspaceHistorySelection<T>>,
) : AbstractList<WorkspaceHistorySelection<T>>() {
    override val size: Int = pages.size * PAGE + tail.size
    override fun get(index: Int): WorkspaceHistorySelection<T> {
        if (index !in indices) throw IndexOutOfBoundsException("History index out of bounds: $index")
        return if (index < pages.size * PAGE) pages[index / PAGE][index % PAGE] else tail[index - pages.size * PAGE]
    }
    fun append(selection: WorkspaceHistorySelection<T>) = if (tail.size == PAGE)
        WorkspaceHistorySelections(pages + listOf(tail), listOf(selection))
        else WorkspaceHistorySelections(pages, tail + selection)

    companion object {
        private const val PAGE = 64
        fun <T> empty(): WorkspaceHistorySelections<T> = WorkspaceHistorySelections(emptyList(), emptyList())
    }
}
