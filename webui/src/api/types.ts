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

// ---- EPG（ticket 09）----

/** EPG 匹配统计（GET /api/epg/source 的 status.stats） */
export interface EpgMatchStats {
  total: number
  matched: number
  unmatched: number
  level1: number
  level2: number
  level3: number
  /** 命中率 0~1 */
  rate: number
}

/** 刷新状态（内存态 + 持久化 lastUpdate） */
export interface EpgStatus {
  running: boolean
  scope: string | null
  lastUpdate: number | null
  lastError: string | null
  stats: EpgMatchStats | null
}

/** 分组级 EPG 源（epgUrl 为 null 表示未覆盖，回退全局源） */
export interface EpgGroupSource {
  name: string
  epgUrl: string | null
}

/** GET /api/epg/source 响应 */
export interface EpgSourceConfig {
  globalUrl: string | null
  groups: EpgGroupSource[]
  status: EpgStatus
}

/** EPG 节目（now/guide 共用） */
export interface EpgProgram {
  id: string
  channelId: string
  title: string
  description: string | null
  category: string | null
  startTime: number
  endTime: number
}

/** GET /api/epg/guide 响应 */
export interface EpgGuide {
  channelId: string
  date: string
  programs: EpgProgram[]
}
