<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { VueDraggable } from 'vue-draggable-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Star, StarFilled, Delete, Picture, EditPen, Refresh } from '@element-plus/icons-vue'
import { useChannelsStore } from '@/stores/channels'
import { useGroupsStore } from '@/stores/groups'
import { usePolling } from '@/composables/usePolling'
import { debounce } from '@/composables/useDebounce'
import type { ChannelDTO } from '@/api/types'

const channelsStore = useChannelsStore()
const groupsStore = useGroupsStore()

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
  void Promise.all([channelsStore.refresh(), groupsStore.refresh()])
})

function onLogoError(e: Event) {
  ;(e.target as HTMLImageElement).style.visibility = 'hidden'
}

onMounted(() => {
  rows.value = filtered.value
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
        <template #prefix>🔍</template>
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
}
.search-input {
  width: 340px;
}
.group-select {
  width: 160px;
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
}
.list-scroll {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}
.channel-list {
  display: flex;
  flex-direction: column;
}
.channel-row {
  display: grid;
  grid-template-columns: 24px 28px 56px 36px 1fr 150px 32px 44px 72px;
  align-items: center;
  gap: 6px;
  height: 52px;
  padding: 0 10px;
  border-bottom: 1px solid #f3f4f6;
  font-size: 13px;
}
.channel-row:hover {
  background: #f9fafb;
}
.channel-row.selected {
  background: #eef2ff;
}
.channel-row.hidden .ch-name-text,
.channel-row.hidden .ch-number {
  color: #9ca3af;
  text-decoration: line-through;
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
.channel-row:hover .edit-hint {
  visibility: visible;
}
.ch-group {
  width: 150px;
}
.ch-fav {
  padding: 0;
}
.ch-actions {
  display: flex;
  justify-content: flex-end;
}
.empty-hint {
  padding: 40px;
  text-align: center;
  color: #9ca3af;
}
</style>
