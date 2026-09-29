/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.expanded

import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import timber.log.Timber

class CandidatesPagingSource(
    val fcitx: FcitxConnection,
    val total: Int,
    val offset: Int,
    /**
     * 【fainput / 隐私】密码框：把候选文字遮成「·」。
     *
     * 为什么这里要**单独**做一次：本类**绕过了** `InputView` 那个统一收口 ——
     * 它用 `getCandidates(offset, limit)` 直接从引擎拉全量列表（见 `CandidateReranker` 的注释）。
     * 所以遮罩必须在数据源头再做一次，否则展开窗会把明文候选全列出来。
     *
     * 安全性同 `InputView`：选候选走的是**下标**，遮显示不影响上屏。
     */
    val mask: Boolean = false,
) : PagingSource<Int, CandidateWord>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CandidateWord> {
        // use candidate index for key, null means load from beginning (with offset)
        val startIndex = params.key ?: offset
        val pageSize = params.loadSize
        Timber.d("getCandidates(offset=$startIndex, limit=$pageSize)")
        val candidates = fcitx.runOnReady {
            getCandidates(startIndex, pageSize)
        }
        val prevKey = if (startIndex >= pageSize) startIndex - pageSize else null
        val nextKey = if (total > 0) {
            if (startIndex + pageSize + 1 >= total) null else startIndex + pageSize
        } else {
            if (candidates.size < pageSize) null else startIndex + pageSize
        }
        return LoadResult.Page(
            if (!mask) candidates.toList()
            else candidates.map { it.copy(text = "·", comment = "") }.toList(),
            prevKey,
            nextKey
        )
    }

    // always reload from beginning
    override fun getRefreshKey(state: PagingState<Int, CandidateWord>) = null

}
