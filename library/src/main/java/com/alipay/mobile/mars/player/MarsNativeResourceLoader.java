package com.alipay.mobile.mars.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;

import com.alipay.mobile.mars.adapter.ContextUtil;
import com.alipay.mobile.mars.adapter.MD5Util;
import com.alipay.mobile.mars.adapter.DownloadUtil;
import com.alipay.mobile.mars.adapter.TaskScheduleUtil;
import com.alipay.mobile.mars.util.CommonCallbacks;
import com.alipay.mobile.mars.util.CommonUtil;
import com.alipay.mobile.mars.util.Constants;
import com.alipay.mobile.mars.util.JNIUtil;
import com.alipay.mobile.mars.util.JNIUtil.CommonJNICallback;
import com.alipay.mobile.mars.adapter.LogUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mars原生资源加载器
 * 负责Mars动画所需的各类资源（图片、字体、压缩包等）的加载和管理
 * <p>
 * C++ Native函数调用汇总：
 * <p>
 * 视频资源管理：
 * {@link JNIUtil#nativeCreateVideoContextAndPrepare(int, String, String, boolean, boolean, CommonJNICallback) - 创建并准备视频上下文}
 */
public class MarsNativeResourceLoader {

    private final static String TAG = "MarsNativeResourceLoader";

    /**
     * 从本地路径加载文件
     * 目前仅支持包内assets路径的资源加载
     *
     * @param url 资源URL路径
     * @return 本地文件路径，如果不是支持的本地路径则返回null
     */
    static String loadFilePathFromLocal(final String url) {
        if (url.startsWith(Constants.RES_PATH_ASSETS_PREFIX)) {
            // 包内assets路径，直接返回
            return url;
        }
        return null;
    }

    /**
     * 从下载缓存中获取资源路径
     * 用于获取已下载并缓存的资源文件目录
     *
     * @param url 资源URL
     * @return 缓存的文件路径，如果不存在则返回null
     */
    static String loadFilePathFromCache(final String url) {
        return DownloadUtil.getDownloadCachePath(url);
    }

    /**
     * 加载ZIP压缩包资源
     * 下载并解压ZIP文件到指定目录，支持缓存和有效性校验
     *
     * @param url ZIP文件下载地址
     * @param bizType 业务类型（用于下载管理）
     * @param validTimeStamp 有效期时间戳，用于缓存有效性判断
     * @param md5 MD5校验值（可选）
     * @param callback 加载结果回调
     */
    static void loadZip(final String url, String bizType, long validTimeStamp, String md5, final LoadZipCallback callback) {
        // 拼接最终保存路径：基于URL的MD5作为目录名
        String urlMd5 = MD5Util.getMD5String(url);
        String dstDirPath = FileOperation.getMarsDir() + urlMd5;

        long timeStamp = System.currentTimeMillis();
        if (validTimeStamp > timeStamp) {
            // 如果传入的时间戳晚于当前时间，使用传入的（支持未来时间预加载）
            timeStamp = validTimeStamp;
        }

        // 检查目录是否存在且有效
        if (FileOperation.checkDirExist(dstDirPath, true, timeStamp)) {
            callback.onSuccess(dstDirPath);
            return;
        }

        // 下载并解压ZIP文件
        FileOperation.downloadAndUnzip(url, bizType, dstDirPath, timeStamp, md5, new FileOperation.DownloadZipCallback() {
            @Override
            public void onResult(String dstPath, String errMsg) {
                if (TextUtils.isEmpty(errMsg)) {
                    callback.onSuccess(dstPath);
                } else {
                    callback.onError(errMsg);
                }
            }
        });
    }

    /**
     * 加载位图资源
     * 下载图片文件并解码为Bitmap对象
     *
     * @param url 图片下载地址
     * @param bizType 业务类型（用于下载管理）
     * @param callback 加载结果回调，返回Bitmap和错误信息
     */
    static void loadBitmap(String url, String bizType, final LoadBitmapCallback callback) {
        DownloadUtil.downloadFile(url, bizType, null, new DownloadUtil.DownloadFileCallback() {
            @Override
            public void onSuccess(InputStream is, String filePath) {
                try {
                    // 读取输入流为字节数组
                    byte[] bytes = CommonUtil.readFileStreamBinaryAndClose(is);
                    if (bytes == null || bytes.length == 0) {
                        callback.onResult(null, "bytes is null");
                        return;
                    }

                    // 解码字节数组为Bitmap
                    Bitmap bitmap = CommonUtil.decodeImage(bytes);
                    if (bitmap != null) {
                        callback.onResult(bitmap, null);
                    } else {
                        callback.onResult(null, "decode image fail");
                    }
                } catch (Exception e) {
                    callback.onResult(null, e.getMessage());
                }
            }

            @Override
            public void onError(String errMsg) {
                callback.onResult(null, errMsg);
            }
        });
    }

    /**
     * 批量加载图片资源列表
     * 支持动态变量替换（文本变量和位图变量），统一管理加载状态
     *
     * @param resList 图片资源列表
     * @param dirPath 基础目录路径
     * @param variables 文本变量映射表（key->替换文本）
     * @param variablesBitmap 位图变量映射表（key->替换位图）
     * @param playerIdx 播放器索引
     * @param bizType 业务类型（用于下载管理）
     * @param callback 批量加载结果回调
     */
    static void loadImages(List<MarsNativeImageResource> resList, String dirPath, Map<String, String> variables, Map<String, Bitmap> variablesBitmap, final int playerIdx, String bizType, final LoadImageListCallback callback) {
        if (resList == null || resList.isEmpty()) {
            TaskScheduleUtil.postToNormalThread(new Runnable() {
                @Override
                public void run() {
                    callback.onResult("");
                }
            });
            return;
        }

        /*
          进度跟踪数组：
          0: 图片总数
          1: 加载结束个数
          2: 是否成功：1成功，0失败
         */
        final int[] temp = { resList.size(), 0, 1 };

        try {
            for (int i = 0; i < resList.size(); i++) {
                final MarsNativeImageResource res = resList.get(i);

                // 检查并替换动态参数
                if (variables != null) {
                    String tmp = variables.get(res.key);
                    if (!TextUtils.isEmpty(tmp)) {
                        res.realUrl = tmp; // 文本变量替换
                    }
                }
                if (variablesBitmap != null) {
                    Bitmap bitmap = variablesBitmap.get(res.key);
                    if (bitmap != null) {
                        res.bitmap = bitmap; // 位图变量替换
                    }
                }

                // 内部加载单张图片
                loadImageInternal(res, dirPath, playerIdx, bizType, new CommonCallbacks.SuccessErrorCallback() {
                    @Override
                    public void onResult(boolean success, String errMsg) {
                        synchronized (temp) {
                            if (temp[2] == 0) {
                                // 之前已经下载失败，不再处理后续结果
                                return;
                            }
                            if (!success) {
                                temp[2] = 0;
                                callback.onResult("loadFile(" + res.realUrl + ") fail," + errMsg);
                                return;
                            }

                            ++temp[1];
                            LogUtil.debug(TAG, "loadImage(" + res.realUrl + ") success");

                            // 所有图片加载完成
                            if (temp[0] == temp[1]) {
                                callback.onResult("");
                            }
                        }
                    }
                });
            }
        } catch (Exception e) {
            LogUtil.error(TAG, "loadImages..e:" + e.getMessage());
            temp[2] = 0;
            callback.onResult(e.getMessage());
        }
    }

    /**
     * 批量加载字体资源
     * 下载字体文件并建立URL到本地路径的映射表
     *
     * @param urlList 字体文件URL列表
     * @param bizType 业务类型（用于下载管理）
     * @param dirPath 基础目录路径
     * @param callback 批量加载结果回调，返回字体路径映射表
     */
    static void loadFonts(List<String> urlList, String bizType, String dirPath, final LoadFontListCallback callback) {
        if (urlList == null || urlList.isEmpty()) {
            TaskScheduleUtil.postToNormalThread(new Runnable() {
                @Override
                public void run() {
                    callback.onResult(null);
                }
            });
            return;
        }

        HashMap<String, String> fontPathMap = new HashMap<>();
        try {
            final int[] temp = { urlList.size(), 0, 1 }; // 0: 字体数量，1: 已加载数量，2: 是否成功

            for (int i = 0; i < urlList.size(); i++) {
                final String url = urlList.get(i);

                // 内部加载单个字体
                loadFontInternal(url, dirPath, bizType, new LoadFontInternalCallback() {
                    @Override
                    public void onResult(String fontPath, String errMsg) {
                        synchronized (temp) {
                            if (temp[2] == 0) {
                                // 之前已经加载失败，不再处理
                                return;
                            }
                            if (fontPath == null) {
                                temp[2] = 0;
                                callback.onResult(null);
                                return;
                            }

                            fontPathMap.put(url, fontPath);
                            ++temp[1];
                            LogUtil.debug(TAG, "loadFont (" + url + ") success");

                            // 所有字体加载完成
                            if (temp[0] == temp[1]) {
                                callback.onResult(fontPathMap);
                            }
                        }
                    }
                });
            }
        } catch (Exception e) {
            LogUtil.error(TAG, "loadFonts..e:" + e.getMessage());
            callback.onResult(null);
        } finally {
            LogUtil.debug(TAG, "loadFonts: " + Integer.toString(urlList.size()));
        }
    }

    /**
     * 内部方法：加载单个图片资源
     * 处理本地文件、网络文件、文本节点和视频资源的加载逻辑
     */
    private static void loadImageInternal(MarsNativeImageResource res, final String dirPath, final int playerIdx, String bizType, final CommonCallbacks.SuccessErrorCallback callback) {
        String url = res.realUrl;
        if (TextUtils.isEmpty(url)) {
            TaskScheduleUtil.postToNormalThread(new Runnable() {
                @Override
                public void run() {
                    callback.onResult(false, "url is null");
                }
            });
            return;
        }

        // 如果已经有位图数据，直接返回成功
        if (res.bitmap != null) {
            TaskScheduleUtil.postToNormalThread(new Runnable() {
                @Override
                public void run() {
                    callback.onResult(true, "");
                }
            });
            return;
        }

        // 处理文本节点的透明底图（特殊格式：text://width_height）
        if (url.startsWith("text://")) {
            String[] components = url.split("_");
            if (components.length == 3) {
                try {
                    float width = Float.parseFloat(components[1]);
                    float height = Float.parseFloat(components[2]);
                    res.bitmap = MarsNativeResourceLoader.generateTransparentImageWithWidth(width, height);
                    TaskScheduleUtil.postToNormalThread(new Runnable() {
                        @Override
                        public void run() { callback.onResult(true, ""); }
                    });
                } catch (Exception e) {
                    TaskScheduleUtil.postToNormalThread(new Runnable() {
                        @Override
                        public void run() {
                            callback.onResult(false, "url is text://xx_xx_xx, but failed to get width, height or bitmap.");
                        }
                    });
                }
            } else {
                TaskScheduleUtil.postToNormalThread(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(false, "url is wrong, text://xx_xx_xx expected.");
                    }
                });
            }
            return;
        }

        // 处理本地文件（非HTTP/HTTPS）
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            TaskScheduleUtil.postToNormalThread(new Runnable() {
                @Override
                public void run() {
                    String filePath = dirPath + File.separator + url;

                    if (res.isVideo) {
                        // 视频文件处理
                        if (new File(filePath).exists()) {
                            res.videoRes = new MarsNativeVideoResource(
                                    playerIdx, filePath, res.isTransparentVideo,
                                    res.videoHardDecoder, callback);
                        } else {
                            callback.onResult(false, "read video file failed");
                        }
                        return;
                    }

                    // 普通图片文件处理
                    InputStream is = null;
                    try {
                        Context context = ContextUtil.getApplicationContext();
                        if (filePath.startsWith(Constants.RES_PATH_ASSETS_PREFIX)) {
                            // 从assets读取
                            is = context.getAssets().open(filePath.substring(Constants.RES_PATH_ASSETS_PREFIX.length()));
                        } else {
                            // 从文件系统读取
                            is = new FileInputStream(filePath);
                        }
                        byte[] bytes = CommonUtil.readFileStreamBinaryAndClose(is);
                        res.data = bytes;
                    } catch (Exception e) {
                        LogUtil.error(TAG, "load local image..e:" + e.getMessage());
                        callback.onResult(false, e.getMessage());
                    }
                    callback.onResult(true, "");
                }
            });
            return;
        }

        // 处理网络文件（HTTP/HTTPS）
        DownloadUtil.downloadFile(url, bizType, null, new DownloadUtil.DownloadFileCallback() {
            @Override
            public void onSuccess(InputStream is, String filePath) {
                if (res.isVideo) {
                    // 视频文件下载完成
                    res.videoRes = new MarsNativeVideoResource(
                            playerIdx, filePath, res.isTransparentVideo, res.videoHardDecoder,
                            callback);
                    return;
                }

                // 普通图片文件下载完成
                res.data = CommonUtil.readFileStreamBinaryAndClose(is);
                callback.onResult(true, "");
            }

            @Override
            public void onError(String errMsg) {
                callback.onResult(false, errMsg);
            }
        });
    }

    /**
     * 内部方法：加载单个字体资源
     * 处理本地字体和网络字体的加载逻辑
     */
    private static void loadFontInternal(String url, final String dirPath, String bizType, final LoadFontInternalCallback callback) {
        if (TextUtils.isEmpty(url)) {
            TaskScheduleUtil.postToNormalThread(new Runnable() {
                @Override
                public void run() {
                    callback.onResult(null, "url is null");
                }
            });
            return;
        }

        // 处理本地字体文件
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            TaskScheduleUtil.postToNormalThread(new Runnable() {
                @Override
                public void run() {
                    String filePath = dirPath + File.separator + url;
                    if (new File(filePath).exists()) {
                        callback.onResult(filePath, "");
                    } else {
                        callback.onResult(null, "read font file failed");
                    }
                }
            });
            return;
        }

        // 处理网络字体文件
        DownloadUtil.downloadFile(url, bizType, null, new DownloadUtil.DownloadFileCallback() {
            @Override
            public void onSuccess(InputStream is, String filePath) {
                callback.onResult(filePath, "");
            }

            @Override
            public void onError(String errMsg) {
                callback.onResult(null, errMsg);
            }
        });
    }

    /**
     * 生成指定尺寸的透明位图
     * 用于文本节点的透明背景底图
     *
     * @param width 位图宽度
     * @param height 位图高度
     * @return 透明位图对象
     */
    public static Bitmap generateTransparentImageWithWidth(float width, float height) {
        return Bitmap.createBitmap((int) width, (int) height, Bitmap.Config.ARGB_8888);
    }

    /**
     * Mars原生视频资源封装类
     * 管理视频上下文的生命周期和相关回调
     */
    public static class MarsNativeVideoResource {
        public long videoContext = 0; // 视频上下文指针
        private CommonCallbacks.SuccessErrorCallback mComplete; // 完成回调

        /**
         * 构造函数
         * 创建视频上下文并准备播放
         */
        public MarsNativeVideoResource(int playerIdx, String filePath, boolean transparent, boolean hardDecoder, CommonCallbacks.SuccessErrorCallback complete) {
            mComplete = complete;
            WeakReference<MarsNativeVideoResource> weakThiz = new WeakReference<>(this);

            // 调用native方法创建视频上下文
            videoContext = JNIUtil.nativeCreateVideoContextAndPrepare(
                    playerIdx, filePath, MD5Util.getMD5String(filePath), transparent, hardDecoder,
                    new JNICallback(weakThiz));

            if (videoContext == 0) {
                LogUtil.error("MarsNativeVideoResource", "create video context fail");
                mComplete.onResult(false, "create video context fail");
                mComplete = null;
            }
        }

        @Override
        protected void finalize() throws Throwable {
            super.finalize();
            LogUtil.debug("MarsNativeVideoResource", "MarsNativeVideoResource finalize");
        }

        /**
         * JNI回调函数封装类
         * 弱引用持有，避免内存泄漏
         */
        private static class JNICallback extends CommonJNICallback {
            private final WeakReference<MarsNativeVideoResource> weakThiz;

            JNICallback(WeakReference<MarsNativeVideoResource> weakReference) {
                weakThiz = weakReference;
            }

            @Override
            public void onCallback(String[] params) {
                MarsNativeVideoResource thiz = weakThiz.get();
                if (thiz == null || thiz.mComplete == null) {
                    return;
                }

                LogUtil.debug("LoadVideoCallback", Arrays.toString(params));
                boolean success = params[0].equalsIgnoreCase("true");

                if (success) {
                    thiz.mComplete.onResult(true, "");
                } else {
                    thiz.mComplete.onResult(false, params[1]);
                }
                thiz.mComplete = null; // 清理回调引用
            }
        }
    }

    /**
     * Mars原生图片资源封装类
     * 描述单个图片资源的属性和状态
     */
    public static class MarsNativeImageResource {
        public String key;              // 资源键名
        public String realUrl;          // 实际URL（可能被变量替换）
        public boolean isVideo = false; // 是否为视频资源
        public boolean isTransparentVideo = false; // 是否为透明视频
        public boolean videoHardDecoder = false;    // 是否启用硬解码
        public MarsNativeVideoResource videoRes = null; // 视频资源对象
        public byte[] data = null;      // 图片二进制数据
        public Bitmap bitmap = null;    // 位图对象

        /**
         * 构造函数
         * @param url 初始URL
         */
        public MarsNativeImageResource(String url) {
            this.key = url;
            this.realUrl = url;
        }
    }

    /**
     * 位图加载回调接口
     */
    public interface LoadBitmapCallback {
        void onResult(Bitmap bitmap, String errMsg);
    }

    /**
     * ZIP压缩包加载回调接口
     */
    interface LoadZipCallback {
        void onSuccess(String dirPath);
        void onError(String errMsg);
    }

    /**
     * 图片列表加载回调接口
     */
    interface LoadImageListCallback {
        void onResult(String errMsg);
    }

    /**
     * 字体列表加载回调接口
     */
    interface LoadFontListCallback {
        void onResult(HashMap<String, String> fontPathMap);
    }

    /**
     * 字体内部加载回调接口
     */
    interface LoadFontInternalCallback {
        void onResult(String fontPath, String errMsg);
    }
}