<script setup lang="ts">
import { useRoute } from 'vue-router'
import { VideoCamera, Folder, Platform, Calendar } from '@element-plus/icons-vue'
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="brand">
        <span class="brand-dot" />
        HyperTV 管理
      </div>
      <nav class="nav">
        <router-link to="/" class="nav-link" :class="{ active: useRoute().path === '/' }">
          <el-icon><VideoCamera /></el-icon><span>频道</span>
        </router-link>
        <router-link to="/groups" class="nav-link" :class="{ active: useRoute().path === '/groups' }">
          <el-icon><Folder /></el-icon><span>分组</span>
        </router-link>
        <router-link to="/sources" class="nav-link" :class="{ active: useRoute().path === '/sources' }">
          <el-icon><Platform /></el-icon><span>直播源</span>
        </router-link>
        <router-link to="/epg" class="nav-link" :class="{ active: useRoute().path === '/epg' }">
          <el-icon><Calendar /></el-icon><span>EPG</span>
        </router-link>
      </nav>
    </header>
    <main class="app-main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.app-header {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 20px;
  height: 52px;
  background: #1f2937;
  color: #f9fafb;
  flex-shrink: 0;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
}
.brand-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #34d399;
}
.nav {
  display: flex;
  gap: 8px;
}
.nav-link {
  padding: 6px 14px;
  border-radius: 6px;
  color: #d1d5db;
  text-decoration: none;
  font-size: 14px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.nav-link:hover {
  color: #fff;
}
.nav-link.active {
  background: #374151;
  color: #fff;
}
.app-main {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: #f3f4f6;
}
</style>
