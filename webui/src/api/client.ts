import type { ChannelDTO, GroupDTO } from './types'

/** 频道字段的可编辑子集（对应 PUT /api/channels/{id} 的局部更新）。 */
export interface ChannelPatch {
  name?: string
  groupName?: string
  logoUrl?: string | null
  isHidden?: boolean
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (res.status === 204) return undefined as T
  if (!res.ok) {
    let message = `请求失败 (${res.status})`
    try {
      const body = (await res.json()) as { error?: string }
      if (body.error) message = body.error
    } catch {
      /* 非 JSON 错误体，保留默认信息 */
    }
    throw new Error(message)
  }
  return res.json() as Promise<T>
}

export const api = {
  // ---- 频道 ----
  channels(includeHidden = true) {
    return request<ChannelDTO[]>(`/api/channels?includeHidden=${includeHidden}`)
  },
  favoriteChannels() {
    return request<ChannelDTO[]>('/api/channels/favorites')
  },
  updateChannel(id: string, patch: ChannelPatch) {
    return request<ChannelDTO>(`/api/channels/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify(patch),
    })
  },
  deleteChannel(id: string) {
    return request<void>(`/api/channels/${encodeURIComponent(id)}`, { method: 'DELETE' })
  },
  reorderChannels(ids: string[]) {
    return request<void>('/api/channels/reorder', { method: 'POST', body: JSON.stringify({ ids }) })
  },
  setFavorite(id: string, favorite: boolean) {
    return request<void>(`/api/channels/${encodeURIComponent(id)}/favorite`, {
      method: 'POST',
      body: JSON.stringify({ favorite }),
    })
  },
  // ---- 分组 ----
  groups() {
    return request<GroupDTO[]>('/api/groups')
  },
  createGroup(name: string) {
    return request<GroupDTO>('/api/groups', { method: 'POST', body: JSON.stringify({ name }) })
  },
  renameGroup(name: string, newName: string) {
    return request<GroupDTO>('/api/groups', { method: 'POST', body: JSON.stringify({ name, newName }) })
  },
  deleteGroup(name: string) {
    return request<void>(`/api/groups/${encodeURIComponent(name)}`, { method: 'DELETE' })
  },
  reorderGroups(names: string[]) {
    return request<void>('/api/groups/reorder', { method: 'POST', body: JSON.stringify({ names }) })
  },
}
