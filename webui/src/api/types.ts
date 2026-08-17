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
  /** EPG 匹配来源（v5）：null=未匹配；"manual" | "rule" | "level1"~"level5" */
  epgMatchSource: string | null
  catchup: string | null
  /** 以下为可选元数据（后端 DTO 暂未全部返回，有值才展示） */
  catchupDays?: number | null
  catchupSource?: string | null
  orderIndex?: number | null
  tvgId?: string | null
  createdAt?: number | null
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

/** 全局 EPG 源（id 为后端自增主键） */
export interface EpgSource {
  id: number
  url: string
  enabled: boolean
}

/** 分组级 EPG 源（url 为 null 表示未覆盖，回退全局源） */
export interface EpgGroupSource {
  groupName: string
  url: string | null
}

/** GET /api/epg/source 响应：全局多源 + 分组级源 + 刷新状态 */
export interface EpgSourceConfig {
  sources: EpgSource[]
  groupSources: EpgGroupSource[]
  status: EpgStatus
}

/** EPG 匹配规则（v3）：把「同一 EPG 频道、不同清晰度多个源」归并到同一 epgId */
export interface EpgMatchRule {
  id: number
  epgChannelId: string
  keyword: string
  /** "prefix" 前缀匹配 | "contains" 包含匹配 */
  ruleType: 'prefix' | 'contains'
  /** 当前 epgId == epgChannelId 的频道数 */
  matchedCount: number
}

/** POST /api/epg/rules 请求体 */
export interface EpgMatchRuleInput {
  epgChannelId: string
  keyword: string
  ruleType: 'prefix' | 'contains'
}

/** POST /api/epg/rules/apply 响应 */
export interface EpgRuleApplyResult {
  applied: number
}

/** GET /api/epg/channels 候选列表项：EPG 频道目录条目（displayName 供界面辨认，如 id=1 → CCTV1） */
export interface EpgChannelCandidate {
  epgId: string
  /** XMLTV 频道展示名（持久化在 epg_channels 目录，刷新后仍可辨认） */
  displayName: string
  /** XMLTV 频道台标 */
  icon: string | null
  /** 当前 epgId 关联到的本地频道数 */
  matchedCount: number
  /** 关联的本地频道名样例（最多 5 个） */
  channelNames: string[]
}

/** EPG 频道 id → displayName 映射（由 /api/epg/channels 目录构建，用于把裸数字 id 渲染成可读频道名） */
export type EpgChannelNameMap = Record<string, string>

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
