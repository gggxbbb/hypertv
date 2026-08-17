import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { PlaylistDTO } from '@/api/types'

/**
 * 直播源列表状态：拉取全部源（含频道数），操作后立即刷新（多标签页同步）。
 */
export const usePlaylistsStore = defineStore('playlists', () => {
  const playlists = ref<PlaylistDTO[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function refresh() {
    loading.value = true
    try {
      playlists.value = await api.playlists()
      error.value = null
    } catch (e) {
      error.value = (e as Error).message
    } finally {
      loading.value = false
    }
  }

  async function rename(id: string, name: string) {
    try {
      const dto = await api.renamePlaylist(id, name)
      playlists.value = playlists.value.map((p) => (p.id === id ? dto : p))
      error.value = null
    } catch (e) {
      error.value = (e as Error).message
      throw e
    }
  }

  async function remove(id: string) {
    try {
      await api.deletePlaylist(id)
      playlists.value = playlists.value.filter((p) => p.id !== id)
      error.value = null
    } catch (e) {
      error.value = (e as Error).message
      throw e
    }
  }

  /** 刷新某直播源内容（增量合并），成功返回计数；失败抛出错误。 */
  async function refreshSource(id: string) {
    try {
      const result = await api.refreshPlaylist(id)
      error.value = null
      return result
    } catch (e) {
      error.value = (e as Error).message
      throw e
    }
  }

  return { playlists, loading, error, refresh, rename, remove, refreshSource }
})
