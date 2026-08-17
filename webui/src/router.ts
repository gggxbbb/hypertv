import { createRouter, createWebHashHistory } from 'vue-router'
import ChannelListPage from './components/ChannelListPage.vue'
import GroupManagerPage from './components/GroupManagerPage.vue'
import SourcesPage from './components/SourcesPage.vue'

// hash 模式：SPA 子路由刷新时无需服务器回退到 index.html（Ktor 静态托管简单可靠）
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: ChannelListPage, meta: { title: '频道' } },
    { path: '/groups', component: GroupManagerPage, meta: { title: '分组' } },
    { path: '/sources', component: SourcesPage, meta: { title: '直播源' } },
  ],
})
