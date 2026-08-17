import { onMounted, onUnmounted } from 'vue'

/**
 * 多标签页同步（ADR-0003）：5 秒轮询 + 手动立即拉取，无 WebSocket。
 */
export function usePolling(fn: () => void | Promise<void>, intervalMs = 5000) {
  let timer: number | null = null
  let inFlight = false

  async function run() {
    if (inFlight) return
    inFlight = true
    try {
      await fn()
    } finally {
      inFlight = false
    }
  }

  onMounted(() => {
    void run()
    timer = window.setInterval(() => void run(), intervalMs)
  })

  onUnmounted(() => {
    if (timer !== null) window.clearInterval(timer)
  })

  return { refresh: () => void run() }
}
