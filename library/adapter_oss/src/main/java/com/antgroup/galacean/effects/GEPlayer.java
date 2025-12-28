package com.antgroup.galacean.effects;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import java.util.Map;

/**
 * 特效播放器主类
 * 作为GEPlayerImpl的包装器，提供统一的播放器接口并继承自FrameLayout用于视图展示
 * 采用桥接模式，将业务逻辑委托给GEPlayerImpl实现
 *
 * 【C++层核心函数说明】
 * 底层基于C++渲染引擎实现，主要C++函数包括：
 * - nativeCreatePlayer(): 创建C++层播放器实例
 * - nativeLoadScene(): 加载特效场景资源(C++层解析GLTF/JSON等格式)
 * - nativePlay(int repeatCount): 启动特效播放(C++层控制动画时序)
 * - nativeRenderFrame(): 逐帧渲染特效(C++层OpenGL/Metal/DirectX实现)
 * - nativeSetVariable(String key, String value): 设置动态参数(C++层实时更新着色器变量)
 * - nativeDestroy(): 销毁C++层资源(释放GPU纹理/缓冲区等)
 * 注：Java层通过JNI调用上述C++函数，实现高性能跨平台渲染
 */
public class GEPlayer extends FrameLayout {

    // 特效播放器的具体实现类，负责实际的播放逻辑（包含JNI调用）
    private final GEPlayerImpl mImpl;

    /**
     * 构造函数
     * @param context 上下文对象，用于系统服务访问和资源加载
     * @param params 播放器参数配置，包含URL、降级图片等资源信息
     */
    public GEPlayer(@NonNull Context context, GEPlayerParams params) {
        super(context);

        // 初始化播放器实现类（内部会通过JNI调用nativeCreatePlayer）
        mImpl = new GEPlayerImpl(params);
    }

    /**
     * 加载特效场景资源
     * @param callback 加载结果回调，通知加载成功或失败状态
     * 【对应C++函数】nativeLoadScene(Context context, String url, Callback callback)
     */
    public void loadScene(Callback callback) {
        if (mImpl != null) {
            // 委托给实现类加载场景资源（内部调用nativeLoadScene）
            mImpl.loadScene(getContext(), callback);

            // 获取播放器视图并添加到当前FrameLayout中
            View view = mImpl.getView();
            if (view != null) {
                // 设置视图充满整个父容器
                addView(view, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
            }
        }
    }

    /**
     * 播放特效（从头开始）
     * @param repeatCount 重复播放次数，0表示无限循环
     * @param callback 播放状态回调
     * 【对应C++函数】nativePlay(int repeatCount, Callback callback)
     */
    public void play(int repeatCount, GEPlayer.Callback callback) {
        if (mImpl != null) {
            mImpl.play(repeatCount, callback);  // 内部调用nativePlay
        }
    }

    /**
     * 播放特效（指定帧范围）
     * @param fromFrame 起始帧索引
     * @param toFrame 结束帧索引
     * @param repeatCount 重复播放次数，0表示无限循环
     * @param callback 播放状态回调
     * 【对应C++函数】nativePlayRange(int fromFrame, int toFrame, int repeatCount, Callback callback)
     */
    public void play(int fromFrame, int toFrame, int repeatCount, GEPlayer.Callback callback) {
        if (mImpl != null) {
            mImpl.play(fromFrame, toFrame, repeatCount, callback);  // 内部调用nativePlayRange
        }
    }

    /** 暂停当前播放【对应C++函数】nativePause() */
    public void pause() {
        if (mImpl != null) {
            mImpl.pause();  // 内部调用nativePause
        }
    }

    /** 恢复已暂停的播放【对应C++函数】nativeResume() */
    public void resume() {
        if (mImpl != null) {
            mImpl.resume();  // 内部调用nativeResume
        }
    }

    /** 停止播放并重置到初始状态【对应C++函数】nativeStop() */
    public void stop() {
        if (mImpl != null) {
            mImpl.stop();  // 内部调用nativeStop
        }
    }

    /** 销毁播放器，释放所有资源【对应C++函数】nativeDestroy() */
    public void destroy() {
        if (mImpl != null) {
            mImpl.destroy();  // 内部调用nativeDestroy
        }
    }

    /**
     * 获取特效宽高比
     * @return 宽高比值，加载失败时返回-1
     * 【对应C++函数】nativeGetAspect()
     */
    public float getAspect() {
        if (mImpl != null) {
            return mImpl.getAspect();  // 内部调用nativeGetAspect
        }
        return -1;
    }

    /**
     * 获取特效总帧数
     * @return 帧数总数，加载失败时返回-1
     * 【对应C++函数】nativeGetFrameCount()
     */
    public int getFrameCount() {
        if (mImpl != null) {
            return mImpl.getFrameCount();  // 内部调用nativeGetFrameCount
        }
        return -1;
    }

    /**
     * 播放器参数配置类
     * 封装播放器初始化所需的所有配置信息
     * 【对应C++函数】nativeInitParams(GEPlayerParams params)
     */
    public static class GEPlayerParams {
        public String url;                    // 特效资源URL地址（C++层用于定位GLTF/JSON文件）
        public Bitmap downgradeImage;         // 降级显示图片（加载过程中或失败时显示，C++层textureId映射）
        public Map<String, String> variables; // 字符串类型的变量参数映射表（C++层传递给着色器uniform）
        public Map<String, Bitmap> variablesBitmap; // 位图类型的变量参数映射表（C++层生成纹理）
    }

    /**
     * 播放器回调接口
     * 用于通知播放器各种状态变化和操作结果
     * 【对应C++函数】nativeOnResult(bool success, const char* errorMsg)
     */
    public interface Callback {
        /**
         * 操作结果回调
         * @param success 是否成功
         * @param errorMsg 错误信息，成功时为null或空字符串
         */
        void onResult(boolean success, String errorMsg);
    }
}