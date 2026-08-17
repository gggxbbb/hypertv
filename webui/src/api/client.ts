import type { ChannelDTO, EpgGuide, EpgProgram, EpgSourceConfig, GroupDTO, ImportPreview, ImportResult, PlaylistDTO } from './types'

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
  // ---- 直播源（ticket 08）----
  playlists() {
    return request<PlaylistDTO[]>('/api/playlists')
  },
  renamePlaylist(id: string, name: string) {
    return request<PlaylistDTO>(`/api/playlists/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify({ name }),
    })
  },
  deletePlaylist(id: string) {
    return request<void>(`/api/playlists/${encodeURIComponent(id)}`, { method: 'DELETE' })
  },
  refreshPlaylist(id: string) {
    return request<ImportResult>(`/api/playlists/${encodeURIComponent(id)}/refresh`, { method: 'POST' })
  },
  // ---- 直播源导入（ticket 03/08）----
  previewImportUrl(url: string) {
    return request<ImportPreview>('/api/playlist/import/preview', { method: 'POST', body: JSON.stringify({ url }) })
  },
  importUrl(url: string) {
    return request<ImportResult>('/api/playlist/import', { method: 'POST', body: JSON.stringify({ url }) })
  },
  /** 上传文件并解析预览（不落库）。 */
  previewImportFile(file: File) {
    return uploadFormData<ImportPreview>('/api/playlist/upload/preview', file)
  },
  /** 确认导入上传文件；sourceName 非空时后端按 (type=file, name) 做同源增量合并。 */
  importFile(file: File, sourceName?: string) {
    const form = new FormData()
    if (sourceName) form.append('sourceName', sourceName)
    form.append('file', file)
    return uploadFormData<ImportResult>('/api/playlist/upload', form)
  },
  // ---- EPG（ticket 09）----
  epgSource() {
    return request<EpgSourceConfig>('/api/epg/source')
  },
  /** 设置全局或分组级 EPG 源；url 为空串 = 清除；groupId 省略 = 全局 */
  setEpgSource(url: string, groupId?: string) {
    return request<EpgSourceConfig>('/api/epg/source', {
      method: 'PUT',
      body: JSON.stringify({ url, ...(groupId ? { groupId } : {}) }),
    })
  },
  /** 触发异步刷新；groupId 省略 = 全局 */
  refreshEpg(groupId?: string) {
    return request<{ status: string; scope: string }>('/api/epg/refresh', {
      method: 'POST',
      body: JSON.stringify(groupId ? { groupId } : {}),
    })
  },
  /** 当前正在播放的节目表：Map<channelId, EpgProgram> */
  epgNow() {
    return request<Record<string, EpgProgram>>('/api/epg/now')
  },
  epgGuide(channelId: string, date?: string) {
    const params = new URLSearchParams({ channelId })
    if (date) params.set('date', date)
    return request<EpgGuide>(`/api/epg/guide?${params.toString()}`)
  },
}

/** multipart 上传：不设置 JSON Content-Type，让浏览器生成 multipart boundary。 */
async function uploadFormData<T>(path: string, body: File | FormData): Promise<T> {
  const form = body instanceof File ? (() => {
    const f = new FormData()
    f.append('file', body)
    return f
  })() : body
  const res = await fetch(path, { method: 'POST', body: form })
  if (!res.ok) {
    let message = `请求失败 (${res.status})`
    try {
      const json = (await res.json()) as { error?: string }
      if (json.error) message = json.error
    } catch {
      /* 非 JSON 错误体，保留默认信息 */
    }
    throw new Error(message)
  }
  return res.json() as Promise<T>
}
