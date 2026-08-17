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
