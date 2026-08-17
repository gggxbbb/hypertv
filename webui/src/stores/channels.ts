import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, type ChannelPatch } from '@/api/client'
import type { ChannelDTO } from '@/api/types'

/**
 * 频道列表状态：拉取全量（含隐藏）供管理，编辑即时生效并 500ms 防抖提交。
 * 编辑失败不阻断其它操作；所有写操作完成后立即拉取（多标签页同步）。
 */
export const useChannelsStore = defineStore('channels', () => {
  const channels = ref<ChannelDTO[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  /** 防抖保存的待提交编辑（本地优先，同一频道多次修改合并为一次请求） */
  const pending = ref<Record<string, ChannelPatch>>({})
  let saveTimer: number | null = null
  let reorderTimer: number | null = null

  async function refresh() {
    loading.value = true
    try {
      const list = await api.channels(true)
      const replay = pending.value
      channels.value = list.map((ch) => {
        const p = replay[ch.id]
        return p ? { ...ch, ...p } : ch
      })
      error.value = null
    } catch (e) {
      error.value = (e as Error).message
    } finally {
      loading.value = false
    }
  }

  function scheduleSave() {
    if (saveTimer !== null) window.clearTimeout(saveTimer)
    saveTimer = window.setTimeout(() => {
      saveTimer = null
      void flush()
    }, 500)
  }

  async function flush() {
    const ids = Object.keys(pending.value)
    if (ids.length === 0) return
    const snapshot = pending.value
    pending.value = {}
    for (const id of ids) {
      const patch = snapshot[id]
      if (!patch) continue
      // flush 期间又有新编辑时跳过本次覆盖，新值会在下一次 flush 提交
      if (pending.value[id]) continue
      try {
        const updated = await api.updateChannel(id, patch)
        const idx = channels.value.findIndex((c) => c.id === id)
        if (idx >= 0) {
          const copy = [...channels.value]
          copy[idx] = updated
          channels.value = copy
        }
      } catch (e) {
        error.value = `保存失败：${(e as Error).message}`
      }
    }
  }

  /** 编辑频道字段：本地即时生效 + 500ms 防抖提交（自动保存，无保存按钮） */
  function applyPatch(id: string, patch: ChannelPatch) {
    const idx = channels.value.findIndex((c) => c.id === id)
    if (idx >= 0) {
      const copy = [...channels.value]
      copy[idx] = { ...copy[idx], ...patch }
      channels.value = copy
    }
    pending.value[id] = { ...(pending.value[id] ?? {}), ...patch }
    scheduleSave()
  }

  /** 拖拽排序：本地顺序已更新，500ms 防抖合并后一次性提交 */
  function scheduleReorder() {
    if (reorderTimer !== null) window.clearTimeout(reorderTimer)
    reorderTimer = window.setTimeout(() => {
      reorderTimer = null
      void commitReorder()
    }, 500)
  }

  async function commitReorder() {
    const orderedIds = channels.value.map((c) => c.id)
    try {
      await api.reorderChannels(orderedIds)
      error.value = null
    } catch (e) {
      error.value = `排序保存失败：${(e as Error).message}`
    } finally {
      await refresh()
    }
  }

  async function toggleFavorite(id: string) {
    const idx = channels.value.findIndex((c) => c.id === id)
    if (idx < 0) return
    const next = !channels.value[idx].isFavorite
    const copy = [...channels.value]
    copy[idx] = { ...copy[idx], isFavorite: next }
    channels.value = copy
    try {
      await api.setFavorite(id, next)
      error.value = null
      await refresh()
    } catch (e) {
      const rollback = [...channels.value]
      const ri = rollback.findIndex((c) => c.id === id)
      if (ri >= 0) rollback[ri] = { ...rollback[ri], isFavorite: !next }
      channels.value = rollback
      error.value = `收藏保存失败：${(e as Error).message}`
    }
  }

  async function removeChannel(id: string) {
    try {
      await api.deleteChannel(id)
      channels.value = channels.value.filter((c) => c.id !== id)
      delete pending.value[id]
      error.value = null
      await refresh()
    } catch (e) {
      error.value = `删除失败：${(e as Error).message}`
    }
  }

  function batchToggleHidden(ids: string[], isHidden: boolean) {
    ids.forEach((id) => applyPatch(id, { isHidden }))
  }

  async function batchDelete(ids: string[]) {
    for (const id of ids) {
      try {
        await api.deleteChannel(id)
        channels.value = channels.value.filter((c) => c.id !== id)
      } catch (e) {
        error.value = `删除失败：${(e as Error).message}`
      }
    }
    ids.forEach((id) => delete pending.value[id])
    await refresh()
  }

  return {
    channels,
    loading,
    error,
    refresh,
    applyPatch,
    scheduleReorder,
    commitReorder,
    toggleFavorite,
    removeChannel,
    batchToggleHidden,
    batchDelete,
  }
})
