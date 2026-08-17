<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, EditPen, Refresh, UploadFilled } from '@element-plus/icons-vue'
import type { UploadFile, UploadInstance } from 'element-plus'
import { api } from '@/api/client'
import type { ImportPreview, ImportResult, PlaylistDTO } from '@/api/types'
import { usePlaylistsStore } from '@/stores/playlists'
import { usePolling } from '@/composables/usePolling'

const playlistsStore = usePlaylistsStore()

// ---- 导入区：URL ----
const urlInput = ref('')
const urlPreviewing = ref(false)
const urlPreview = ref<ImportPreview | null>(null)
const urlImporting = ref(false)

async function previewUrl() {
  const url = urlInput.value.trim()
  if (!url) {
    ElMessage.warning('请输入 M3U/M3U8 URL')
    return
  }
  urlPreviewing.value = true
  urlPreview.value = null
  try {
    urlPreview.value = await api.previewImportUrl(url)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    urlPreviewing.value = false
  }
}

async function confirmImportUrl() {
  const url = urlInput.value.trim()
  urlImporting.value = true
  try {
    const result = await api.importUrl(url)
    showImportResult(result)
    urlPreview.value = null
    urlInput.value = ''
    await playlistsStore.refresh()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    urlImporting.value = false
  }
}

// ---- 导入区：文件上传 ----
const fileInput = ref<File | null>(null)
const filePreviewing = ref(false)
const filePreview = ref<ImportPreview | null>(null)
const fileImporting = ref(false)
const fileUploadRef = ref<UploadInstance | null>(null)

function onFileSelected(uploadFile: UploadFile) {
  const raw = uploadFile.raw
  if (!raw) return
  fileInput.value = raw
  filePreview.value = null
  void previewFile(raw)
}

async function previewFile(file: File) {
  filePreviewing.value = true
  try {
    // 传文件名作 sourceName，与确认导入一致：同名源时后端返回增量预测（冲突提示数字）
    filePreview.value = await api.previewImportFile(file, file.name)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    filePreviewing.value = false
  }
}

async function confirmImportFile() {
  const file = fileInput.value
  if (!file) return
  fileImporting.value = true
  try {
    // 前端传文件名作 sourceName：重复上传同名文件时后端按 (type=file, name) 增量合并
    const result = await api.importFile(file, file.name)
    showImportResult(result)
    filePreview.value = null
    fileInput.value = null
    // 清空 el-upload 内部 fileList，否则 limit=1 下无法再次选择同一文件
    fileUploadRef.value?.clearFiles()
    await playlistsStore.refresh()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    fileImporting.value = false
  }
}

function cancelFilePreview() {
  filePreview.value = null
  fileInput.value = null
  // 清空 el-upload 内部 fileList：否则取消后再次拖入同一文件被 limit=1 拒绝（on-change 不触发）
  fileUploadRef.value?.clearFiles()
}

function showImportResult(r: ImportResult) {
  const parts = [`新增 ${r.imported} 个频道`]
  if (r.updated > 0) parts.push(`更新 ${r.updated} 个`)
  if (r.hidden > 0) parts.push(`隐藏 ${r.hidden} 个`)
  ElMessage.success(`导入完成：${parts.join('，')}`)
}

// ---- 多源列表：重命名 / 刷新 / 删除 ----
const renamingId = ref<string | null>(null)
const renameValue = ref('')
const refreshingId = ref<string | null>(null)

function startRename(p: PlaylistDTO) {
  renamingId.value = p.id
  renameValue.value = p.name
}

async function commitRename() {
  const id = renamingId.value
  if (!id) return
  const name = renameValue.value.trim()
  renamingId.value = null
  if (!name) {
    ElMessage.warning('名称不能为空')
    return
  }
  try {
    await playlistsStore.rename(id, name)
    ElMessage.success('已重命名')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function refreshSource(p: PlaylistDTO) {
  refreshingId.value = p.id
  try {
    const result = await playlistsStore.refreshSource(p.id)
    ElMessage.success(`刷新完成：新增 ${result.imported}，更新 ${result.updated}，隐藏 ${result.hidden}`)
    await playlistsStore.refresh()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    refreshingId.value = null
  }
}

async function removeSource(p: PlaylistDTO) {
  try {
    await ElMessageBox.confirm(
      `确定删除直播源「${p.name}」？将删除该源的全部 ${p.channelCount} 个频道（含收藏），不可恢复。`,
      '删除直播源',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
    await playlistsStore.remove(p.id)
    ElMessage.success('已删除直播源')
  } catch {
    /* 取消 */
  }
}

function formatTime(ts: number): string {
  if (!ts) return '—'
  return new Date(ts).toLocaleString('zh-CN', { hour12: false })
}

// ---- 轮询同步（5s）+ 操作后立即拉取（ADR-0003）----
const polling = usePolling(() => playlistsStore.refresh())

defineExpose({ refresh: polling.refresh })
</script>

<template>
  <div class="page">
    <!-- 导入区 -->
    <div class="import-panel">
      <div class="panel-title">导入直播源</div>
      <el-tabs type="border-card">
        <el-tab-pane label="URL 导入">
          <div class="import-row">
            <el-input
              v-model="urlInput"
              placeholder="粘贴 M3U/M3U8 URL，如 http://192.168.1.10/live.m3u"
              clearable
              @keyup.enter="previewUrl"
            >
              <template #prefix>🔗</template>
            </el-input>
            <el-button type="primary" :loading="urlPreviewing" @click="previewUrl">解析预览</el-button>
          </div>
          <div v-if="urlPreview" class="preview-card">
            <div class="preview-head">
              <span class="preview-source-name">{{ urlPreview.sourceName }}</span>
              <el-tag size="small" type="info" effect="plain">{{ urlPreview.encoding }}</el-tag>
            </div>
            <div class="preview-stats">
              <el-tag size="small" type="primary" effect="plain">共 {{ urlPreview.total }} 个频道</el-tag>
              <template v-if="urlPreview.groups.length > 0">
                <el-tag
                  v-for="g in urlPreview.groups.slice(0, 6)"
                  :key="g"
                  size="small"
                  type="success"
                  effect="plain"
                >
                  {{ g }}
                </el-tag>
                <span v-if="urlPreview.groups.length > 6" class="group-more">
                  +{{ urlPreview.groups.length - 6 }} 个分组
                </span>
              </template>
            </div>
            <div
              v-if="
                urlPreview.imported != null &&
                urlPreview.updated != null &&
                urlPreview.hidden != null &&
                urlPreview.existingChannelCount != null
              "
              class="conflict-hint"
            >
              已存在直播源（现有 {{ urlPreview.existingChannelCount }} 个频道）：
              将新增 {{ urlPreview.imported }} 个、更新 {{ urlPreview.updated }} 个、隐藏 {{ urlPreview.hidden }} 个
            </div>
            <div class="preview-actions">
              <el-button type="primary" :loading="urlImporting" @click="confirmImportUrl">确认导入</el-button>
              <el-button @click="urlPreview = null">取消</el-button>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="文件上传">
          <el-upload
            ref="fileUploadRef"
            drag
            :auto-upload="false"
            :show-file-list="false"
            :limit="1"
            accept=".m3u,.m3u8,.txt"
            :on-change="onFileSelected"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖拽 .m3u 文件到此处，或<em>点击选择</em></div>
          </el-upload>
          <div v-if="fileInput" class="file-chosen">已选择：{{ fileInput.name }}</div>
          <div v-if="filePreview" class="preview-card">
            <div class="preview-head">
              <span class="preview-source-name">{{ filePreview.sourceName }}</span>
              <el-tag size="small" type="info" effect="plain">{{ filePreview.encoding }}</el-tag>
            </div>
            <div class="preview-stats">
              <el-tag size="small" type="primary" effect="plain">共 {{ filePreview.total }} 个频道</el-tag>
              <template v-if="filePreview.groups.length > 0">
                <el-tag
                  v-for="g in filePreview.groups.slice(0, 6)"
                  :key="g"
                  size="small"
                  type="success"
                  effect="plain"
                >
                  {{ g }}
                </el-tag>
                <span v-if="filePreview.groups.length > 6" class="group-more">
                  +{{ filePreview.groups.length - 6 }} 个分组
                </span>
              </template>
            </div>
            <div
              v-if="
                filePreview.imported != null &&
                filePreview.updated != null &&
                filePreview.hidden != null &&
                filePreview.existingChannelCount != null
              "
              class="conflict-hint"
            >
              已存在同名直播源（现有 {{ filePreview.existingChannelCount }} 个频道）：
              将新增 {{ filePreview.imported }} 个、更新 {{ filePreview.updated }} 个、隐藏 {{ filePreview.hidden }} 个
            </div>
            <div class="preview-actions">
              <el-button type="primary" :loading="fileImporting" @click="confirmImportFile">确认导入</el-button>
              <el-button @click="cancelFilePreview">取消</el-button>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 多源列表 -->
    <div class="list-panel">
      <div class="list-head">
        <div class="panel-title">直播源列表</div>
        <div class="list-tools">
          <el-alert v-if="playlistsStore.error" type="error" :closable="true" class="alert" show-icon>
            {{ playlistsStore.error }}
          </el-alert>
          <el-button :icon="Refresh" :loading="playlistsStore.loading" @click="polling.refresh">刷新</el-button>
        </div>
      </div>
      <div v-if="playlistsStore.playlists.length === 0" class="empty-hint">
        还没有直播源，先导入一个吧
      </div>
      <el-table
        v-else
        :data="playlistsStore.playlists"
        class="source-table"
        size="default"
        :header-cell-style="{ background: '#f9fafb' }"
      >
        <el-table-column label="名称" min-width="180">
          <template #default="{ row }: { row: PlaylistDTO }">
            <span v-if="renamingId === row.id" class="rename-cell">
              <el-input
                v-model="renameValue"
                size="small"
                autofocus
                @blur="commitRename"
                @keyup.enter="commitRename"
              />
            </span>
            <span v-else class="source-name" :title="row.name">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }: { row: PlaylistDTO }">
            <el-tag size="small" :type="row.type === 'file' ? 'warning' : 'success'" effect="plain">
              {{ row.type === 'file' ? '文件源' : 'URL 源' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="地址" min-width="200" show-overflow-tooltip>
          <template #default="{ row }: { row: PlaylistDTO }">
            <span class="source-url">{{ row.type === 'file' ? '本地文件' : row.url }}</span>
          </template>
        </el-table-column>
        <el-table-column label="频道数" width="90" align="right">
          <template #default="{ row }: { row: PlaylistDTO }">{{ row.channelCount }}</template>
        </el-table-column>
        <el-table-column label="上次导入" width="170">
          <template #default="{ row }: { row: PlaylistDTO }">{{ formatTime(row.lastImportedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="right">
          <template #default="{ row }: { row: PlaylistDTO }">
            <el-button link :title="'重命名'" @click="startRename(row)">
              <el-icon><EditPen /></el-icon>
            </el-button>
            <el-button
              link
              :title="'刷新'"
              :loading="refreshingId === row.id"
              @click="refreshSource(row)"
            >
              <el-icon><Refresh /></el-icon>
            </el-button>
            <el-button link type="danger" :title="'删除'" @click="removeSource(row)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 12px 16px;
  gap: 12px;
  min-height: 0;
}
.import-panel {
  flex-shrink: 0;
}
.panel-title {
  font-size: 15px;
  font-weight: 600;
  color: #374151;
  margin-bottom: 8px;
}
.import-row {
  display: flex;
  gap: 8px;
}
.preview-card {
  margin-top: 12px;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.preview-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.preview-source-name {
  font-weight: 600;
  font-size: 14px;
  color: #111827;
}
.preview-stats {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.group-more {
  font-size: 12px;
  color: #9ca3af;
}
.conflict-hint {
  padding: 8px 10px;
  border-radius: 6px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  color: #92400e;
  font-size: 13px;
}
.preview-actions {
  display: flex;
  gap: 8px;
}
.file-chosen {
  margin-top: 8px;
  font-size: 13px;
  color: #374151;
}
.list-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  flex-shrink: 0;
}
.list-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}
.alert {
  flex-shrink: 0;
}
.source-table {
  flex: 1;
  min-height: 0;
}
.rename-cell {
  display: block;
}
.source-name {
  font-weight: 500;
}
.source-url {
  color: #6b7280;
  font-size: 12px;
}
.empty-hint {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed #d1d5db;
  border-radius: 8px;
  background: #fff;
  color: #9ca3af;
  font-size: 14px;
}
</style>
