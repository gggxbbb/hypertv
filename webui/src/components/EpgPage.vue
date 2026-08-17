<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, VideoCamera } from '@element-plus/icons-vue'
import { api } from '@/api/client'
import type { EpgProgram } from '@/api/types'
import { useChannelsStore } from '@/stores/channels'
import { useEpgStore } from '@/stores/epg'
import { usePolling } from '@/composables/usePolling'

const epgStore = useEpgStore()
const channelsStore = useChannelsStore()

// ---- 全局源 ----
const globalUrl = ref('')
const globalSaving = ref(false)

const status = computed(() => epgStore.config?.status ?? null)

async function saveGlobal() {
  const url = globalUrl.value.trim()
  globalSaving.value = true
  try {
    await epgStore.saveGlobalUrl(url)
    globalUrl.value = url
    ElMessage.success(url ? '已保存全局 EPG 源' : '已清除全局 EPG 源')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    globalSaving.value = false
  }
}

async function refreshGlobal() {
  try {
    await epgStore.triggerRefresh()
    ElMessage.success('已开始刷新全局 EPG')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---- 分组级源 ----
const groupUrls = ref<Record<string, string>>({})
const savingGroup = ref<string | null>(null)

function initGroupUrls() {
  const config = epgStore.config
  if (!config) return
  groupUrls.value = Object.fromEntries(config.groups.map((g) => [g.name, g.epgUrl ?? '']))
}

async function saveGroup(groupId: string) {
  const url = (groupUrls.value[groupId] ?? '').trim()
  savingGroup.value = groupId
  try {
    await epgStore.saveGroupUrl(groupId, url)
    ElMessage.success(url ? `已设置分组「${groupId}」的 EPG 源` : `已清除分组「${groupId}」的覆盖`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    savingGroup.value = null
  }
}

async function refreshGroup(groupId: string) {
  try {
    await epgStore.triggerRefresh(groupId)
    ElMessage.success(`已开始刷新分组「${groupId}」的 EPG`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---- 节目预览 ----
const selectedChannelId = ref('')

function todayLocal(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const guideDate = ref(todayLocal())
const guidePrograms = ref<EpgProgram[]>([])
const guideLoading = ref(false)

async function loadGuide() {
  const channelId = selectedChannelId.value
  if (!channelId) return
  guideLoading.value = true
  try {
    const guide = await api.epgGuide(channelId, guideDate.value)
    guidePrograms.value = guide.programs
  } catch (e) {
    ElMessage.error((e as Error).message)
    guidePrograms.value = []
  } finally {
    guideLoading.value = false
  }
}

function onChannelChange() {
  void loadGuide()
}

function formatTime(ts: number): string {
  return new Date(ts).toLocaleString('zh-CN', { hour12: false })
}

function formatClock(ts: number): string {
  return new Date(ts).toLocaleTimeString('zh-CN', { hour12: false })
}

function formatLastUpdate(ts: number | null): string {
  if (!ts) return '从未刷新'
  return formatTime(ts)
}

// ---- 轮询同步（ADR-0003）：5s 拉一次状态，刷新进行中也能及时反映完成 ----
const polling = usePolling(() => epgStore.refresh())

onMounted(async () => {
  await epgStore.refresh()
  globalUrl.value = epgStore.config?.globalUrl ?? ''
  initGroupUrls()
  if (channelsStore.channels.length === 0) {
    await channelsStore.refresh()
  }
})

defineExpose({ refresh: polling.refresh })
</script>

<template>
  <div class="page">
    <!-- 全局 EPG 源 -->
    <div class="panel">
      <div class="panel-head">
        <div class="panel-title">全局 EPG 源</div>
        <div class="status-bar">
          <el-tag v-if="status?.running" type="warning" effect="plain" class="running-tag">刷新中…</el-tag>
          <el-tag v-else-if="status?.stats" type="success" effect="plain">
            命中率 {{ Math.round((status.stats.rate ?? 0) * 100) }}%（{{ status.stats.matched }}/{{ status.stats.total }}）
          </el-tag>
          <el-tag v-else type="info" effect="plain">未刷新</el-tag>
          <span class="last-update">上次刷新：{{ formatLastUpdate(status?.lastUpdate ?? null) }}</span>
        </div>
      </div>
      <el-alert
        v-if="status?.lastError"
        type="error"
        :closable="false"
        show-icon
        class="alert"
        :title="`上次刷新失败：${status.lastError}`"
      />
      <div class="row">
        <el-input
          v-model="globalUrl"
          placeholder="粘贴 XMLTV EPG 源 URL，如 http://192.168.1.10/epg/xmltv.xml"
          clearable
          @keyup.enter="saveGlobal"
        />
        <el-button type="primary" :loading="globalSaving" @click="saveGlobal">保存</el-button>
        <el-button :icon="Refresh" :loading="epgStore.refreshingScope === 'global'" @click="refreshGlobal">
          刷新全局
        </el-button>
      </div>
    </div>

    <!-- 匹配统计（三级） -->
    <div v-if="status?.stats" class="panel">
      <div class="panel-title">频道匹配</div>
      <div class="stats-row">
        <el-tag size="small" type="primary" effect="plain">精确匹配（tvg-id）{{ status.stats.level1 }}</el-tag>
        <el-tag size="small" type="success" effect="plain">忽略大小写 {{ status.stats.level2 }}</el-tag>
        <el-tag size="small" type="warning" effect="plain">频道名匹配 {{ status.stats.level3 }}</el-tag>
        <el-tag size="small" type="info" effect="plain">未匹配 {{ status.stats.unmatched }}</el-tag>
        <div class="rate-bar">
          <div class="rate-fill" :style="{ width: `${Math.round((status.stats.rate ?? 0) * 100)}%` }" />
        </div>
      </div>
    </div>

    <!-- 分组级 EPG 源 -->
    <div class="panel">
      <div class="panel-title">分组级 EPG 源（覆盖全局；未配置的分组使用全局源）</div>
      <el-table :data="epgStore.config?.groups ?? []" size="default" :header-cell-style="{ background: '#f9fafb' }">
        <el-table-column label="分组" prop="name" min-width="140">
          <template #default="{ row }">{{ row.name }}</template>
        </el-table-column>
        <el-table-column label="EPG 源 URL" min-width="280">
          <template #default="{ row }">
            <el-input v-model="groupUrls[row.name]" placeholder="留空 = 回退全局源" clearable />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="right">
          <template #default="{ row }">
            <el-button link :loading="savingGroup === row.name" @click="saveGroup(row.name)">保存</el-button>
            <el-button
              link
              :icon="Refresh"
              :loading="epgStore.refreshingScope === row.name"
              @click="refreshGroup(row.name)"
            >
              刷新
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="(epgStore.config?.groups ?? []).length === 0" class="empty-hint">
        还没有分组，先去「分组」页创建一个吧
      </div>
    </div>

    <!-- 节目预览（选做） -->
    <div class="panel">
      <div class="panel-title">节目预览</div>
      <div class="row">
        <el-select
          v-model="selectedChannelId"
          filterable
          placeholder="选择频道查看当天节目单"
          style="width: 260px"
          @change="onChannelChange"
        >
          <el-option
            v-for="c in channelsStore.channels.filter((c) => c.epgId)"
            :key="c.id"
            :label="`${c.number} ${c.name}`"
            :value="c.id"
          />
        </el-select>
        <el-date-picker v-model="guideDate" type="date" value-format="YYYY-MM-DD" placeholder="日期" @change="loadGuide" />
        <el-button :icon="VideoCamera" :loading="guideLoading" @click="loadGuide">查询</el-button>
      </div>
      <el-table v-if="guidePrograms.length > 0" :data="guidePrograms" size="default" :header-cell-style="{ background: '#f9fafb' }">
        <el-table-column label="开始" width="100">
          <template #default="{ row }">{{ formatClock(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="结束" width="100">
          <template #default="{ row }">{{ formatClock(row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="节目" prop="title" min-width="180" />
        <el-table-column label="分类" prop="category" width="120">
          <template #default="{ row }">{{ row.category || '—' }}</template>
        </el-table-column>
        <el-table-column label="简介" prop="description" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
      </el-table>
      <div v-else-if="selectedChannelId" class="empty-hint">该频道当天暂无节目单（可能未匹配到 EPG 或源里没有数据）</div>
    </div>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px 16px;
  height: 100%;
  overflow-y: auto;
}
.panel {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 12px 14px;
  flex-shrink: 0;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.panel-title {
  font-size: 15px;
  font-weight: 600;
  color: #374151;
  margin-bottom: 8px;
}
.panel-head .panel-title {
  margin-bottom: 0;
}
.status-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.last-update {
  font-size: 12px;
  color: #9ca3af;
}
.alert {
  margin-bottom: 8px;
}
.row {
  display: flex;
  gap: 8px;
}
.stats-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.rate-bar {
  flex: 1;
  min-width: 120px;
  height: 8px;
  border-radius: 4px;
  background: #e5e7eb;
  overflow: hidden;
}
.rate-fill {
  height: 100%;
  background: #34d399;
  transition: width 0.3s;
}
.empty-hint {
  padding: 20px;
  text-align: center;
  color: #9ca3af;
  border: 1px dashed #d1d5db;
  border-radius: 8px;
  font-size: 13px;
}
</style>
