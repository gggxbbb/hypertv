// 与后端 ManagementModels.kt 对齐的 API 类型定义

export interface ChannelDTO {
  id: string
  sourceId: string
  /** 频道号 = 全局 orderIndex + 1，与分组无关 */
  number: number
  name: string
  url: string
  groupName: string
  logoUrl: string | null
  isFavorite: boolean
  isHidden: boolean
  epgId: string | null
  catchup: string | null
}

export interface GroupDTO {
  name: string
  orderIndex: number
  channelCount: number
}

export interface ApiError {
  error: string
}

// ---- 直播源（ticket 08）----

export interface PlaylistDTO {
  id: string
  name: string
  /** "url" 或 "file" */
  type: string
  url: string
  channelCount: number
  lastImportedAt: number
}

/** 预览响应的单条频道摘要 */
export interface ChannelPreview {
  name: string
  url: string
  groupName: string
  logoUrl: string | null
  epgId: string | null
}

/** 解析预览响应（不落库）；imported/updated/hidden/existingChannelCount 为同源增量预测（无匹配源时为 null） */
export interface ImportPreview {
  total: number
  groups: string[]
  preview: ChannelPreview[]
  encoding: string
  sourceName: string
  url: string | null
  imported: number | null
  updated: number | null
  hidden: number | null
  existingChannelCount: number | null
}

/** 导入/刷新执行响应 */
export interface ImportResult {
  imported: number
  updated: number
  hidden: number
  sourceId: string
}
