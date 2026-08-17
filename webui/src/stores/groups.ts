import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { GroupDTO } from '@/api/types'

export const useGroupsStore = defineStore('groups', () => {
  const groups = ref<GroupDTO[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function refresh() {
    loading.value = true
    try {
      groups.value = await api.groups()
      error.value = null
    } catch (e) {
      error.value = (e as Error).message
    } finally {
      loading.value = false
    }
  }

  async function create(name: string) {
    try {
      const group = await api.createGroup(name)
      groups.value = [...groups.value, group]
      error.value = null
      return group
    } catch (e) {
      error.value = (e as Error).message
      throw e
    }
  }

  async function rename(name: string, newName: string) {
    try {
      const group = await api.renameGroup(name, newName)
      groups.value = groups.value.map((g) => (g.name === name ? group : g))
      error.value = null
    } catch (e) {
      error.value = (e as Error).message
      throw e
    }
  }

  async function remove(name: string) {
    try {
      await api.deleteGroup(name)
      groups.value = groups.value.filter((g) => g.name !== name)
      error.value = null
    } catch (e) {
      error.value = (e as Error).message
      throw e
    }
  }

  async function reorder(names: string[]) {
    try {
      await api.reorderGroups(names)
      error.value = null
      await refresh()
    } catch (e) {
      error.value = `排序保存失败：${(e as Error).message}`
      await refresh()
    }
  }

  return { groups, loading, error, refresh, create, rename, remove, reorder }
})
