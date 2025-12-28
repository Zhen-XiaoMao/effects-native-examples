package com.example.galacean_effects

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.antgroup.galacean.effects.GEPlayer
import java.lang.ref.WeakReference
import kotlin.random.Random

class GEPlayDemo : Activity() {

    // 使用 Map 存储播放器，Key 为索引，更直观且无需处理 List 的占位 null 值
    private val effectPlayers = mutableMapOf<Int, GEPlayer>()

    // 使用 Kotlin 官方推荐的随机数单例
    private val randomGenerator = Random.Default

    // 使用 by lazy 委托：只有在第一次调用 mainHandler 时才会初始化，节省资源
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // 语法糖：使用 map 快速将 ID 数组转换为 View 列表
    private val effectContainers by lazy {
        intArrayOf(
            R.id.effect_0_container, R.id.effect_1_container, R.id.effect_2_container, R.id.effect_3_container,
            R.id.effect_4_container, R.id.effect_5_container, R.id.effect_6_container, R.id.effect_7_container
        ).map { findViewById<FrameLayout>(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 使用 apply 作用域函数：在对象范围内执行多个配置操作，代码更紧凑
        window.apply {
            requestFeature(Window.FEATURE_NO_TITLE)
            setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        setContentView(R.layout.activity_main)

        // 设置按钮点击事件
        findViewById<Button>(R.id.btn_single).setOnClickListener { playSingleEffect(0) }
        findViewById<Button>(R.id.btn_all).setOnClickListener { playAllEffects() }
        findViewById<Button>(R.id.btn_stop_all).setOnClickListener { stopAllEffects() }

        // 语法糖：forEachIndexed 自动处理循环索引
        effectContainers.forEachIndexed { index, container ->
            container.setOnClickListener { playSingleEffect(index) }
        }

        // 延迟 1 秒后自动播放
        mainHandler.postDelayed({ playAllEffects() }, 1000)
    }

    /**
     * 创建并初始化特效播放器
     * @param effectIndex 特效索引，对应EFFECT_URLS中的位置
     */
    private fun createEffectPlayer(effectIndex: Int) {
        // 安全获取特效URL，防止索引越界导致崩溃
        val url = EFFECT_URLS.getOrNull(effectIndex) ?: return

        // 如果该位置已有播放器实例，先销毁避免资源泄漏
        effectPlayers[effectIndex]?.destroy()

        // 配置播放器参数
        val playerParameters = GEPlayer.GEPlayerParams().apply {
            this.url = url  // 设置特效资源URL

            // 创建降级显示的随机颜色图片（128x128 ARGB格式）
            // also作用域函数：在创建Bitmap后立即进行Canvas绘制，最后返回Bitmap对象
            this.downgradeImage = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { bitmap ->
                Canvas(bitmap).drawColor(getRandomColor())  // 填充随机背景色
            }
        }

        // 创建播放器实例并保存到集合中
        val effectPlayer = GEPlayer(this, playerParameters)
        effectPlayers[effectIndex] = effectPlayer

        // 使用弱引用持有Activity，防止异步回调导致的内存泄漏
        val weakActivity = WeakReference(this)

        // 加载特效场景，完成后进行UI绑定
        effectPlayer.loadScene { loadSuccess, errorMessage ->
            // 从弱引用获取Activity，若已被回收则直接返回
            val activity = weakActivity.get() ?: return@loadScene

            // 加载失败时记录日志并提示用户
            if (!loadSuccess) {
                Log.e(TAG, "加载失败: ${EFFECT_DISPLAY_NAMES[effectIndex]}, $errorMessage")
                activity.toast("${EFFECT_DISPLAY_NAMES[effectIndex]} 加载失败")
                return@loadScene
            }

            // 安全获取容器并绑定播放器视图
            // let作用域函数：仅在container不为空时执行内部逻辑
            activity.effectContainers.getOrNull(effectIndex)?.let { container ->
                container.removeAllViews()  // 清空容器原有内容

                // 应用自定义布局到容器（扩展函数）
                effectPlayer.applyLayout(container)

                // 将播放器添加到容器中进行显示
                container.addView(effectPlayer)
            }
        }
    }

    /**
     * Kotlin 扩展函数：直接为 GEPlayer 类添加布局计算逻辑
     * 使主业务逻辑更清晰
     */
    private fun GEPlayer.applyLayout(container: FrameLayout) {
        val cw = if (container.width > 0) container.width.toFloat() else 140f
        val ch = if (container.height > 0) container.height.toFloat() else 140f

        // 语法糖：使用对偶 (Pair) 和解构声明快速赋值宽高
        val (layoutWidth, layoutHeight) = if (cw / aspect < ch) {
            cw to (cw / aspect)
        } else {
            (ch * aspect) to ch
        }

        this.x = (cw - layoutWidth) / 2
        this.y = (ch - layoutHeight) / 2
        this.layoutParams = FrameLayout.LayoutParams(layoutWidth.toInt(), layoutHeight.toInt())
        this.setBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * 播放单个特效
     */
    private fun playSingleEffect(effectIndex: Int = 0) {
        if (effectIndex !in EFFECT_URLS.indices) return

        if (effectPlayers[effectIndex] == null) {
            createEffectPlayer(effectIndex)
            // 初次创建需要等待加载，延迟播放
            mainHandler.postDelayed({ actuallyPlayEffect(effectIndex) }, 2000)
        } else {
            actuallyPlayEffect(effectIndex)
        }
    }

    /**
     * 执行播放动作
     */
    private fun actuallyPlayEffect(effectIndex: Int) {
        effectPlayers[effectIndex]?.apply {
            play(0) { _, _ ->
                toast("${EFFECT_DISPLAY_NAMES[effectIndex]} 播放完成")
            }
            toast("开始播放: ${EFFECT_DISPLAY_NAMES[effectIndex]}")
        }
    }

    /**
     * 播放所有特效
     */
    private fun playAllEffects() {
        toast("开始加载所有效果...")

        // 遍历所有特效索引，创建未初始化的播放器
        EFFECT_URLS.indices.forEach { index ->
            if (effectPlayers[index] == null) createEffectPlayer(index)
        }

        // 延迟2.5秒检查播放状态
        mainHandler.postDelayed({
            // 统计并尝试播放所有播放器
            val hasPlayed = effectPlayers.values.count { player ->
                player.play(0) { _, _ -> }  // 播放特效（忽略回调）
                true  // 计数+1
            } > 0

            toast(if (hasPlayed) "开始播放所有效果" else "没有可播放的效果")
        }, 2500)
    }

    /**
     * 简化 Toast 调用的辅助函数
     */
    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 获取随机颜色
     */
    private fun getRandomColor(): Int {
        val colorPalette = intArrayOf(
            Color.parseColor("#FF6B6B"), Color.parseColor("#4ECDC4"),
            Color.parseColor("#45B7D1"), Color.parseColor("#96CEB4"),
            Color.parseColor("#FFEAA7"), Color.parseColor("#DDA0DD"),
            Color.parseColor("#98D8C8"), Color.parseColor("#F7DC6F")
        )
        // 语法糖：集合直接调用 random() 获取随机元素
        return colorPalette.random(randomGenerator)
    }

    // 语法糖：使用 super.onPause().also { ... } 在父类方法执行后紧跟逻辑
    override fun onPause() = super.onPause().also {
        effectPlayers.values.forEach { it.pause() }
    }

    override fun onResume() = super.onResume().also {
        effectPlayers.values.forEach { it.resume() }
    }

    private fun stopAllEffects() {
        effectPlayers.values.forEach { it.pause() }
        toast("已停止所有效果")
    }

    override fun onDestroy() {
        super.onDestroy()
        // 退出时彻底销毁播放器并清空集合
        effectPlayers.values.forEach { it.destroy() }
        effectPlayers.clear()
    }

    companion object {
        private const val TAG = "MultiEffectPlay"

        // 特效资源链接
        private val EFFECT_URLS = arrayOf(
            "https://mdn.alipayobjects.com/mars/afts/file/A*WL2TTZ0DBGoAAAAAAAAAAAAAARInAQ",
            "https://mdn.alipayobjects.com/mars/afts/file/A*D6TbS5ax2TgAAAAAAAAAAAAAARInAQ",
            "https://mdn.alipayobjects.com/mars/afts/file/A*TazWSbYr84wAAAAAAAAAAAAAARInAQ",
            "https://mdn.alipayobjects.com/mars/afts/file/A*e7_FTLA_REgAAAAAAAAAAAAAARInAQ",
            "https://mdn.alipayobjects.com/mars/afts/file/A*D4ixTaUS-HoAAAAAAAAAAAAADlB4AQ",
            "https://mdn.alipayobjects.com/mars/afts/file/A*OW2VSKK3bWIAAAAAAAAAAAAADlB4AQ",
            "https://mdn.alipayobjects.com/mars/afts/file/A*wIkMSokvwCgAAAAAAAAAAAAAARInAQ",
            "https://mdn.alipayobjects.com/mars/afts/file/A*VtHiR4iOuxYAAAAAAAAAAAAAARInAQ"
        )

        // 特效显示名称
        private val EFFECT_DISPLAY_NAMES = arrayOf(
            "心形粒子", "闪电球", "年兽爆炸", "双十一鼓掌",
            "敬业福弹卡", "七夕倒计时", "天猫618", "年度账单"
        )
    }
}