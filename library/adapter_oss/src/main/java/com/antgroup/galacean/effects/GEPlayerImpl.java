package com.antgroup.galacean.effects;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import com.alipay.mobile.mars.adapter.ContextUtil;
import com.alipay.mobile.mars.adapter.TaskScheduleUtil;
import com.alipay.mobile.mars.player.MarsNativeBuilder;
import com.alipay.mobile.mars.player.MarsNativePlayer;
import com.alipay.mobile.mars.adapter.LogUtil;

import java.lang.ref.WeakReference;

/**
 * GEPlayer的具体实现类（内部类）
 * 负责对接Mars原生播放器SDK（MarsNativePlayer），处理资源加载、播放控制等核心逻辑
 * 采用桥接模式，隔离上层GEPlayer与底层Mars SDK的直接依赖
 */
class GEPlayerImpl {
    private final static String TAG = "GEPlayerImpl";

    // 播放器初始化参数（从上层GEPlayer传递）
    private GEPlayer.GEPlayerParams mParams;

    // Mars原生播放器实例（核心播放能力由Mars SDK提供）
    private MarsNativePlayer mPlayer = null;

    GEPlayerImpl(GEPlayer.GEPlayerParams params) {
        mParams = params;
    }

    /**
     * 获取播放器视图（供上层GEPlayer添加到视图树）
     * @return MarsNativePlayer实例（本身继承自View，可直接作为渲染视图）
     */
    View getView() {
        return mPlayer;
    }

    /**
     * 加载特效场景资源（核心初始化逻辑）
     * 流程：参数校验 → 初始化Mars SDK环境 → 构建播放器 → 异步初始化并回调结果
     * @param context 上下文（用于Mars SDK环境配置）
     * @param callback 加载结果回调（通知上层成功/失败状态）
     */
    void loadScene(Context context, GEPlayer.Callback callback) {
        // 1. 重复加载防护：若播放器已存在，直接回调失败（避免重复初始化资源）
        if (mPlayer != null) {
            TaskScheduleUtil.postToUiThreadDelayed(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onResult(false, "loadScene duplicated");
                    }
                }
            }, 0);
            return;
        }

        // 2. 参数合法性校验：必须存在params且url非空（无资源地址则无法加载）
        if (mParams == null || TextUtils.isEmpty(mParams.url)) {
            TaskScheduleUtil.postToUiThreadDelayed(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onResult(false, "url is null");
                    }
                }
            }, 0);
            return;
        }

        try {
            // 3. 初始化Mars SDK全局环境（设置应用上下文，确保资源加载路径正确）
            ContextUtil.setApplicationContext(context.getApplicationContext());

            // 4. 构建Mars播放器构造器，配置基础参数
            MarsNativeBuilder builder = new MarsNativeBuilder("galacean", mParams.url);
            builder.placeHolderBitmap = mParams.downgradeImage;  // 设置降级占位图（加载中/失败时显示）
            builder.variables = mParams.variables;              // 设置字符串类型动态参数（如着色器变量）
            builder.variablesBitmap = mParams.variablesBitmap;  // 设置位图类型动态参数（如纹理输入）
            builder.showPlaceHolderFirst = false;               // 不优先显示占位图（直接进入加载流程）

            // 5. 构建播放器实例（此时仅完成对象创建，未完成资源初始化）
            mPlayer = builder.build(context);

            // 6. 弱引用持有当前实例，防止异步回调导致内存泄漏（避免持有外部Activity/Fragment强引用）
            WeakReference<GEPlayerImpl> weakThiz = new WeakReference<>(this);

            // 7. 异步初始化播放器（Mars SDK内部可能进行网络请求、资源解码等耗时操作）
            builder.initPlayer(new MarsNativeBuilder.InitCallback() {
                @Override
                protected void onInitResult(boolean success, int errCode, String errMsg) {
                    // 7.1 回调空校验：若上层已取消回调（如页面销毁），直接返回
                    if (callback == null) {
                        LogUtil.error(TAG, "onInitResult without callback");
                        return;
                    }

                    // 7.2 实例存活校验：若当前GEPlayerImpl已被销毁（mPlayer=null），终止回调（避免操作已释放资源）
                    GEPlayerImpl thiz = weakThiz.get();
                    if (thiz == null || thiz.mPlayer == null) {
                        return;
                    }

                    // 7.3 通知上层初始化结果（成功/失败）
                    if (success) {
                        callback.onResult(true, "");
                    } else {
                        callback.onResult(false, errMsg);
                    }
                }
            });
        } catch (Throwable e) {
            // 8. 异常捕获：防止初始化过程中抛出未捕获异常导致崩溃（如资源解析失败、SDK内部错误）
            LogUtil.error(TAG, "loadScene.e:" + e.getMessage());
            TaskScheduleUtil.postToUiThreadDelayed(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onResult(false, e.getMessage());
                    }
                }
            }, 0);
        }
    }

    /**
     * 播放特效（从头开始）
     * @param repeatCount 重复次数（0=无限循环）
     * @param callback 播放状态回调（由PlayCallback转发）
     */
    void play(int repeatCount, GEPlayer.Callback callback) {
        // 判空保护：仅当播放器已初始化成功时执行播放
        if (mPlayer != null) {
            // 包装上层回调为Mars SDK所需的PlayCallback（统一处理播放完成/运行时错误）
            mPlayer.play(repeatCount, new PlayCallback(callback));
        }
    }

    /**
     * 播放特效（指定帧范围）
     * @param fromFrame 起始帧索引
     * @param toFrame 结束帧索引
     * @param repeatCount 重复次数（0=无限循环）
     * @param callback 播放状态回调（由PlayCallback转发）
     */
    void play(int fromFrame, int toFrame, int repeatCount, GEPlayer.Callback callback) {
        // 判空保护：仅当播放器已初始化成功时执行播放
        if (mPlayer != null) {
            mPlayer.play(fromFrame, toFrame, repeatCount, new PlayCallback(callback));
        }
    }

    /** 暂停播放（仅暂停当前播放进度，不释放资源） */
    void pause() {
        if (mPlayer != null) {
            mPlayer.pause();
        }
    }

    /** 恢复播放（从暂停处继续，需先调用pause） */
    void resume() {
        if (mPlayer != null) {
            mPlayer.resume();
        }
    }

    /** 停止播放（重置播放进度到初始状态，可重新调用play） */
    void stop() {
        if (mPlayer != null) {
            mPlayer.stop();
        }
    }

    /**
     * 销毁播放器（释放所有资源，包括GPU纹理、内存缓存等）
     * 调用后mPlayer置空，避免野指针和重复释放
     */
    void destroy() {
        if (mPlayer != null) {
            mPlayer.destroy();
            mPlayer = null;  // 关键：置空引用，防止后续误用
        }
    }

    /**
     * 获取特效宽高比（用于上层布局适配）
     * @return 宽高比（加载失败时返回-1）
     */
    float getAspect() {
        if (mPlayer != null) {
            return mPlayer.getAspect();
        }
        return -1;
    }

    /**
     * 获取特效总帧数（估算值，基于30fps计算：时长(ms)/1000 * 30 ≈ 帧数）
     * @return 总帧数（加载失败时返回-1）
     */
    int getFrameCount() {
        if (mPlayer != null) {
            // 公式：总帧数 ≈ 播放时长(ms) / 1000 * 30fps（向下取整）
            return (int) Math.floor(mPlayer.getDuration() * 1000.0 / 33.0);
        }
        return -1;
    }

    /**
     * 播放回调中转类（桥接Mars SDK的PlayCallback与上层GEPlayer.Callback）
     * 作用：统一处理播放完成、运行时错误，并转发给上层回调
     */
    private static class PlayCallback extends MarsNativePlayer.PlayCallback {
        // 上层回调引用（弱引用已在loadScene中处理，此处直接持有）
        private GEPlayer.Callback mCallback;

        PlayCallback(GEPlayer.Callback callback) {
            mCallback = callback;
        }

        /** 播放完成时回调（如单次播放结束或循环结束） */
        @Override
        protected void onPlayFinish() {
            try {
                if (mCallback != null) {
                    mCallback.onResult(true, "");  // 播放完成视为成功
                }
            } catch (Throwable e) {
                // 异常捕获：防止上层回调抛出未处理异常导致崩溃
                LogUtil.error(TAG, "onPlayFinish.e:" + e.getMessage());
            }
        }

        /** 播放过程中发生运行时错误（如资源损坏、渲染失败） */
        @Override
        protected void onRuntimeError(String errMsg) {
            try {
                if (mCallback != null) {
                    mCallback.onResult(false, errMsg);  // 运行时错误视为失败
                }
            } catch (Throwable e) {
                // 异常捕获：防止上层回调抛出未处理异常导致崩溃
                LogUtil.error(TAG, "onRuntimeError.e:" + e.getMessage());
            }
        }
    }
}