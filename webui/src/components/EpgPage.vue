<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, EditPen, Refresh, VideoCamera } from '@element-plus/icons-vue'
import { api } from '@/api/client'
import type { EpgChannelCandidate, EpgMatchRule, EpgProgram, EpgSource } from '@/api/types'
import { useChannelsStore } from '@/stores/channels'
import { useEpgStore } from '@/stores/epg'
import { usePolling } from '@/composables/usePolling'

const epgStore = useEpgStore()
const channelsStore = useChannelsStore()

const status = computed(() => epgStore.config?.status ?? null)
const sources = computed(() => epgStore.config?.sources ?? [])

// ---- 全局多源：新增 ----
const newUrl = ref('')
const addingSource = ref(false)

async function addSource() {
  const url = newUrl.value.trim()
  if (!url) {
    ElMessage.warning('请输入 EPG 源 URL')
    return
  }
  addingSource.value = true
  try {
    await api.addEpgSource(url)
    newUrl.value = ''
    ElMessage.success('已添加 EPG 源')
    await epgStore.refresh()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    addingSource.value = false
  }
}

// ---- 全局多源：行内编辑 url ----
const editingSourceId = ref<number | null>(null)
const editingSourceUrl = ref('')

function startEditSource(s: EpgSource) {
  editingSourceId.value = s.id
  editingSourceUrl.value = s.url
}

async function commitEditSource() {
  const id = editingSourceId.value
  editingSourceId.value = null
  if (id === null) return
  const url = editingSourceUrl.value.trim()
  if (!url) {
    ElMessage.warning('URL 不能为空')
    return
  }
  try {
    await api.updateEpgSource(id, { url })
    ElMessage.success('已更新 EPG 源')
    await epgStore.refresh()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---- 全局多源：启用/停用 ----
const togglingSourceId = ref<number | null>(null)

async function toggleSourceEnabled(s: EpgSource, enabled: boolean) {
  togglingSourceId.value = s.id
  try {
    await api.updateEpgSource(s.id, { enabled })
    ElMessage.success(enabled ? '已启用 EPG 源' : '已停用 EPG 源')
    await epgStore.refresh()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    togglingSourceId.value = null
  }
}

// ---- 全局多源：删除 ----
async function removeSource(s: EpgSource) {
  try {
    await ElMessageBox.confirm(`确定删除 EPG 源「${s.url}」？`, '删除 EPG 源', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await api.deleteEpgSource(s.id)
    ElMessage.success('已删除 EPG 源')
    await epgStore.refresh()
  } catch {
    /* 取消 */
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

// ---- 分组级源（覆盖全局；留空 = 回退全局源）----
const groupUrls = ref<Record<string, string>>({})
const savingGroup = ref<string | null>(null)

function initGroupUrls() {
  const config = epgStore.config
  if (!config) return
  groupUrls.value = Object.fromEntries(config.groupSources.map((g) => [g.groupName, g.url ?? '']))
}

async function saveGroup(groupName: string) {
  const url = (groupUrls.value[groupName] ?? '').trim()
  savingGroup.value = groupName
  try {
    await epgStore.saveGroupUrl(groupName, url)
    ElMessage.success(url ? `已设置分组「${groupName}」的 EPG 源` : `已清除分组「${groupName}」的覆盖`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    savingGroup.value = null
  }
}

async function refreshGroup(groupName: string) {
  try {
    await epgStore.triggerRefresh(groupName)
    ElMessage.success(`已开始刷新分组「${groupName}」的 EPG`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---- 匹配规则 ----
const rules = ref<EpgMatchRule[]>([])
const rulesLoading = ref(false)

async function loadRules() {
  rulesLoading.value = true
  try {
    rules.value = await api.epgRules()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    rulesLoading.value = false
  }
}

const candidates = ref<EpgChannelCandidate[]>([])
const candidatesLoading = ref(false)

async function loadCandidates() {
  candidatesLoading.value = true
  try {
    candidates.value = await api.epgChannels()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    candidatesLoading.value = false
  }
}

const ruleChannelId = ref('')
const ruleKeyword = ref('')
const ruleType = ref<'prefix' | 'contains'>('prefix')
const ruleAdding = ref(false)

async function addRule() {
  const epgChannelId = ruleChannelId.value
  const keyword = ruleKeyword.value.trim()
  if (!epgChannelId) {
    ElMessage.warning('请选择 EPG 频道')
    return
  }
  if (!keyword) {
    ElMessage.warning('请输入匹配关键字')
    return
  }
  ruleAdding.value = true
  try {
    await api.addEpgRule({ epgChannelId, keyword, ruleType: ruleType.value })
    ruleKeyword.value = ''
    ElMessage.success('已添加规则')
    await loadRules()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    ruleAdding.value = false
  }
}

async function removeRule(r: EpgMatchRule) {
  try {
    await ElMessageBox.confirm(`确定删除规则「${r.keyword} → ${r.epgChannelId}」？`, '删除规则', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await api.deleteEpgRule(r.id)
    ElMessage.success('已删除规则')
    await loadRules()
  } catch {
    /* 取消 */
  }
}

const applyingRules = ref(false)

async function applyRules() {
  applyingRules.value = true
  try {
    const result = await api.applyEpgRules()
    ElMessage.success(`已应用规则，命中 ${result.applied} 个频道`)
    // 应用规则会改写频道 epgId：同步规则命中数、频道列表与候选
    await Promise.all([loadRules(), loadCandidates(), channelsStore.refresh(), epgStore.refresh()])
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    applyingRules.value = false
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

// ---- 轮询同步（ADR-0003）：5s 拉一次配置/规则，刷新进行中也能及时反映完成 ----
const polling = usePolling(async () => {
  await epgStore.refresh()
  await loadRules()
})

onMounted(async () => {
  await epgStore.refresh()
  initGroupUrls()
  await Promise.all([loadRules(), loadCandidates()])
  if (channelsStore.channels.length === 0) {
    await channelsStore.refresh()
  }
})

defineExpose({ refresh: polling.refresh })
</script>

<template>
  <div class="page">
    <!-- 全局 EPG 源（多源） -->
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
          v-model="newUrl"
          placeholder="粘贴 XMLTV EPG 源 URL，如 http://192.168.1.10/epg/xmltv.xml"
          clearable
          @keyup.enter="addSource"
        />
        <el-button type="primary" :loading="addingSource" @click="addSource">添加源</el-button>
        <el-button :icon="Refresh" :loading="epgStore.refreshingScope === 'global'" @click="refreshGlobal">
          刷新全局
        </el-button>
      </div>
      <el-table
        v-if="sources.length > 0"
        :data="sources"
        size="default"
        class="epg-table"
        :header-cell-style="{ background: '#f9fafb' }"
      >
        <el-table-column label="EPG 源 URL" min-width="320">
          <template #default="{ row }: { row: EpgSource }">
            <span v-if="editingSourceId === row.id" class="rename-cell">
              <el-input
                v-model="editingSourceUrl"
                size="small"
                autofocus
                @blur="commitEditSource"
                @keyup.enter="commitEditSource"
              />
            </span>
            <span v-else class="source-url" :title="row.url" @dblclick="startEditSource(row)">
              {{ row.url }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90">
          <template #default="{ row }: { row: EpgSource }">
            <el-switch
              :model-value="row.enabled"
              size="small"
              :loading="togglingSourceId === row.id"
              @change="(v: string | number | boolean) => toggleSourceEnabled(row, Boolean(v))"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="right">
          <template #default="{ row }: { row: EpgSource }">
            <el-button link :title="'编辑 URL'" @click="startEditSource(row)">
              <el-icon><EditPen /></el-icon>
            </el-button>
            <el-button link type="danger" :title="'删除'" @click="removeSource(row)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="empty-hint">还没有全局 EPG 源，先在上方添加一个吧</div>
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
      <el-table
        :data="epgStore.config?.groupSources ?? []"
        size="default"
        :header-cell-style="{ background: '#f9fafb' }"
      >
        <el-table-column label="分组" prop="groupName" min-width="140">
          <template #default="{ row }">{{ row.groupName }}</template>
        </el-table-column>
        <el-table-column label="EPG 源 URL" min-width="280">
          <template #default="{ row }">
            <el-input v-model="groupUrls[row.groupName]" placeholder="留空 = 回退全局源" clearable />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="right">
          <template #default="{ row }">
            <el-button link :loading="savingGroup === row.groupName" @click="saveGroup(row.groupName)">
              保存
            </el-button>
            <el-button
              link
              :icon="Refresh"
              :loading="epgStore.refreshingScope === row.groupName"
              @click="refreshGroup(row.groupName)"
            >
              刷新
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="(epgStore.config?.groupSources ?? []).length === 0" class="empty-hint">
        还没有分组，先去「分组」页创建一个吧
      </div>
    </div>

    <!-- EPG 匹配规则 -->
    <div class="panel">
      <div class="panel-title">EPG 匹配规则</div>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="alert"
        title="规则用于把「同一频道、不同清晰度多个源」归并到同一 EPG 频道。例如 keyword=CCTV-1 会命中 CCTV-1 与 CCTV-1HD；添加并应用规则后再刷新 EPG 即生效。"
      />
      <div class="row">
        <el-select
          v-model="ruleChannelId"
          filterable
          clearable
          placeholder="选择 EPG 频道"
          class="rule-channel"
          :loading="candidatesLoading"
        >
          <el-option
            v-for="c in candidates"
            :key="c.epgId"
            :label="c.channelNames.length > 0 ? `${c.epgId}（${c.channelNames.join('、')}）` : c.epgId"
            :value="c.epgId"
          />
        </el-select>
        <el-input v-model="ruleKeyword" placeholder="匹配关键字，如 CCTV-1" clearable class="rule-keyword" @keyup.enter="addRule" />
        <el-select v-model="ruleType" class="rule-type">
          <el-option label="前缀匹配" value="prefix" />
          <el-option label="包含匹配" value="contains" />
        </el-select>
        <el-button type="primary" :loading="ruleAdding" @click="addRule">添加规则</el-button>
        <el-button type="warning" :loading="applyingRules" @click="applyRules">立即应用规则</el-button>
      </div>
      <el-table
        v-if="rules.length > 0"
        :data="rules"
        size="default"
        class="epg-table"
        :header-cell-style="{ background: '#f9fafb' }"
      >
        <el-table-column label="EPG 频道 id" prop="epgChannelId" min-width="200" show-overflow-tooltip />
        <el-table-column label="匹配关键字" prop="keyword" min-width="160" show-overflow-tooltip />
        <el-table-column label="类型" width="110">
          <template #default="{ row }: { row: EpgMatchRule }">
            <el-tag size="small" :type="row.ruleType === 'prefix' ? 'primary' : 'success'" effect="plain">
              {{ row.ruleType === 'prefix' ? '前缀匹配' : '包含匹配' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="命中频道" prop="matchedCount" width="100" align="right">
          <template #default="{ row }: { row: EpgMatchRule }">{{ row.matchedCount }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="right">
          <template #default="{ row }: { row: EpgMatchRule }">
            <el-button link type="danger" :title="'删除'" @click="removeRule(row)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="empty-hint">还没有匹配规则，添加一条试试</div>
    </div>

    <!-- 节目预览 -->
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
  margin-bottom: 8px;
}
.epg-table {
  margin-top: 4px;
}
.rename-cell {
  display: block;
}
.source-url {
  cursor: text;
  color: #374151;
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
.rule-channel {
  width: 280px;
}
.rule-keyword {
  width: 200px;
}
.rule-type {
  width: 130px;
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
