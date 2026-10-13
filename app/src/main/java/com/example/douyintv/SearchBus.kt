package com.example.douyintv

/**
 * 搜索关键词总线
 * ===============
 * 手机/电脑浏览器打开电视上的推送页（:8088）输入关键词 → ConfigServer 收到
 * → 通过这里交给前台的 SearchActivity 执行搜索。
 *
 * 为什么走内存总线而不是 Intent：
 *   搜索页可能已经在前台（用户正在看热搜榜），Intent 会重建它；
 *   走总线既能复用当前页面，也能在页面没开时把关键词先存着。
 */
object SearchBus {

    /** 还没被消费的关键词（App 不在搜索页时先攒着） */
    @Volatile
    var pending: String? = null
        private set

    @Volatile
    private var listener: ((String) -> Unit)? = null

    /** SearchActivity 注册（onResume 调）；注册时若有积压关键词会立即回调 */
    fun register(l: (String) -> Unit) {
        listener = l
        val p = pending
        if (!p.isNullOrBlank()) {
            pending = null
            l(p)
        }
    }

    fun unregister() {
        listener = null
    }

    fun push(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        val l = listener
        if (l != null) l(k) else pending = k
    }
}
