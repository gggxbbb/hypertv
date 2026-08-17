<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { VideoCamera, Folder, Platform, Calendar, Menu } from '@element-plus/icons-vue'

const route = useRoute()
const routePath = computed(() => route.path)

/** 移动端导航抽屉开合（仅窄屏可见，宽屏下恒隐藏） */
const menuOpen = ref(false)

/** 导航链接定义（桌面横向导航 / 移动端抽屉共用同一份） */
const navItems = [
  { to: '/', label: '频道', icon: VideoCamera },
  { to: '/groups', label: '分组', icon: Folder },
  { to: '/sources', label: '直播源', icon: Platform },
  { to: '/epg', label: 'EPG', icon: Calendar },
]
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="brand">
        <span class="brand-dot" />
        HyperTV 管理
      </div>
      <!-- 桌面/宽屏横向导航 -->
      <nav class="nav">
        <router-link
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="nav-link"
          :class="{ active: routePath === item.to }"
        >
          <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
        </router-link>
      </nav>
      <!-- 移动端汉堡按钮（窄屏显示） -->
      <button
        class="nav-toggle"
        :aria-expanded="menuOpen"
        aria-label="打开菜单"
        @click="menuOpen = !menuOpen"
      >
        <el-icon :size="20"><Menu /></el-icon>
      </button>
      <!-- 移动端抽屉导航：点击链接或空白处关闭 -->
      <nav v-show="menuOpen" class="nav-drawer" @click="menuOpen = false">
        <router-link
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="nav-link"
          :class="{ active: routePath === item.to }"
        >
          <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
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
  position: relative; /* 移动端抽屉绝对定位锚点 */
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
  white-space: nowrap;
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
  white-space: nowrap;
}
.nav-link:hover {
  color: #fff;
}
.nav-link.active {
  background: #374151;
  color: #fff;
}
/* 汉堡按钮：默认隐藏，窄屏显示 */
.nav-toggle {
  display: none;
  margin-left: auto;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  border-radius: 6px;
  color: #d1d5db;
  cursor: pointer;
  padding: 0;
}
.nav-toggle:hover {
  background: #374151;
  color: #fff;
}
/* 移动端抽屉：默认隐藏（宽屏即使 menuOpen 也不显示），窄屏由 v-show 控制 */
.nav-drawer {
  display: none;
}
.app-main {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: #f3f4f6;
}

/* ---- 移动端适配（<768px） ---- */
@media (max-width: 767px) {
  .app-header {
    gap: 12px;
    padding: 0 12px;
  }
  .nav {
    display: none;
  }
  .nav-toggle {
    display: inline-flex;
  }
  .nav-drawer {
    position: absolute;
    top: 100%;
    left: 0;
    right: 0;
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 8px 12px 12px;
    background: #1f2937;
    box-shadow: 0 8px 16px rgba(0, 0, 0, 0.3);
    z-index: 50;
  }
  .nav-drawer .nav-link {
    padding: 12px 14px;
    font-size: 15px;
  }
}
</style>
