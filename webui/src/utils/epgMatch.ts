// EPG 匹配来源（v5）的中文展示：manual / rule / level1~level5 / null（未匹配）

export interface EpgSourceMeta {
  text: string
  tagType: 'primary' | 'warning' | 'info'
}

/** 匹配来源 → { 中文文案, 标签类型 }；null/未知一律视为「未匹配」。 */
export function epgMatchSourceMeta(source: string | null | undefined): EpgSourceMeta {
  if (source === 'manual') return { text: '手动绑定', tagType: 'primary' }
  if (source === 'rule') return { text: '规则匹配', tagType: 'warning' }
  const m = source?.match(/^level([1-5])$/)
  if (m) return { text: `自动匹配 · 第 ${m[1]} 级`, tagType: 'info' }
  return { text: '未匹配', tagType: 'info' }
}
