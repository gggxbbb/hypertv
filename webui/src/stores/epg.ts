import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { EpgSourceConfig } from '@/api/types'

/**
 * EPG 配置与刷新状态：拉取 source 配置（含刷新状态），
 * 手动触发全局/分组刷新，操作后立即刷新（多标签页同步，ADR-0003）。
 */
export const useEpgStore = defineStore('epg', () => {
  const config = ref<EpgSourceConfig | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  /** 手动刷新按钮的进行态 */
  const refreshingScope = ref<string | null>(null)

  async function refresh() {
    loading.value = true
    try {
      config.value = await api.epgSource()
      error.value = null
    } catch (e) {
      error.value = (e as Error).message
    } finally {
      loading.value = false
    }
  }

  async function saveGlobalUrl(url: string) {
    try {
      config.value = await api.setEpgSource(url)
      error.value = null
    } catch (e) {
      error.value = `保存全局源失败：${(e as Error).message}`
      throw e
    }
  }

  async function saveGroupUrl(groupId: string, url: string) {
    try {
      config.value = await api.setEpgSource(url, groupId)
      error.value = null
    } catch (e) {
      error.value = `保存分组源失败：${(e as Error).message}`
      throw e
    }
  }

  /** 触发异步刷新并立即拉取状态；scope 为 'global' 或分组名 */
  async function triggerRefresh(scope?: string) {
    const key = scope ?? 'global'
    refreshingScope.value = key
    try {
      await api.refreshEpg(scope)
      error.value = null
    } catch (e) {
      error.value = `触发刷新失败：${(e as Error).message}`
      throw e
    } finally {
      refreshingScope.value = null
      await refresh()
    }
  }

  return {
    config,
    loading,
    error,
    refreshingScope,
    refresh,
    saveGlobalUrl,
    saveGroupUrl,
    triggerRefresh,
  }
})
