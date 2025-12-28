package com.alipay.mobile.mars.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

/**
 * Mars原生播放器基类（抽象类）
 * 继承自FrameLayout，可直接作为视图添加到界面
 */
public abstract class MarsNativePlayer extends FrameLayout {
    public MarsNativePlayer(@NonNull Context context) {
        super(context);
    }

    // 播放控制方法
    public abstract void play();  // 默认播放
    public abstract void play(MarsNativePlayerImpl.PlayCallback callback);  // 带回调播放
    public abstract void play(int repeatCount, MarsNativePlayerImpl.PlayCallback callback);  // 指定重复次数播放
    public abstract void play(int fromFrame, int toFrame, int repeatCount, MarsNativePlayerImpl.PlayCallback callback);  // 指定帧范围播放
    public abstract void stop();  // 停止播放
    public abstract void pause();  // 暂停播放
    public abstract void resume();  // 恢复播放
    public abstract void destroy();  // 销毁播放器，释放资源

    // 状态获取方法
    public abstract float getAspect();  // 获取宽高比
    public abstract PreviewSize getPreviewSize();  // 获取预览尺寸
    public abstract float getDuration();  // 获取总时长（秒）

    // 扩展功能
    public abstract void setEventListener(EventListener listener);  // 设置事件监听
    public abstract void addExtension(MarsNativeExtension extension);  // 添加扩展插件
    public abstract void onFirstScreen(Runnable runnable);  // 首帧渲染回调（仅第一次play时触发）
    public abstract boolean updateVariable(String key, Bitmap bitmap);  // 更新变量（如纹理）

    // 播放回调抽象类
    public static abstract class PlayCallback {
        protected abstract void onPlayFinish();  // 播放完成回调
        protected void onRuntimeError(String errMsg) {}  // 运行时错误回调（可选实现）
    }

    // 事件监听接口
    public interface EventListener {
        void onMessageItem(String itemName, String phrase);  // 接收自定义消息
    }

    // 扩展插件接口
    public interface MarsNativeExtension {
        boolean onSceneDataCreated(long sceneDataPtr, String[] errMsg);  // 场景数据创建回调
        long getCustomPlugin(String[] name);  // 获取自定义插件
        void onDestroy();  // 扩展销毁回调
    }

    // 预览尺寸封装类
    public static class PreviewSize {
        public float width;   // 预览宽度
        public float height;  // 预览高度
        public PreviewSize(float width, float height) {
            this.width = width;
            this.height = height;
        }
    }
}