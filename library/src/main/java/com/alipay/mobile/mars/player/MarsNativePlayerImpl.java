package com.alipay.mobile.mars.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.text.TextUtils;
import android.view.Surface;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.alipay.mobile.mars.adapter.DowngradeUtil;
import com.alipay.mobile.mars.EventEmitter;
import com.alipay.mobile.mars.adapter.ConfigUtil;
import com.alipay.mobile.mars.adapter.MonitorUtil;
import com.alipay.mobile.mars.adapter.TaskScheduleUtil;
import com.alipay.mobile.mars.player.data.MarsDataBase;
import com.alipay.mobile.mars.player.data.MarsDataJsonBin;
import com.alipay.mobile.mars.util.Constants;
import com.alipay.mobile.mars.util.JNIUtil;
import com.alipay.mobile.mars.adapter.LogUtil;
import com.alipay.mobile.mars.view.MarsTextureView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mars原生播放器实现类
 * 负责Mars动画的播放、控制和渲染管理
 *
 * C++ Native函数调用汇总：
 *
 * 播放器生命周期管理：
 * - {@link JNIUtil#nativeMarsCreate(int, int, boolean, boolean)} - 创建原生播放器实例
 * - {@link JNIUtil#nativeMarsDestroy(int)} - 销毁原生播放器
 * - {@link JNIUtil#nativeMarsStop(int)} - 停止播放
 * - {@link JNIUtil#nativeMarsEvent(int, int)} - 发送播放器事件（暂停/恢复等）
 *
 * 场景数据管理：
 * - {@link JNIUtil#nativeSceneDataCreate(byte[], int)} - 从字节数组创建场景数据
 * - {@link JNIUtil#nativeSceneDataCreateByPath(String)} - 从文件路径创建场景数据
 * - {@link JNIUtil#nativeSceneDataDestroy(long)} - 销毁场景数据
 * - {@link JNIUtil#nativeSetSceneData(int, long)} - 设置场景数据到播放器
 *
 * 资源图片管理：
 * - {@link JNIUtil#nativeSetSceneImageResource(long, String, byte[], int)} - 设置压缩纹理资源
 * - {@link JNIUtil#nativeSetSceneImageResourceBmp(long, String, Bitmap)} - 设置位图资源
 * - {@link JNIUtil#nativeSetSceneImageResourceVideo(long, String, long)} - 设置视频纹理资源
 * - {@link JNIUtil#nativeUpdateMarsImage(int, int, Bitmap)} - 动态更新变量图片
 *
 * 播放控制：
 * - {@link JNIUtil#nativeSetRepeatCount(int, int)} - 设置重复播放次数
 * - {@link JNIUtil#nativeMarsPlayFrameControl(int, int, int, String, boolean)} - 控制播放帧范围
 *
 * Surface管理：
 * - {@link JNIUtil#nativeSetupSurface(int, Surface)} - 设置渲染Surface
 * - {@link JNIUtil#nativeResizeSurface(int, int, int)} - 调整Surface尺寸
 * - {@link JNIUtil#nativeDestroySurface(int)} - 销毁Surface
 *
 * 插件扩展：
 * - {@link JNIUtil#nativeMarsAddPlugin(int, long, String)} - 添加自定义插件
 */
public class MarsNativePlayerImpl extends MarsNativePlayer implements EventEmitter.EventListener, MarsTextureView.MarsSurfaceListener {

    private final static String TAG = "MarsNativePlayer";

    // 原始资源路径
    private final String mSource;
    // 格式化后的URL（用于标识和降级判断）
    private final String mUrl;
    // 场景码
    private final String mScene;
    // 设备等级（用于性能适配）
    private int mDeviceLevel = Constants.DEVICE_LEVEL_HIGH;
    // 是否修复时间刻度（保证动画播放流畅性）
    private boolean mFixTickTime = true;

    // SO库是否已加载
    private static boolean sIsLoadSo = false;
    // 原生播放器指针
    private int mNativePlayer = -1;
    // 场景数据指针
    private long mSceneDataPtr = 0;
    // Mars渲染视图
    private MarsTextureView mMarsView;

    // 重复播放次数（-1表示无限循环）
    private int mRepeatCount = -1;
    // 总帧数
    private int mFrameCount = 0;
    // 是否降级处理
    private boolean mDowngrade = false;

    // 占位图ImageView
    private ImageView mPlaceHolder;
    // 占位图可见性状态
    private int mPlaceHolderVisibility;
    // 宽高比
    private float mAspect = 1.0f;
    // 动画时长（秒）
    private float mDuration = 0.0f;

    // Mars数据源
    private MarsDataBase mMarsData = null;
    // 变量映射表（文本变量）
    private Map<String, String> mVariables = null;
    // 变量位图映射表（图片变量）
    private Map<String, Bitmap> mVariableBitmaps = null;
    // 事件监听器
    private EventListener mEventListener = null;

    // 指针操作互斥锁
    private final Object mPtrMutex = new Object();

    // 初始化回调
    private InitCallback mInitCallback;
    // 首屏渲染完成回调
    private Runnable mFirstScreenCallback = null;
    // 播放回调持有者
    private PlayCallbackHolder mPlayCallbackHolder;

    // Mars扩展列表
    private ArrayList<MarsNativeExtension> mExtensions = new ArrayList<>();
    // 临时资源列表（防止GC回收）
    private ArrayList<MarsNativeResourceLoader.MarsNativeImageResource> mResList;

    /**
     * 构造函数
     * @param context 上下文
     * @param builder 构建器
     */
    MarsNativePlayerImpl(Context context, MarsNativeBuilder builder) {
        super(context);
        mSource = builder.getSource();
        mUrl = getFormattedSourceId(builder.getUrl());
        mScene = builder.scene;
        mRepeatCount = builder.repeatCount;
        mVariables = builder.variables;
        mVariableBitmaps = builder.variablesBitmap;
        mFixTickTime = builder.fixTickTime;
        mPlaceHolderVisibility = builder.showPlaceHolderFirst ? VISIBLE : GONE;
    }

    /**
     * 检查是否需要降级处理
     * @return 降级原因，null表示不降级
     */
    String checkDowngrade() {
        String downgradeReason = null;
        // 先检查总降级开关
        boolean shouldDowngrade = ConfigUtil.forceDowngradeMN();
        downgradeReason = "forceDowngradeMN";
        if (!shouldDowngrade) {
            // 检查资源纬度降级开关
            if (TextUtils.isEmpty(mScene)) {
                // 没有场景码，按资源降级
                shouldDowngrade = ConfigUtil.forceDowngradeByResId(mUrl);
                downgradeReason = "forceDowngradeByResId," + mUrl;
            } else {
                // 有场景码，按场景降级
                shouldDowngrade = ConfigUtil.forceDowngradeByResScene(mScene);
                downgradeReason = "forceDowngradeByResScene," + mScene;
            }
        }
        if (!shouldDowngrade) {
            // 没有开关降级，检查统一降级
            DowngradeUtil.DowngradeResult downgradeResult = DowngradeUtil.getDowngradeResult(mUrl);
            if (downgradeResult.getDowngrade()) {
                // 统一降级降级了
                shouldDowngrade = true;
                downgradeReason = "getDowngradeResult," + downgradeResult.getReason();
            } else {
                // 没降级，获取设备等级
                mDeviceLevel = downgradeResult.getDeviceLevel();
                LogUtil.debug(TAG, "getDowngradeResult: deviceLevel " + mDeviceLevel);
            }
        }

        if (shouldDowngrade) {
            // 需要降级，切换到UI线程执行降级
            TaskScheduleUtil.postToUiThreadDelayed(new Runnable() {
                @Override
                public void run() {
                    setDowngrade();
                }
            }, 0);
            return downgradeReason;
        }
        return null;
    }

    @Override
    public void play() {
        // 默认从第一帧播放到最后一帧，使用预设重复次数
        play(0, mFrameCount, mRepeatCount, null);
    }

    @Override
    public void play(PlayCallback callback) {
        // 带回调的播放
        play(0, mFrameCount, mRepeatCount, callback);
    }

    @Override
    public void play(int repeatCount, PlayCallback callback) {
        // 指定重复次数的播放
        play(0, mFrameCount, repeatCount, callback);
    }

    @Override
    public void play(int fromFrame, int toFrame, int repeatCount, final PlayCallback callback) {
        // 完整的播放控制方法
        if (mDowngrade) {
            // 已降级，直接返回错误
            TaskScheduleUtil.postToUiThreadDelayed(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onRuntimeError("downgrade");
                    }
                }
            }, 0);
            return;
        }
        LogUtil.debug(TAG, "play " + this);
        mRepeatCount = repeatCount;
        mPlayCallbackHolder = new PlayCallbackHolder(callback);
        // 设置原生层重复次数
        JNIUtil.nativeSetRepeatCount(mNativePlayer, mRepeatCount);
        // 开始播放指定帧范围
        JNIUtil.nativeMarsPlayFrameControl(mNativePlayer, fromFrame, toFrame, mPlayCallbackHolder.mToken, true);
    }

    @Override
    public void stop() {
        // 停止播放
        if (mDowngrade) {
            return;
        }
        LogUtil.debug(TAG, "stop " + this);
        JNIUtil.nativeMarsStop(mNativePlayer);
    }

    @Override
    public void pause() {
        // 暂停播放
        if (mDowngrade) {
            return;
        }
        LogUtil.debug(TAG, "pause " + this);
        JNIUtil.nativeMarsEvent(mNativePlayer, Constants.NATIVE_EVENT_PAUSE);
    }

    @Override
    public void resume() {
        // 恢复播放
        if (mDowngrade) {
            return;
        }
        LogUtil.debug(TAG, "resume " + this);
        JNIUtil.nativeMarsEvent(mNativePlayer, Constants.NATIVE_EVENT_RESUME);
    }

    @Override
    public void destroy() {
        // 销毁播放器，释放所有资源
        synchronized (mPtrMutex) {
            LogUtil.debug(TAG, "destroy " + mNativePlayer + " " + this);
            if (mNativePlayer != -1) {
                // 销毁原生播放器
                JNIUtil.nativeMarsDestroy(mNativePlayer);
                // 取消事件监听注册
                EventEmitter.unregisterListener(mNativePlayer);
                mNativePlayer = -1;
            }
            if (mSceneDataPtr != 0 && mSceneDataPtr != -1) {
                // 销毁场景数据
                JNIUtil.nativeSceneDataDestroy(mSceneDataPtr);
                mSceneDataPtr = 0;
            }
            mMarsView = null;
            mInitCallback = null;
            mPlayCallbackHolder = null;

            // 通知所有扩展销毁
            for (MarsNativeExtension extension : mExtensions) {
                extension.onDestroy();
            }
            mExtensions.clear();
        }
    }

    @Override
    public float getAspect() {
        // 获取宽高比
        return mAspect;
    }

    public PreviewSize getPreviewSize() {
        // 获取预览尺寸
        Point tmp = mMarsData.getPreviewSize();
        return new PreviewSize((float) tmp.x, (float) tmp.y);
    }

    @Override
    public float getDuration() {
        // 获取动画时长
        return mDuration;
    }

    @Override
    public void setEventListener(EventListener listener) {
        // 设置事件监听器
        mEventListener = listener;
    }

    @Override
    public void addExtension(MarsNativeExtension extension) {
        // 添加扩展插件
        mExtensions.add(extension);
    }

    @Override
    public void onFirstScreen(Runnable runnable) {
        // 设置首屏渲染完成回调
        mFirstScreenCallback = runnable;
    }

    @Override
    public boolean updateVariable(String key, Bitmap bitmap) {
        // 更新变量图片（动态替换模板中的图片）
        if (bitmap == null) {
            LogUtil.error(TAG, "updateVariable: bitmap is null");
            return false;
        }

        // 查找对应的模板索引
        List<MarsDataBase.ImageInfo> imgs =  mMarsData.getImages();
        int templateIdx = -1;
        for (MarsDataBase.ImageInfo img : imgs) {
            if (img.url.equals(key)) {
                templateIdx = img.templateIdx;
                break;
            }
        }

        if (templateIdx == -1) {
            LogUtil.error(TAG, "updateVariable: teamplateIdx is -1");
            return false;
        }

        // 调用原生接口更新图片
        return JNIUtil.nativeUpdateMarsImage(mNativePlayer, templateIdx, bitmap);
    }

    /**
     * 使用Mars数据初始化播放器
     * @param marsData Mars数据源
     * @param callback 初始化回调
     */
    void initWithMarsData(MarsDataBase marsData, InitCallback callback) {
        LogUtil.debug(TAG, "initWithMarsData:type=" + marsData.getType());

        if (mDowngrade) {
            return;
        }

        mMarsData = marsData;

        synchronized (mPtrMutex) {
            // 设置基础属性
            mAspect = marsData.getAspect();
            mDuration = marsData.getDuration();
            mFrameCount = (int) (marsData.getDuration() * 30); // 假设30fps
            mInitCallback = callback;

            // 尝试加载SO库
            if (!tryLoadSo()) {
                callback.onResult(false, "load so fail");
                return;
            }

            // 创建原生播放器实例
            mNativePlayer = generatePlayerIndex();
            EventEmitter.registerListener(mNativePlayer, this);
            JNIUtil.nativeMarsCreate(mNativePlayer,
                    ConfigUtil.getRenderLevel(mDeviceLevel), // 渲染等级
                    ConfigUtil.enableSurfaceScale(mUrl),    // 是否启用表面缩放
                    mFixTickTime);                         // 是否修复时间刻度

            // 添加自定义插件
            for (int i = 0; i < mExtensions.size(); i++) {
                String[] name = new String[1];
                long ptr = mExtensions.get(i).getCustomPlugin(name);
                if (ptr != 0) {
                    JNIUtil.nativeMarsAddPlugin(mNativePlayer, ptr, name[0]);
                }
            }

            if (mNativePlayer == -1) {
                callback.onResult(false, "create player fail");
                return;
            }

            // 加载图片资源
            tryLoadImages(marsData);
            JNIUtil.nativeSetRepeatCount(mNativePlayer, mRepeatCount);

            // 根据数据类型创建场景数据
            if (marsData.getType() == MarsDataBase.Type.JSON_BIN) {
                MarsDataJsonBin marsDataJsonBin = (MarsDataJsonBin) marsData;
                if (marsDataJsonBin.getBinBytes() != null) {
                    // 从字节数组创建场景数据
                    byte[] data = marsDataJsonBin.getBinBytes();
                    mSceneDataPtr = JNIUtil.nativeSceneDataCreate(data, data.length);
                } else {
                    // 从文件路径创建场景数据
                    mSceneDataPtr = JNIUtil.nativeSceneDataCreateByPath(((MarsDataJsonBin) marsData).getBinFilePath());
                }
            }

            LogUtil.debug(TAG, "initWithMarsData:mSceneDataPtr " + mSceneDataPtr);
            if (mSceneDataPtr == -1 || mSceneDataPtr == 0) {
                callback.onResult(false, "create scene data fail");
                return;
            }

            // 通知扩展场景数据创建完成
            for (MarsNativeExtension extension : mExtensions) {
                String[] errMsg = new String[1];
                extension.onSceneDataCreated(mSceneDataPtr, errMsg);
                if (!TextUtils.isEmpty(errMsg[0])) {
                    callback.onResult(false, errMsg[0]);
                    return;
                }
            }

            // 切换到UI线程创建渲染视图
            TaskScheduleUtil.postToUiThreadDelayed(new Runnable() {
                @Override
                public void run() {
                    ViewGroup.LayoutParams layoutParams = new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);
                    mMarsView = new MarsTextureView(getContext(), MarsNativePlayerImpl.this);
                    addView(mMarsView, layoutParams);
                }
            }, 0);
        }
    }

    /**
     * 显示占位图
     */
    void showPlaceHolder() {
        if (mPlaceHolder != null) {
            mPlaceHolder.setVisibility(VISIBLE);
        }
        mPlaceHolderVisibility = VISIBLE;
    }

    /**
     * 隐藏占位图
     */
    void hidePlaceHolder() {
        if (mDowngrade) {
            return;
        }
        if (mPlaceHolder != null) {
            mPlaceHolder.setVisibility(GONE);
        }
        mPlaceHolderVisibility = GONE;
    }

    /**
     * 设置占位图
     * @param bitmap 占位图位图
     */
    void setPlaceHolder(Bitmap bitmap) {
        LogUtil.debug(TAG, "setPlaceHolder " + bitmap);
        if (mPlaceHolder == null) {
            mPlaceHolder = new ImageView(getContext());
            mPlaceHolder.setScaleType(ImageView.ScaleType.FIT_XY);
            addView(mPlaceHolder, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mPlaceHolder.setVisibility(mPlaceHolderVisibility);
        }
        mPlaceHolder.setImageBitmap(bitmap);
    }

    /**
     * 设置降级状态
     */
    void setDowngrade() {
        LogUtil.debug(TAG, "setDowngrade");
        mDowngrade = true;
        showPlaceHolder();
        destroy();
    }

    public String getSourceId() {
        // 获取资源ID
        return mUrl;
    }

    public boolean isDowngrade() {
        // 是否处于降级状态
        return mDowngrade;
    }

    @Override
    protected void finalize() throws Throwable {
        // 析构函数，确保资源被释放
        super.finalize();
        LogUtil.debug(TAG, "finalize");
        destroy();
    }

    /**
     * 尝试加载图片资源
     * @param marsData Mars数据源
     */
    private void tryLoadImages(MarsDataBase marsData) {
        List<MarsDataBase.ImageInfo> images = marsData.getImages();
        if (images == null || images.isEmpty()) {
            LogUtil.debug(TAG, "tryLoadImages skip");
            // 没有图片资源，直接设置空处理器
            TaskScheduleUtil.postToNormalThread(new Runnable() {
                @Override
                public void run() {
                    setupImages(new ImageProcessor(null));
                }
            });
            return;
        }

        // 创建资源列表
        final ArrayList<MarsNativeResourceLoader.MarsNativeImageResource> resList = new ArrayList<>();
        // 是否出现过templateIdx，如果出现过，后面应全是数据模版；如果后面出现非数据模版，应忽略
        boolean hasTemplateIdx = false;
        mResList = resList;

        // 处理图片信息列表
        for (int i = 0; i < images.size(); i++) {
            MarsDataBase.ImageInfo info = images.get(i);
            String url = info.url;
            // 优先使用ASTC格式URL
            if (!TextUtils.isEmpty(info.astc)) {
                url = info.astc;
            }

            MarsNativeResourceLoader.MarsNativeImageResource res = new MarsNativeResourceLoader.MarsNativeImageResource(url);
            res.isVideo = info.isVideo;
            res.isTransparentVideo = info.isTransparentVideo;

            if (res.isVideo) {
                // 配置视频硬解码
                res.videoHardDecoder = ConfigUtil.enableVideoHardDecoder(mUrl);
            }

            if (info.templateIdx >= 0) {
                // 数据模板类型
                hasTemplateIdx = true;
                res = resList.get(info.templateIdx);
                if (res == null) {
                    continue;
                }
                // 替换key为实际的数据URL
                res.key = info.url;
            }

            if (!hasTemplateIdx) {
                // 普通图片资源，添加到列表
                resList.add(res);
            }
        }

        LogUtil.debug(TAG, "tryLoadImages count:" + resList.size());

        // 异步加载图片资源
        MarsNativeResourceLoader.loadImages(resList, marsData.getDirPath(), mVariables, mVariableBitmaps, mNativePlayer, mSource, new MarsNativeResourceLoader.LoadImageListCallback() {
            @Override
            public void onResult(String errMsg) {
                if (!TextUtils.isEmpty(errMsg)) {
                    LogUtil.error(TAG, "tryLoadImages..e:" + errMsg);
                    TaskScheduleUtil.postToNormalThread(new Runnable() {
                        @Override
                        public void run() {
                            setupImages(null);
                        }
                    });
                    return;
                }
                LogUtil.debug(TAG, "tryLoadImages success");
                TaskScheduleUtil.postToNormalThread(new Runnable() {
                    @Override
                    public void run() {
                        ImageProcessor processor = new ImageProcessor(resList);
                        if (marsData.getFonts() == null || marsData.getFonts().isEmpty()) {
                            // 没有字体，直接设置图片
                            setupImages(processor);
                        } else {
                            // 需要加载字体
                            tryLoadFonts(marsData, marsData.getDirPath(), processor);
                        }
                    }
                });
            }
        });
    }

    /**
     * 尝试加载字体资源
     * @param marsData Mars数据源
     * @param dirPath 资源目录路径
     * @param imageProcessor 图片处理器
     */
    private void tryLoadFonts(MarsDataBase marsData, String dirPath, ImageProcessor imageProcessor) {
        ArrayList<String> urlList = new ArrayList<>();
        ArrayList<MarsDataBase.FontInfo> fonts = marsData.getFonts();

        // 收集字体URL列表
        for (int i = 0; i < fonts.size(); i++) {
            MarsDataBase.FontInfo font = fonts.get(i);
            if (!TextUtils.isEmpty(font.url)) {
                urlList.add(font.url);
            }
        }

        // 设置文本和字体信息到处理器
        imageProcessor.setTexts(marsData.getTexts(), marsData.getFonts());

        if (urlList.isEmpty()) {
            // 没有线上字体文件，直接设置图片
            setupImages(imageProcessor);
            return;
        }

        // 异步加载字体
        MarsNativeResourceLoader.loadFonts(urlList, mSource, dirPath, new MarsNativeResourceLoader.LoadFontListCallback() {
            @Override
            public void onResult(HashMap<String, String> fontPathMap) {
                if (fontPathMap == null) {
                    LogUtil.error(TAG, "tryLoadFonts..e:fontPathMap is null");
                    TaskScheduleUtil.postToNormalThread(new Runnable() {
                        @Override
                        public void run() {
                            setupImages(null);
                        }
                    });
                    return;
                }

                LogUtil.debug(TAG, "tryLoadFonts success");
                TaskScheduleUtil.postToNormalThread(new Runnable() {
                    @Override
                    public void run() {
                        imageProcessor.setFontDataMap(fontPathMap);
                        setupImages(imageProcessor);
                    }
                });
            }
        });
    }

    /**
     * 设置图片资源到原生层
     * @param imageProcessor 图片处理器
     */
    private void setupImages(ImageProcessor imageProcessor) {
        synchronized (mPtrMutex) {
            final ArrayList<MarsNativeResourceLoader.MarsNativeImageResource> temp = mResList;
            mResList = null;

            if (mSceneDataPtr == 0 || mSceneDataPtr == -1) {
                LogUtil.debug(TAG, "setupImages:ptr is null");
                return;
            }

            if (imageProcessor == null) {
                LogUtil.error(TAG, "setupImages processor is null");
                if (mInitCallback != null) {
                    mInitCallback.onResult(false, "download images fail");
                }
                return;
            }

            // 处理图片数据
            Map<String, ImageProcessor.DataInfo> imageDataMap = imageProcessor.process(mVariables);
            if (imageDataMap == null) {
                LogUtil.debug(TAG, "setupImages:dataMap is null");
                if (mInitCallback != null) {
                    mInitCallback.onResult(false, "load images fail");
                }
                return;
            }

            // 将处理后的图片数据设置到原生层
            for (Map.Entry<String, ImageProcessor.DataInfo> entry : imageDataMap.entrySet()) {
                String url = entry.getKey();
                ImageProcessor.DataInfo info = entry.getValue();

                if (info.isVideo) {
                    // 设置视频纹理
                    JNIUtil.nativeSetSceneImageResourceVideo(mSceneDataPtr, url, info.videoRes.videoContext);
                    info.videoRes.videoContext = 0; // 清理引用
                } else if (info.isKtx) {
                    // 设置压缩纹理
                    JNIUtil.nativeSetSceneImageResource(mSceneDataPtr, url, info.bytes, info.bytes.length);
                } else {
                    // 设置普通位图
                    JNIUtil.nativeSetSceneImageResourceBmp(mSceneDataPtr, url, info.bitmap);
                }
            }

            // 释放处理器中的数据
            imageProcessor.releaseData();
            // 将场景数据设置到播放器
            JNIUtil.nativeSetSceneData(mNativePlayer, mSceneDataPtr);
            mSceneDataPtr = 0; // 清理指针

            // 初始化成功回调
            if (mInitCallback != null) {
                mInitCallback.onResult(true, null);
                mInitCallback = null;
            }
        }
    }

    /**
     * 尝试加载SO库
     * @return 是否加载成功
     */
    private static synchronized boolean tryLoadSo() {
        if (!sIsLoadSo) {
            try {
                LogUtil.debug(TAG, "load marsnative.so async");
                System.loadLibrary("marsnative");
                sIsLoadSo = true;
            } catch (Throwable e) {
                LogUtil.debug(TAG, "load library failed" + e.toString());
            }
        }
        return sIsLoadSo;
    }

    /**
     * 格式化源ID（用于标识和降级判断）
     * @param url 原始URL
     * @return 格式化后的ID
     */
    static String getFormattedSourceId(String url) {
        try {
            if (url.length() > 32 && (url.startsWith("http://") || url.startsWith("https://"))) {
                int idx = url.lastIndexOf("A*");
                if (idx != -1) {
                    return url.substring(idx, Math.min(idx + 32, url.length()));
                }
            }
        } catch (Exception e) {
            LogUtil.error(TAG, "getFormattedSourceId..e:" + e.getMessage());
        }
        return url;
    }

    /**
     * 事件监听回调
     * @param type 事件类型
     * @param msg 事件消息
     */
    @Override
    public void onEvent(int type, String msg) {
        LogUtil.debug(TAG, "onEvent:" + type + "," + msg + ".");
        switch (type) {
            case Constants.PLATFORM_EVENT_STATISTICS:
                // 统计事件：GPU能力信息
                TaskScheduleUtil.postToNormalThread(new Runnable() {
                    @Override
                    public void run() {
                        if (TextUtils.isEmpty(msg)) {
                            LogUtil.error(TAG, "empty msg for event1");
                            return;
                        }
                        String[] arr = msg.split("_");
                        if (arr.length < 2) {
                            LogUtil.error(TAG, "invalid msg for event1 " + msg);
                            return;
                        }
                        boolean supportCompressedTexture = (arr[0].equals("true"));
                        int glesVersion = Integer.parseInt(arr[1]);
                        MonitorUtil.monitorStatisticsEvent(mUrl, supportCompressedTexture, glesVersion);
                    }
                });
                break;

            case Constants.PLATFORM_EVENT_START:
                // 动画开始播放事件
                TaskScheduleUtil.postToUiThreadDelayed(new Runnable() {
                    @Override
                    public void run() {
                        hidePlaceHolder(); // 隐藏占位图
                        if (mFirstScreenCallback != null) {
                            mFirstScreenCallback.run(); // 执行首屏回调
                            mFirstScreenCallback = null;
                        }
                    }
                }, 0);
                break;

            case Constants.PLATFORM_EVENT_THREAD_START:
                // 工作线程开始事件
                Thread.currentThread().setName(msg);
                DowngradeUtil.writeResourceIdBegin(mUrl, msg);
                break;

            case Constants.PLATFORM_EVENT_THREAD_END:
                // 工作线程结束事件
                DowngradeUtil.writeResourceIdFinish(mUrl, msg);
                break;

            case Constants.PLATFORM_EVENT_ANIMATION_END:
                // 动画播放结束事件
                TaskScheduleUtil.postToUiThreadDelayed(new Runnable() {
                    @Override
                    public void run() {
                        PlayCallback callback = (mPlayCallbackHolder != null) ? mPlayCallbackHolder.mPlayCallback : null;
                        if (callback != null && TextUtils.equals(mPlayCallbackHolder.mToken, msg)) {
                            callback.onPlayFinish(); // 通知播放完成
                        }
                    }
                }, 0);
                break;

            case Constants.PLATFORM_EVENT_INTERACT_MESSAGE_BEGIN:
            case Constants.PLATFORM_EVENT_INTERACT_MESSAGE_END:
                // 交互消息开始/结束事件
                TaskScheduleUtil.postToUiThreadDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mEventListener != null) {
                            mEventListener.onMessageItem(msg,
                                    (type == Constants.PLATFORM_EVENT_INTERACT_MESSAGE_BEGIN)
                                            ? "MESSAGE_ITEM_PHRASE_BEGIN"
                                            : "MESSAGE_ITEM_PHRASE_END");
                        }
                    }
                }, 0);
                break;

            case Constants.PLATFORM_EVENT_EGL_INIT_ERROR:
            case Constants.PLATFORM_EVENT_RUNTIME_ERROR:
                // 错误事件：EGL初始化错误或运行时错误
                TaskScheduleUtil.postToUiThreadDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // 回调运行时异常
                        PlayCallback callback = (mPlayCallbackHolder != null) ? mPlayCallbackHolder.mPlayCallback : null;
                        if (callback != null) {
                            try {
                                callback.onRuntimeError(msg);
                            } catch (Exception e) {
                                LogUtil.error(TAG, "onRuntimeError..e:" + e.getMessage());
                            }
                        }
                        setDowngrade(); // 发生错误时降级
                    }
                }, 0);
                // 上报错误埋点
                TaskScheduleUtil.postToNormalThread(new Runnable() {
                    @Override
                    public void run() {
                        MonitorUtil.monitorErrorEvent(mUrl, "runtime_error", msg);
                    }
                });
                break;

            default:
                LogUtil.debug(TAG, "unhandled event:" + type);
                break;
        }
    }

    /**
     * Surface创建回调
     * @param surface Surface对象
     */
    @Override
    public void onSurfaceCreated(Surface surface) {
        JNIUtil.nativeSetupSurface(mNativePlayer, surface);
    }

    /**
     * Surface尺寸变化回调
     * @param width 新宽度
     * @param height 新高度
     */
    @Override
    public void onSurfaceResize(int width, int height) {
        JNIUtil.nativeResizeSurface(mNativePlayer, width, height);
    }

    /**
     * Surface销毁回调
     */
    @Override
    public void onSurfaceDestroyed() {
        JNIUtil.nativeDestroySurface(mNativePlayer);
    }

    // 播放器索引生成器
    private static final AtomicInteger sPlayerIndex = new AtomicInteger(0);

    /**
     * 生成唯一的播放器索引
     * @return 播放器索引
     */
    public static synchronized int generatePlayerIndex() {
        return sPlayerIndex.incrementAndGet();
    }

    /**
     * 初始化回调接口
     */
    interface InitCallback {
        void onResult(boolean success, String errMsg);
    }

    // 完成索引生成器
    private static final AtomicInteger sCompleteIdx = new AtomicInteger(0);

    /**
     * 播放回调持有者（用于匹配播放完成事件）
     */
    private static class PlayCallbackHolder {
        public PlayCallback mPlayCallback;
        public String mToken;

        PlayCallbackHolder(PlayCallback callback) {
            synchronized (sCompleteIdx) {
                mToken = "p" + sCompleteIdx.incrementAndGet();
            }
            mPlayCallback = callback;
        }
    }
}