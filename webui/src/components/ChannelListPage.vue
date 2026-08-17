<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { VueDraggable } from 'vue-draggable-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Star,
  StarFilled,
  Delete,
  Picture,
  EditPen,
  Refresh,
  Search,
  ArrowDown,
  ArrowRight,
  CopyDocument,
} from '@element-plus/icons-vue'
import { useChannelsStore } from '@/stores/channels'
import { useGroupsStore } from '@/stores/groups'
import { usePlaylistsStore } from '@/stores/playlists'
import { usePolling } from '@/composables/usePolling'
import { debounce } from '@/composables/useDebounce'
import { api } from '@/api/client'
import { epgMatchSourceMeta } from '@/utils/epgMatch'
import type { ChannelDTO, EpgChannelCandidate, EpgChannelNameMap, EpgProgram } from '@/api/types'

const channelsStore = useChannelsStore()
const groupsStore = useGroupsStore()
const playlistsStore = usePlaylistsStore()

// ---- 过滤条件 ----
const searchInput = ref('')
const searchTerm = ref('')
const groupFilter = ref('')
const hideOnly = ref(false)
const debouncedSearch = debounce(() => {
  searchTerm.value = searchInput.value
}, 250)
watch(searchInput, () => debouncedSearch())

const groupOptions = computed(() => groupsStore.groups.map((g) => g.name))

const filtered = computed(() => {
  let list = channelsStore.channels
  if (groupFilter.value !== '') {
    list = list.filter((c) => c.groupName === groupFilter.value)
  }
  if (hideOnly.value) {
    list = list.filter((c) => c.isHidden)
  }
  const q = searchTerm.value.trim().toLowerCase()
  if (q) {
    list = list.filter(
      (c) =>
        c.name.toLowerCase().includes(q) ||
        c.groupName.toLowerCase().includes(q) ||
        String(c.number).includes(q),
    )
  }
  return list
})

/** 列表行：拖拽排序的目标数组（过滤视图），变更后同步回全量并防抖提交 */
const rows = ref<ChannelDTO[]>([])
watch(filtered, (v) => {
  rows.value = v
})

function syncOrderToAll(ordered: ChannelDTO[]) {
  const all = channelsStore.channels
  const ids = new Set(ordered.map((c) => c.id))
  const rest = all.filter((c) => !ids.has(c.id))
  channelsStore.channels = [...ordered, ...rest]
}

function onDragChange() {
  syncOrderToAll(rows.value)
  channelsStore.scheduleReorder()
}

// ---- 多选 ----
const selectedIds = ref<Set<string>>(new Set())

function toggleSelect(id: string, checked: boolean) {
  const next = new Set(selectedIds.value)
  if (checked) next.add(id)
  else next.delete(id)
  selectedIds.value = next
}

const selectedCount = computed(() => selectedIds.value.size)

const allSelected = computed(
  () => rows.value.length > 0 && rows.value.every((c) => selectedIds.value.has(c.id)),
)
const someSelected = computed(() => rows.value.some((c) => selectedIds.value.has(c.id)))

function toggleSelectAll(checked: boolean) {
  selectedIds.value = new Set(checked ? rows.value.map((c) => c.id) : [])
}

// ---- 行内编辑 ----
const editingId = ref<string | null>(null)
const editingName = ref('')

function startEdit(ch: ChannelDTO) {
  editingId.value = ch.id
  editingName.value = ch.name
}

function commitEdit() {
  if (editingId.value) {
    const name = editingName.value.trim()
    if (name) channelsStore.applyPatch(editingId.value, { name })
  }
  editingId.value = null
}

function applyGroup(id: string, groupName: string) {
  channelsStore.applyPatch(id, { groupName })
}

async function editLogo(ch: ChannelDTO) {
  try {
    const { value } = await ElMessageBox.prompt('输入台标图片 URL（留空清除）', '编辑台标', {
      inputValue: ch.logoUrl ?? '',
      inputPlaceholder: 'https://example.com/logo.png',
    })
    channelsStore.applyPatch(ch.id, { logoUrl: value.trim() === '' ? null : value.trim() })
  } catch {
    /* 取消 */
  }
}

// ---- EPG 手动绑定 ----
const editingEpgId = ref<string | null>(null)
const editingEpgValue = ref('')
const epgCandidates = ref<EpgChannelCandidate[]>([])
const epgCandidatesLoading = ref(false)

/** EPG 频道目录 id → displayName 映射（供 EPG 列展示与绑定下拉） */
const epgNameMap = computed<EpgChannelNameMap>(() =>
  Object.fromEntries(epgCandidates.value.map((c) => [c.epgId, c.displayName])),
)

/** 下拉选项显示名：displayName (epgId)，filterable 按 label 可同时搜两个字段 */
function epgCandidateLabel(c: EpgChannelCandidate): string {
  return `${c.displayName} (${c.epgId})`
}

/** EPG 列展示名：目录里有 displayName 用之；未绑定显示「未匹配」；目录缺失的 id 回退原 id */
function epgDisplayName(epgId: string | null): string {
  if (!epgId) return '未匹配'
  return epgNameMap.value[epgId] ?? epgId
}

async function loadEpgCandidates() {
  epgCandidatesLoading.value = true
  try {
    epgCandidates.value = await api.epgChannels()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    epgCandidatesLoading.value = false
  }
}

function startEpgEdit(ch: ChannelDTO) {
  editingEpgId.value = ch.id
  editingEpgValue.value = ch.epgId ?? ''
}

/** 行内 EPG 绑定提交：空值 = 清除（置 null），非空 = 手动绑定（后端置 epgManual，导入/刷新不再覆盖） */
function commitEpg(id: string, value: string | number | boolean) {
  const epgId = String(value ?? '').trim()
  channelsStore.applyPatch(id, { epgId: epgId ? epgId : null })
  ElMessage.success(
    epgId ? `已绑定 ${epgDisplayName(epgId)}（${epgId}），手动绑定后不会被导入/刷新覆盖` : '已清除 EPG 绑定',
  )
  editingEpgId.value = null
}

/** 下拉收起时若未选择则退出编辑态（不保存） */
function onEpgSelectVisible(visible: boolean) {
  if (!visible) editingEpgId.value = null
}

// ---- 频道详情：展开 + 今日节目单 ----
const expandedId = ref<string | null>(null)

interface GuideState {
  channelId: string
  loading: boolean
  error: string | null
  programs: EpgProgram[]
}
const guideState = ref<GuideState | null>(null)

function todayLocal(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function formatClock(ts: number): string {
  const d = new Date(ts)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function formatTimestamp(ts?: number | null): string {
  if (!ts) return '—'
  return new Date(ts).toLocaleString('zh-CN', { hour12: false })
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败')
  }
}

/** 直播源名映射：playlists store 可用时显示源名，否则回退 sourceId */
const playlistNameById = computed(() => new Map(playlistsStore.playlists.map((p) => [p.id, p.name])))

function sourceLabel(ch: ChannelDTO): string {
  const name = playlistNameById.value.get(ch.sourceId)
  return name ? `${name}（${ch.sourceId}）` : ch.sourceId
}

function toggleExpand(ch: ChannelDTO) {
  if (expandedId.value === ch.id) {
    expandedId.value = null
    guideState.value = null
  } else {
    expandedId.value = ch.id
    void loadGuide(ch)
  }
}

/** 拉取该频道今日节目单；未绑定 EPG 直接置空（不请求）。 */
async function loadGuide(ch: ChannelDTO) {
  if (!ch.epgId) {
    guideState.value = { channelId: ch.id, loading: false, error: null, programs: [] }
    return
  }
  guideState.value = { channelId: ch.id, loading: true, error: null, programs: [] }
  try {
    const guide = await api.epgGuide(ch.id, todayLocal())
    guideState.value = { channelId: ch.id, loading: false, error: null, programs: guide.programs }
  } catch (e) {
    guideState.value = { channelId: ch.id, loading: false, error: (e as Error).message, programs: [] }
  }
}

// ---- 删除 ----
async function confirmDelete(ch: ChannelDTO) {
  try {
    await ElMessageBox.confirm(`确定删除频道「${ch.name}」？删除后不可恢复。`, '删除频道', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await channelsStore.removeChannel(ch.id)
    ElMessage.success('已删除')
  } catch {
    /* 取消 */
  }
}

async function confirmBatchDelete() {
  const ids = [...selectedIds.value]
  if (ids.length === 0) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${ids.length} 个频道？删除后不可恢复。`, '批量删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await channelsStore.batchDelete(ids)
    selectedIds.value = new Set()
    ElMessage.success(`已删除 ${ids.length} 个频道`)
  } catch {
    /* 取消 */
  }
}

function batchHide(hide: boolean) {
  const ids = [...selectedIds.value]
  if (ids.length === 0) return
  channelsStore.batchToggleHidden(ids, hide)
  ElMessage.success(hide ? `已隐藏 ${ids.length} 个频道` : `已恢复 ${ids.length} 个频道`)
}

// ---- 轮询同步（5s）+ 操作后立即拉取（ADR-0003）----
const polling = usePolling(() => {
  void Promise.all([channelsStore.refresh(), groupsStore.refresh(), loadEpgCandidates(), playlistsStore.refresh()])
})

function onLogoError(e: Event) {
  ;(e.target as HTMLImageElement).style.visibility = 'hidden'
}

onMounted(() => {
  rows.value = filtered.value
  void loadEpgCandidates()
})

defineExpose({ refresh: polling.refresh })
</script>

<template>
  <div class="page">
    <!-- 工具栏 -->
    <div class="toolbar">
      <el-input
        v-model="searchInput"
        class="search-input"
        placeholder="搜索频道名 / 分组 / 频道号（即时搜索）"
        clearable
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select v-model="groupFilter" class="group-select" placeholder="全部分组" clearable>
        <el-option label="全部分组" value="" />
        <el-option label="未分组" value="__none__" />
        <el-option v-for="g in groupOptions" :key="g" :label="g" :value="g" />
      </el-select>
      <el-checkbox v-model="hideOnly" class="hide-toggle">仅看隐藏</el-checkbox>
      <el-checkbox
        :model-value="allSelected"
        :indeterminate="someSelected"
        class="hide-toggle"
        @change="(v: string | number | boolean) => toggleSelectAll(Boolean(v))"
      >
        全选
      </el-checkbox>
      <el-button :icon="Refresh" :loading="channelsStore.loading" @click="polling.refresh">
        刷新
      </el-button>
      <el-tag type="info" effect="plain">共 {{ channelsStore.channels.length }} 个频道</el-tag>
    </div>

    <!-- 错误提示 -->
    <el-alert v-if="channelsStore.error" type="error" :closable="true" class="alert" show-icon>
      {{ channelsStore.error }}
    </el-alert>

    <!-- 批量操作栏 -->
    <div v-if="selectedCount > 0" class="batch-bar">
      <span>已选 {{ selectedCount }} 个频道</span>
      <el-button size="small" @click="batchHide(true)">隐藏</el-button>
      <el-button size="small" @click="batchHide(false)">恢复</el-button>
      <el-button size="small" type="danger" @click="confirmBatchDelete">删除</el-button>
      <el-button size="small" text @click="selectedIds = new Set()">清空选择</el-button>
    </div>

    <!-- 列表 -->
    <div class="list-scroll">
      <VueDraggable
        v-model="rows"
        class="channel-list"
        :animation="150"
        handle=".drag-handle"
        ghost-class="drag-ghost"
        chosen-class="drag-chosen"
        :group="{ name: 'channel-list', pull: false, put: false }"
        @change="onDragChange"
      >
        <div
          v-for="ch in rows"
          :key="ch.id"
          class="channel-row"
          :class="{ hidden: ch.isHidden, selected: selectedIds.has(ch.id) }"
        >
          <div class="channel-row-grid">
            <el-button
              class="row-expand"
              link
              :title="expandedId === ch.id ? '收起详情' : '展开详情'"
              @click="toggleExpand(ch)"
            >
              <el-icon><ArrowDown v-if="expandedId === ch.id" /><ArrowRight v-else /></el-icon>
            </el-button>
          <span class="drag-handle" title="拖拽排序">⠿</span>
          <el-checkbox
            :model-value="selectedIds.has(ch.id)"
            class="row-check"
            @change="(v: string | number | boolean) => toggleSelect(ch.id, Boolean(v))"
          />
          <span class="ch-number">{{ ch.number }}</span>
          <span class="ch-logo">
            <img v-if="ch.logoUrl" :src="ch.logoUrl" alt="" loading="lazy" @error="onLogoError" />
            <el-icon v-else><Picture /></el-icon>
          </span>
          <span v-if="editingId === ch.id" class="ch-name">
            <el-input
              v-model="editingName"
              size="small"
              autofocus
              @blur="commitEdit"
              @keyup.enter="commitEdit"
            />
          </span>
          <span v-else class="ch-name ch-name-text" :title="ch.name" @dblclick="startEdit(ch)">
            {{ ch.name }}
            <el-icon class="edit-hint"><EditPen /></el-icon>
          </span>
          <el-select
            :model-value="ch.groupName"
            class="ch-group"
            size="small"
            placeholder="未分组"
            @change="(v: string) => applyGroup(ch.id, v)"
          >
            <el-option label="未分组" value="" />
            <el-option v-for="g in groupOptions" :key="g" :label="g" :value="g" />
          </el-select>
          <span class="ch-epg">
            <el-select
              v-if="editingEpgId === ch.id"
              v-model="editingEpgValue"
              class="epg-select"
              size="small"
              filterable
              allow-create
              default-first-option
              clearable
              placeholder="输入或选择 epgId"
              :loading="epgCandidatesLoading"
              @change="(v: string | number | boolean) => commitEpg(ch.id, v)"
              @visible-change="onEpgSelectVisible"
            >
              <el-option
                v-for="c in epgCandidates"
                :key="c.epgId"
                :label="epgCandidateLabel(c)"
                :value="c.epgId"
              >
                <template #default>
                  <div class="candidate-option">
                    <span class="candidate-name">{{ c.displayName }}</span>
                    <span class="candidate-id">({{ c.epgId }})</span>
                    <el-tag v-if="c.matchedCount > 0" size="small" type="success" effect="plain">
                      命中 {{ c.matchedCount }}
                    </el-tag>
                  </div>
                </template>
              </el-option>
            </el-select>
            <span
              v-else
              class="epg-text"
              :class="{ 'epg-unmatched': !ch.epgId }"
              :title="ch.epgId ? `点击修改；显示的是 EPG 频道名（${ch.epgId} 为手动绑定，不会被导入/刷新覆盖）` : '点击绑定 EPG 频道'"
              @click="startEpgEdit(ch)"
            >
              {{ epgDisplayName(ch.epgId) }}
            </span>
          </span>
          <el-button
            class="ch-fav"
            link
            :type="ch.isFavorite ? 'warning' : 'info'"
            :title="ch.isFavorite ? '取消收藏' : '收藏'"
            @click="channelsStore.toggleFavorite(ch.id)"
          >
            <el-icon><StarFilled v-if="ch.isFavorite" /><Star v-else /></el-icon>
          </el-button>
          <el-switch
            :model-value="ch.isHidden"
            size="small"
            :title="ch.isHidden ? '恢复显示' : '隐藏'"
            @change="(v: string | number | boolean) => channelsStore.applyPatch(ch.id, { isHidden: Boolean(v) })"
          />
          <span class="ch-actions">
            <el-button link :title="'编辑台标'" @click="editLogo(ch)">
              <el-icon><EditPen /></el-icon>
            </el-button>
            <el-button link type="danger" :title="'删除'" @click="confirmDelete(ch)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </span>
          </div>

          <!-- 详情卡片 -->
          <div v-if="expandedId === ch.id" class="channel-detail">
            <div class="detail-grid">
              <div class="detail-item">
                <span class="detail-label">频道名</span>
                <span class="detail-value">{{ ch.name }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">频道号</span>
                <span class="detail-value">{{ ch.number }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">分组</span>
                <span class="detail-value">{{ ch.groupName || '未分组' }}</span>
              </div>
              <div class="detail-item detail-item-wide">
                <span class="detail-label">播放地址</span>
                <span class="detail-value detail-url">
                  <a :href="ch.url" target="_blank" rel="noopener" class="url-text">{{ ch.url }}</a>
                  <el-button link size="small" title="复制播放地址" @click="copyText(ch.url)">
                    <el-icon><CopyDocument /></el-icon>
                  </el-button>
                </span>
              </div>
              <div v-if="ch.logoUrl" class="detail-item detail-item-wide">
                <span class="detail-label">台标</span>
                <span class="detail-value detail-logo">
                  <img :src="ch.logoUrl" alt="" class="detail-logo-img" loading="lazy" @error="onLogoError" />
                  <a :href="ch.logoUrl" target="_blank" rel="noopener" class="url-text">{{ ch.logoUrl }}</a>
                  <el-button link size="small" title="复制台标 URL" @click="copyText(ch.logoUrl)">
                    <el-icon><CopyDocument /></el-icon>
                  </el-button>
                </span>
              </div>
              <div class="detail-item">
                <span class="detail-label">直播源</span>
                <span class="detail-value">{{ sourceLabel(ch) }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">频道 ID</span>
                <span class="detail-value">{{ ch.id }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">收藏</span>
                <span class="detail-value">{{ ch.isFavorite ? '已收藏' : '未收藏' }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">状态</span>
                <span class="detail-value">{{ ch.isHidden ? '已隐藏' : '正常' }}</span>
              </div>
              <div v-if="ch.orderIndex != null" class="detail-item">
                <span class="detail-label">排序索引</span>
                <span class="detail-value">{{ ch.orderIndex }}</span>
              </div>
              <div v-if="ch.catchup" class="detail-item">
                <span class="detail-label">回看</span>
                <span class="detail-value">{{ ch.catchup }}</span>
              </div>
              <div v-if="ch.catchupDays" class="detail-item">
                <span class="detail-label">回看天数</span>
                <span class="detail-value">{{ ch.catchupDays }}</span>
              </div>
              <div v-if="ch.catchupSource" class="detail-item">
                <span class="detail-label">回看源</span>
                <span class="detail-value">{{ ch.catchupSource }}</span>
              </div>
              <div v-if="ch.tvgId" class="detail-item">
                <span class="detail-label">tvg-id</span>
                <span class="detail-value">{{ ch.tvgId }}</span>
              </div>
              <div v-if="ch.createdAt" class="detail-item">
                <span class="detail-label">创建时间</span>
                <span class="detail-value">{{ formatTimestamp(ch.createdAt) }}</span>
              </div>
            </div>

            <div class="detail-section">
              <div class="detail-section-title">EPG 匹配</div>
              <div class="epg-match-row">
                <template v-if="ch.epgId">
                  <span class="epg-match-channel">{{ epgDisplayName(ch.epgId) }}</span>
                  <span class="epg-match-id">({{ ch.epgId }})</span>
                  <el-tag size="small" :type="epgMatchSourceMeta(ch.epgMatchSource).tagType" effect="plain">
                    {{ epgMatchSourceMeta(ch.epgMatchSource).text }}
                  </el-tag>
                  <el-button link size="small" title="复制 epgId" @click="copyText(ch.epgId!)">
                    <el-icon><CopyDocument /></el-icon>
                  </el-button>
                </template>
                <span v-else class="epg-unmatched">未匹配</span>
              </div>
            </div>

            <div class="detail-section">
              <div class="detail-section-title">今日节目单（{{ todayLocal() }}）</div>
              <div v-if="guideState?.loading" class="guide-hint">加载中…</div>
              <div v-else-if="guideState?.error" class="guide-hint guide-error">
                加载失败：{{ guideState.error }}
                <el-button link size="small" @click="loadGuide(ch)">重试</el-button>
              </div>
              <div v-else-if="!ch.epgId" class="guide-hint">未匹配 EPG，无节目单</div>
              <div v-else-if="guideState && guideState.programs.length === 0" class="guide-hint">
                该频道当天暂无节目单（EPG 源中可能没有该频道的数据）
              </div>
              <ul v-else class="guide-list">
                <li v-for="p in guideState?.programs" :key="p.id" class="guide-item">
                  <span class="guide-time">{{ formatClock(p.startTime) }} - {{ formatClock(p.endTime) }}</span>
                  <span class="guide-title" :title="p.description || ''">{{ p.title }}</span>
                </li>
              </ul>
            </div>
          </div>
        </div>
        <div v-if="rows.length === 0" class="empty-hint">没有符合条件的频道</div>
      </VueDraggable>
    </div>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 12px 16px;
  gap: 10px;
  min-height: 0;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  /* 窄屏允许换行，避免固定宽度控件横向溢出 */
  flex-wrap: wrap;
}
.search-input {
  width: 340px;
  max-width: 100%;
}
.group-select {
  width: 160px;
  max-width: 100%;
}
.hide-toggle {
  margin-left: 4px;
  white-space: nowrap;
}
.alert {
  flex-shrink: 0;
}
.batch-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #eef2ff;
  border: 1px solid #c7d2fe;
  border-radius: 6px;
  flex-shrink: 0;
  flex-wrap: wrap;
}
.list-scroll {
  flex: 1;
  overflow: auto;
  min-height: 0;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}
.channel-list {
  display: flex;
  flex-direction: column;
  min-width: 1080px;
}
.channel-row {
  display: flex;
  flex-direction: column;
  border-bottom: 1px solid #f3f4f6;
}
.channel-row-grid {
  display: grid;
  grid-template-columns: 24px 24px 28px 56px 36px 1fr 150px 180px 32px 44px 72px;
  align-items: center;
  gap: 6px;
  height: 52px;
  padding: 0 10px;
  font-size: 13px;
}
.channel-row-grid:hover {
  background: #f9fafb;
}
.channel-row.selected .channel-row-grid {
  background: #eef2ff;
}
.channel-row.hidden .ch-name-text,
.channel-row.hidden .ch-number {
  color: #9ca3af;
  text-decoration: line-through;
}
.row-expand {
  padding: 0;
  color: #9ca3af;
}
.drag-handle {
  cursor: grab;
  color: #9ca3af;
  font-size: 14px;
  user-select: none;
}
.drag-handle:active {
  cursor: grabbing;
}
.row-check {
  margin-right: 0;
}
.ch-number {
  font-variant-numeric: tabular-nums;
  color: #6b7280;
  text-align: right;
}
.ch-logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 4px;
  background: #f3f4f6;
  overflow: hidden;
  color: #9ca3af;
}
.ch-logo img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}
.ch-name-text {
  cursor: text;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  display: flex;
  align-items: center;
  gap: 6px;
}
.edit-hint {
  visibility: hidden;
  color: #9ca3af;
}
.channel-row-grid:hover .edit-hint {
  visibility: visible;
}
.ch-group {
  width: 150px;
}
.ch-epg {
  min-width: 0;
}
.epg-select {
  width: 180px;
}
.candidate-option {
  display: flex;
  align-items: center;
  gap: 6px;
}
.candidate-option .el-tag {
  margin-left: auto;
  flex-shrink: 0;
}
.candidate-id {
  color: #9ca3af;
  font-size: 12px;
}
.epg-text {
  cursor: pointer;
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #374151;
}
.epg-unmatched {
  color: #9ca3af;
  font-style: italic;
}
.ch-fav {
  padding: 0;
}
.ch-actions {
  display: flex;
  justify-content: flex-end;
}
.channel-detail {
  padding: 12px 14px;
  background: #f8fafc;
  border-top: 1px solid #eef2f7;
  font-size: 13px;
}
.detail-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px 16px;
}
.detail-item {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.detail-item-wide {
  grid-column: span 3;
}
.detail-label {
  flex-shrink: 0;
  color: #6b7280;
  min-width: 56px;
}
.detail-value {
  color: #374151;
  min-width: 0;
  overflow-wrap: anywhere;
}
.detail-url {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}
.url-text {
  color: #2563eb;
  text-decoration: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.url-text:hover {
  text-decoration: underline;
}
.detail-logo {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.detail-logo-img {
  width: 28px;
  height: 28px;
  object-fit: contain;
  border-radius: 4px;
  background: #f3f4f6;
  flex-shrink: 0;
}
.detail-section {
  margin-top: 12px;
  border-top: 1px solid #e5e7eb;
  padding-top: 10px;
}
.detail-section-title {
  font-size: 12px;
  font-weight: 600;
  color: #6b7280;
  margin-bottom: 8px;
}
.epg-match-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.epg-match-channel {
  font-weight: 500;
  color: #111827;
}
.epg-match-id {
  color: #9ca3af;
  font-size: 12px;
}
.guide-hint {
  color: #9ca3af;
  padding: 6px 0;
}
.guide-error {
  color: #b91c1c;
}
.guide-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 220px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}
.guide-item {
  display: flex;
  gap: 12px;
  align-items: baseline;
  padding: 5px 4px;
  border-bottom: 1px dashed #e5e7eb;
  font-size: 13px;
}
.guide-item:last-child {
  border-bottom: none;
}
.guide-time {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
  color: #6b7280;
  font-size: 12px;
  min-width: 100px;
}
.guide-title {
  color: #374151;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.empty-hint {
  padding: 40px;
  text-align: center;
  color: #9ca3af;
}
</style>
