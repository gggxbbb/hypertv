<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { VueDraggable } from 'vue-draggable-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, EditPen, Plus } from '@element-plus/icons-vue'
import { useChannelsStore } from '@/stores/channels'
import { useGroupsStore } from '@/stores/groups'
import { usePolling } from '@/composables/usePolling'
import type { ChannelDTO, GroupDTO } from '@/api/types'

const channelsStore = useChannelsStore()
const groupsStore = useGroupsStore()

// ---- 分组列表（拖拽排序）----
const groupRows = ref<GroupDTO[]>([])
watch(
  () => groupsStore.groups,
  (v) => {
    groupRows.value = v
    if (!selectedGroup.value && v.length > 0) {
      selectedGroup.value = v[0].name
    }
  },
)

const selectedGroup = ref<string | null>(null)

function selectGroup(name: string) {
  selectedGroup.value = name
}

function onGroupListChange() {
  if (groupReorderTimer !== null) window.clearTimeout(groupReorderTimer)
  groupReorderTimer = window.setTimeout(() => {
    groupReorderTimer = null
    void groupsStore.reorder(groupRows.value.map((g) => g.name))
  }, 500)
}
let groupReorderTimer: number | null = null

// ---- 新建 / 重命名 / 删除分组 ----
const newGroupName = ref('')
const renaming = ref<string | null>(null)
const renameValue = ref('')

async function createGroup() {
  const name = newGroupName.value.trim()
  if (!name) {
    ElMessage.warning('请输入分组名')
    return
  }
  try {
    await groupsStore.create(name)
    newGroupName.value = ''
    ElMessage.success(`已创建分组「${name}」`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function startRename(g: GroupDTO) {
  renaming.value = g.name
  renameValue.value = g.name
}

async function commitRename() {
  const oldName = renaming.value
  if (!oldName) return
  const newName = renameValue.value.trim()
  renaming.value = null
  if (!newName) {
    ElMessage.warning('分组名不能为空')
    return
  }
  try {
    await groupsStore.rename(oldName, newName)
    await channelsStore.refresh()
    ElMessage.success(`已重命名为「${newName}」`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function removeGroup(g: GroupDTO) {
  try {
    await ElMessageBox.confirm(
      `确定删除分组「${g.name}」？组内 ${g.channelCount} 个频道将归入"未分组"。`,
      '删除分组',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
    await groupsStore.remove(g.name)
    if (selectedGroup.value === g.name) selectedGroup.value = null
    await channelsStore.refresh()
    ElMessage.success('已删除分组')
  } catch {
    /* 取消 */
  }
}

// ---- 频道拖拽入组 / 出组 ----
const groupChannels = computed(() =>
  selectedGroup.value ? channelsStore.channels.filter((c) => c.groupName === selectedGroup.value) : [],
)
const poolChannels = computed(() =>
  // 待分配池 = 未分组频道（groupName 为空），而非"非当前分组的全部频道"
  channelsStore.channels.filter((c) => !c.groupName),
)

const groupRows2 = ref<ChannelDTO[]>([])
const poolRows = ref<ChannelDTO[]>([])
const poolSearch = ref('')

/** store 驱动下的本组频道 id 集合，用于 diff 出拖拽移动的频道 */
const lastGroupIds = ref<Set<string>>(new Set())

watch(groupChannels, (v) => {
  groupRows2.value = [...v]
  lastGroupIds.value = new Set(v.map((c) => c.id))
})

function filterPool(list: ChannelDTO[]) {
  const q = poolSearch.value.trim().toLowerCase()
  if (!q) return [...list]
  return list.filter((c) => c.name.toLowerCase().includes(q) || c.number === Number(q))
}

watch(poolChannels, (v) => {
  poolRows.value = filterPool(v)
})

watch(poolSearch, () => {
  poolRows.value = filterPool(poolChannels.value)
})

function onGroupChange() {
  // 拖拽结束后 v-model 已同步，diff 出进入/离开本组的频道
  void nextTick(() => {
    if (!selectedGroup.value) return
    const groupIds = new Set(groupRows2.value.map((c) => c.id))
    const added = groupRows2.value.filter((c) => !lastGroupIds.value.has(c.id))
    const removed = [...lastGroupIds.value].filter((id) => !groupIds.has(id))
    if (added.length === 0 && removed.length === 0) return
    added.forEach((c) => channelsStore.applyPatch(c.id, { groupName: selectedGroup.value! }))
    removed.forEach((id) => channelsStore.applyPatch(id, { groupName: '' }))
    lastGroupIds.value = groupIds
  })
}

function onLogoError(e: Event) {
  ;(e.target as HTMLImageElement).style.visibility = 'hidden'
}

// ---- 轮询同步 ----
const polling = usePolling(() => {
  void Promise.all([channelsStore.refresh(), groupsStore.refresh()])
})

onMounted(() => {
  groupRows.value = groupsStore.groups
  groupRows2.value = [...groupChannels.value]
  poolRows.value = filterPool(poolChannels.value)
})

defineExpose({ refresh: polling.refresh })
</script>

<template>
  <div class="page">
    <!-- 左栏：分组列表 -->
    <div class="left-panel">
      <div class="panel-title">分组（拖动排序）</div>
      <div class="group-list">
        <VueDraggable
          v-model="groupRows"
          :animation="150"
          handle=".drag-handle"
          ghost-class="drag-ghost"
          chosen-class="drag-chosen"
          @change="onGroupListChange"
        >
          <div
            v-for="g in groupRows"
            :key="g.name"
            class="group-row"
            :class="{ active: selectedGroup === g.name }"
            @click="selectGroup(g.name)"
          >
            <span class="drag-handle" title="拖拽排序">⠿</span>
            <span class="group-name" :title="g.name">{{ g.name }}</span>
            <el-tag size="small" type="info" effect="plain" class="group-count">
              {{ g.channelCount }}
            </el-tag>
            <el-button link :title="'重命名'" @click.stop="startRename(g)">
              <el-icon><EditPen /></el-icon>
            </el-button>
            <el-button link type="danger" :title="'删除'" @click.stop="removeGroup(g)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
          <div v-if="groupRows.length === 0" class="empty-hint">暂无分组</div>
        </VueDraggable>
      </div>
      <div class="create-row">
        <el-input
          v-model="newGroupName"
          size="small"
          placeholder="新分组名"
          clearable
          @keyup.enter="createGroup"
        />
        <el-button size="small" type="primary" :icon="Plus" @click="createGroup">新建</el-button>
      </div>
      <div v-if="renaming" class="create-row">
        <el-input
          v-model="renameValue"
          size="small"
          placeholder="新名称"
          autofocus
          @keyup.enter="commitRename"
        />
        <el-button size="small" type="primary" @click="commitRename">确定</el-button>
        <el-button size="small" @click="renaming = null">取消</el-button>
      </div>
    </div>

    <!-- 右栏：选中分组频道管理 -->
    <div class="right-panel">
      <div class="panel-title">
        {{ selectedGroup ? `分组「${selectedGroup}」` : '分组管理' }}
      </div>
      <el-alert v-if="groupsStore.error" type="error" :closable="true" class="alert" show-icon>
        {{ groupsStore.error }}
      </el-alert>
      <template v-if="selectedGroup">
        <div class="channel-panels">
          <!-- 本组频道 -->
          <div class="channel-section">
            <div class="section-title">
              {{ selectedGroup }} 的频道（拖到下方归入"未分组"）
              <el-tag size="small" type="info" effect="plain">{{ groupRows2.length }}</el-tag>
            </div>
            <div class="section-list">
              <VueDraggable
                v-model="groupRows2"
                class="draggable-list"
                :group="{ name: 'channels', pull: true, put: true }"
                :sort="false"
                ghost-class="drag-ghost"
                chosen-class="drag-chosen"
                @change="onGroupChange"
              >
                <div v-for="ch in groupRows2" :key="ch.id" class="ch-row">
                  <span class="mini-logo">
                    <img v-if="ch.logoUrl" :src="ch.logoUrl" alt="" loading="lazy" @error="onLogoError" />
                  </span>
                  <span class="ch-num">{{ ch.number }}</span>
                  <span class="ch-name" :title="ch.name">{{ ch.name }}</span>
                </div>
                <div v-if="groupRows2.length === 0" class="empty-hint">本组暂无频道</div>
              </VueDraggable>
            </div>
          </div>
          <!-- 待分配频道池 -->
          <div class="channel-section">
            <div class="section-title">待分配频道（拖到上方加入分组）</div>
            <el-input
              v-model="poolSearch"
              size="small"
              placeholder="搜索频道名 / 频道号"
              clearable
              class="pool-search"
            />
            <div class="section-list">
              <VueDraggable
                v-model="poolRows"
                class="draggable-list"
                :group="{ name: 'channels', pull: true, put: true }"
                :sort="false"
                ghost-class="drag-ghost"
                chosen-class="drag-chosen"
              >
                <div v-for="ch in poolRows" :key="ch.id" class="ch-row">
                  <span class="mini-logo">
                    <img v-if="ch.logoUrl" :src="ch.logoUrl" alt="" loading="lazy" @error="onLogoError" />
                  </span>
                  <span class="ch-num">{{ ch.number }}</span>
                  <span class="ch-name" :title="ch.name">{{ ch.name }}</span>
                </div>
                <div v-if="poolRows.length === 0" class="empty-hint">没有待分配频道</div>
              </VueDraggable>
            </div>
          </div>
        </div>
      </template>
      <div v-else class="empty-hint">从左侧选择分组进行频道管理</div>
    </div>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  height: 100%;
  gap: 12px;
  padding: 12px 16px;
  min-height: 0;
}
.left-panel {
  width: 300px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
}
.right-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  min-height: 0;
}

/* 窄屏：左右两栏改为上下堆叠，避免固定宽度左栏挤压右栏 */
@media (max-width: 767px) {
  .page {
    flex-direction: column;
    overflow-y: auto;
  }
  .left-panel {
    width: 100%;
    flex-shrink: 1;
  }
}
.panel-title {
  font-size: 15px;
  font-weight: 600;
  color: #374151;
  flex-shrink: 0;
}
.alert {
  flex-shrink: 0;
}
.group-list {
  flex: 1;
  overflow-y: auto;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  min-height: 0;
}
.group-row {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 42px;
  padding: 0 10px;
  border-bottom: 1px solid #f3f4f6;
  cursor: pointer;
  font-size: 13px;
}
.group-row:hover {
  background: #f9fafb;
}
.group-row.active {
  background: #eef2ff;
  border-left: 3px solid #6366f1;
}
.drag-handle {
  cursor: grab;
  color: #9ca3af;
  font-size: 14px;
  user-select: none;
}
.group-name {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.group-count {
  flex-shrink: 0;
}
.create-row {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.channel-panels {
  flex: 1;
  display: flex;
  gap: 12px;
  min-height: 0;
}
.channel-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  min-height: 0;
}
.section-title {
  font-size: 13px;
  color: #6b7280;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.section-list {
  flex: 1;
  overflow-y: auto;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  min-height: 0;
}
.draggable-list {
  content-visibility: auto;
}
.ch-row {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 44px;
  padding: 0 12px;
  border-bottom: 1px solid #f3f4f6;
  font-size: 13px;
}
.ch-row:hover {
  background: #f9fafb;
}
.mini-logo {
  width: 28px;
  height: 28px;
  border-radius: 4px;
  background: #f3f4f6;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.mini-logo img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}
.ch-num {
  width: 48px;
  color: #6b7280;
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}
.ch-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.pool-search {
  flex-shrink: 0;
}
.empty-hint {
  padding: 24px;
  text-align: center;
  color: #9ca3af;
  font-size: 13px;
}
</style>
