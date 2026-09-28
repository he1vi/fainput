/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import timber.log.Timber

object ClipboardManager : ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    private lateinit var clbDb: ClipboardDatabase
    private lateinit var clbDao: ClipboardDao

    fun interface OnClipboardUpdateListener {
        fun onUpdate(entry: ClipboardEntry)
    }

    private val clipboardManager = appContext.clipboardManager

    private val mutex = Mutex()

    var itemCount: Int = 0
        private set

    private suspend fun updateItemCount() {
        itemCount = clbDao.itemCount()
    }

    private val onUpdateListeners = WeakHashSet<OnClipboardUpdateListener>()

    var transformer: ((String) -> String)? = null

    fun addOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.add(listener)
    }

    fun removeOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.remove(listener)
    }

    private val enabledPref = AppPrefs.getInstance().clipboard.clipboardListening

    @Keep
    private val enabledListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        if (value) {
            clipboardManager.addPrimaryClipChangedListener(this)
        } else {
            clipboardManager.removePrimaryClipChangedListener(this)
        }
    }

    private val limitPref = AppPrefs.getInstance().clipboard.clipboardHistoryLimit

    @Keep
    private val limitListener = ManagedPreference.OnChangeListener<Int> { _, _ ->
        launch { removeOutdated() }
    }

    var lastEntry: ClipboardEntry? = null

    private fun updateLastEntry(entry: ClipboardEntry) {
        lastEntry = entry
        onUpdateListeners.forEach { it.onUpdate(entry) }
    }

    fun init(context: Context) {
        clbDb = Room
            .databaseBuilder(context, ClipboardDatabase::class.java, "clbdb")
            // allow wipe the database instead of crashing when downgrade
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
        clbDao = clbDb.clipboardDao()
        enabledListener.onChange(enabledPref.key, enabledPref.getValue())
        enabledPref.registerOnChangeListener(enabledListener)
        limitListener.onChange(limitPref.key, limitPref.getValue())
        limitPref.registerOnChangeListener(limitListener)
        launch { updateItemCount() }
    }

    suspend fun get(id: Int) = clbDao.get(id)

    suspend fun haveUnpinned() = clbDao.haveUnpinned()

    fun allEntries() = clbDao.allEntries()

    // ==================== 【fainput 新功能】时间窗 + 懒加载 ====================

    /**
     * 面板的时间窗：**只显示最近 2 小时**（+ 置顶的）。
     *
     * 条目**一条都不删** —— 更早的仍在库里，设置里的历史能看、能搜。
     * 这样面板永远是"刚刚拿的东西"，不会被几百条旧记录淹掉。
     */
    const val PANEL_WINDOW_MS = 2 * 3600_000L

    /**
     * 面板用：只取「**预览 + 长度**」，不取全文。
     *
     * 这是"复制一本小说也不卡"的关键 —— 一页 16 条也只搬 16×200 字。
     */
    fun rows(now: Long = System.currentTimeMillis()) = clbDao.rowsSince(now - PANEL_WINDOW_MS)

    /** 全文长度（字符数）—— 不读内容。 */
    suspend fun textLength(id: Int) = clbDao.textLength(id)

    /**
     * 分块读全文：从 [offset] 起取 [len] 个字。
     * 超长文本的**查看 / 编辑**都走这条路，永远不整篇进内存。
     */
    suspend fun textChunk(id: Int, offset: Int, len: Int) = clbDao.textChunk(id, offset, len)

    /** 跨条目搜索：匹配在 SQL 侧做（`instr`），只回片段。 */
    suspend fun search(q: String, limit: Int = 50) = clbDao.searchRows(q, limit)

    /**
     * 历史页用：**全部**条目的一页（无时间窗）。
     *
     * 面板给的是"最近 2 小时"，这里给的是"**所有**" —— 但同样只搬预览。
     */
    suspend fun rowsPage(limit: Int, offset: Int) = clbDao.rowsAll(limit, offset)

    /**
     * 按 id 取**全文** —— 只在真的要**粘贴 / 分享 / 写回**时才调一次。
     * **列表路径永远不该调这个。**
     */
    suspend fun textOf(id: Int): String? = clbDao.get(id)?.text

    suspend fun pin(id: Int) = clbDao.updatePinStatus(id, true)

    suspend fun unpin(id: Int) = clbDao.updatePinStatus(id, false)

    suspend fun updateText(id: Int, text: String) {
        lastEntry?.let {
            if (id == it.id) updateLastEntry(it.copy(text = text))
        }
        clbDao.updateText(id, text)
    }

    suspend fun delete(id: Int) {
        clbDao.markAsDeleted(id)
        updateItemCount()
    }

    suspend fun deleteAll(skipPinned: Boolean = true): IntArray {
        val ids = if (skipPinned) {
            clbDao.findUnpinnedIds()
        } else {
            clbDao.findAllIds()
        }
        clbDao.markAsDeleted(*ids)
        updateItemCount()
        return ids
    }

    suspend fun undoDelete(vararg ids: Int) {
        clbDao.undoDelete(*ids)
        updateItemCount()
    }

    suspend fun realDelete() {
        clbDao.realDelete()
    }

    suspend fun nukeTable() {
        withContext(coroutineContext) {
            clbDb.clearAllTables()
            updateItemCount()
        }
    }

    private var lastClipTimestamp = -1L
    private var lastClipHash = 0

    override fun onPrimaryClipChanged() {
        val clip = clipboardManager.primaryClip ?: return
        /**
         * skip duplicate ClipData
         * https://developer.android.com/reference/android/content/ClipboardManager.OnPrimaryClipChangedListener#onPrimaryClipChanged()
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timestamp = clip.description.timestamp
            if (timestamp == lastClipTimestamp) return
            lastClipTimestamp = timestamp
        } else {
            val timestamp = System.currentTimeMillis()
            val hash = clip.hashCode()
            if (timestamp - lastClipTimestamp < 100L && hash == lastClipHash) return
            lastClipTimestamp = timestamp
            lastClipHash = hash
        }
        launch {
            mutex.withLock {
                val entry = ClipboardEntry.fromClipData(clip, transformer) ?: return@withLock
                if (entry.text.isBlank()) return@withLock
                try {
                    clbDao.find(entry.text, entry.sensitive)?.let {
                        updateLastEntry(it.copy(timestamp = entry.timestamp))
                        clbDao.updateTime(it.id, entry.timestamp)
                        return@withLock
                    }
                    val insertedEntry = clbDb.withTransaction {
                        val rowId = clbDao.insert(entry)
                        removeOutdated()
                        // new entry can be deleted immediately if clipboard limit == 0
                        clbDao.get(rowId) ?: entry
                    }
                    updateLastEntry(insertedEntry)
                    updateItemCount()
                } catch (exception: Exception) {
                    Timber.w("Failed to update clipboard database: $exception")
                    updateLastEntry(entry)
                }
            }
        }
    }

    /**
     * 历史文本总量的**安全阀**（字符数）。
     *
     * 20,000,000 字 ≈ 40MB —— 正常打字几辈子也到不了；
     * 但"反复复制整本小说"会到。留着它，是为了让"永久保存"不至于
     * 变成"永久涨磁盘"。
     */
    private const val HISTORY_CHAR_BUDGET = 20_000_000L

    /**
     * 裁剪历史。
     *
     * ## 【fainput 用户要求】历史**永久保存**
     *
     * 上游规则是「超过 N 条就删旧的」，而 N 默认只有 **10** ——
     * 用户看到的现象就是"历史会消失"。
     *
     * 现在改成**两道安全阀，正常使用都不触发**：
     *
     * | 闸 | 阈值 | 什么时候才管得着 |
     * |---|---|---|
     * | 条数 | `clipboardHistoryLimit`（默认已 10 → **500**）| 攒够 500 条以上 |
     * | **总字符数** | [HISTORY_CHAR_BUDGET] | 有人反复复制整本小说 |
     *
     * **置顶的条目两闸都不管**（置顶 = 明确说"别删它"）。
     *
     * ⚠️ 这里是**软删除**（`markAsDeleted`）—— 真正的 DELETE 只在
     *    「清空全部」→ `realDelete()` 时才发生。所以即使触发了安全阀，
     *    也还有反悔的余地。
     */
    private suspend fun removeOutdated() {
        val limit = limitPref.getValue()
        val unpinned = clbDao.getAllUnpinned()
        val tooMany = unpinned.size > limit
        val tooBig = clbDao.totalTextChars() > HISTORY_CHAR_BUDGET
        if (!tooMany && !tooBig) return
        // 保留最近 limit 条，比它更早的全部软删
        val last = unpinned
            .sortedBy { it.id }
            .getOrNull(unpinned.size - limit)
        clbDao.markUnpinnedAsDeletedEarlierThan(last?.timestamp ?: System.currentTimeMillis())
    }

}