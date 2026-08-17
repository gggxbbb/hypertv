/** 通用防抖：返回包装函数，停止调用 `delay` ms 后执行一次。 */
export function debounce<A extends unknown[]>(fn: (...args: A) => void, delay: number) {
  let timer: number | null = null
  const wrapped = (...args: A) => {
    if (timer !== null) window.clearTimeout(timer)
    timer = window.setTimeout(() => {
      timer = null
      fn(...args)
    }, delay)
  }
  wrapped.cancel = () => {
    if (timer !== null) {
      window.clearTimeout(timer)
      timer = null
    }
  }
  return wrapped
}
