package com.innovator.imageloader.loader;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.innovator.imageloader.R;
import com.innovator.imageloader.utils.MyUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 加载图片工具类
 */
public class ImageLoader {

    private static final String TAG = "ImageLoader";

    public static final int MESSAGE_POST_RESULT = 1;

    private static final int CPU_COUNT = Runtime.getRuntime()
            .availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final long KEEP_ALIVE = 10L;

    private static final int TAG_KEY_URI = R.id.imageloader_uri;
    private static final long DISK_CACHE_SIZE = 1024 * 1024 * 50;
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private static final int DISK_CACHE_INDEX = 0;
    private boolean mIsDiskLruCacheCreated = false;

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        //实现并发加载图片，所以不用AsyncTask
        public Thread newThread(Runnable r) {
            return new Thread(r, "ImageLoader#" + mCount.getAndIncrement());
        }
    };

    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
            KEEP_ALIVE, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(), sThreadFactory);


    private Handler mMainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;
            String uri = (String) imageView.getTag(TAG_KEY_URI);

            //在给ImageView设置图片之前检查他的url有没有发生变化，没改变就设置图片
            if (uri.equals(result.uri)) {
                imageView.setImageBitmap(result.bitmap);
            } else {
                Log.w(TAG, "set image bitmap,but url has changed, ignored!");
            }
        }
    };

    private Context mContext;
    private ImageResizer mImageResizer = new ImageResizer();
    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;


    private ImageLoader(Context context){
        mContext = context;

        //内存缓存为进程最大可使用内存的 1/8,单位 KB
        int maxMemory = (int)(Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize){
            @Override
            protected int sizeOf(String key, Bitmap value) {
                //单位相同，转成 KB
                return value.getRowBytes() * value.getHeight() / 1024;
            }
        };

        File diskCacheDir = getDiskCacheDir(mContext,"bitmap");
        if(!diskCacheDir.exists()){
            diskCacheDir.mkdirs();
        }

        //创建磁盘缓存
        if(getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE){
            try {
                mDiskLruCache = DiskLruCache.open(diskCacheDir,1,1,DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;
            }catch (IOException e){
                e.printStackTrace();
            }
        }

    }

    /**
     * build a new instance of ImageLoader
     * @param context
     * @return a new instance of ImageLoader
     */
    public static ImageLoader build(Context context) {
        return new ImageLoader(context);
    }


    /**
     * 从内存缓存中获取 Bitmap
     * @param key
     * @return
     */
    private Bitmap getBitmapFromMemoryCache(String key){
       return mMemoryCache.get(key);
    }

    /**
     * 存储 Bitmap 到内存缓存
     * @param key
     * @param bitmap
     */
    private void addBitmapToMemoryCache(String key,Bitmap bitmap){
        if(null == getBitmapFromMemoryCache(key)){
            mMemoryCache.put(key,bitmap);
        }
    }

    /**
     * 从网络或者磁盘中异步加载 Bitmap
     * @param uri
     * @param imageView
     */
    public void bindBitmap(final String uri, final ImageView imageView) {
        bindBitmap(uri, imageView, 0, 0);
    }

    /**
     * 从网络或者磁盘中异步加载 Bitmap
     * @param uri
     * @param imageView
     * @param reqWidth
     * @param reqHeight
     */
    public void bindBitmap(final String uri, final ImageView imageView, final int reqWidth, final int reqHeight){
        //解决由于View复用导致的图片错位问题
        imageView.setTag(TAG_KEY_URI,uri);
        //首先从内存缓存中加载
        final Bitmap bitmap = loadBitmapFromMemCache(uri);
        if(bitmap != null){
            imageView.setImageBitmap(bitmap);
            return;
        }

        //线程池执行加载Bitmap任务
        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap1 = loadBitmap(uri,reqWidth,reqHeight);
                if(bitmap1 != null){
                    LoaderResult result = new LoaderResult(imageView,uri,bitmap1);
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT,result)
                            .sendToTarget();
                }
            }
        };

        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }

    /**
     * 同步加载图片
     * 外部需要在子线程中调用
     * @param url
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    public Bitmap loadBitmap(String url,int reqWidth,int reqHeight){
        //首先从内存缓存中获取
        Bitmap bitmap = loadBitmapFromMemCache(url);
        if(bitmap != null){
            Log.d(TAG,"loadBitmapFromMemCache，url："+url);
            return bitmap;
        }

        //内存缓存没有，尝试从磁盘缓存中加载，磁盘缓存没有就从网络加载
        try{
            bitmap = loadBitmapFromDiskCache(url, reqWidth,reqHeight);
            if (bitmap != null){
                Log.d(TAG,"loadBitmapFromDiskCache,url："+url);
                return bitmap;
            }

            //磁盘缓存没有，从网络中加载
            bitmap = loadBitmapFromHttp(url,reqWidth,reqHeight);
            Log.d(TAG,"loadBitmapFromHttp,url："+url);
        }catch (IOException e){
            e.printStackTrace();
        }

        //以防没有空间创建磁盘缓存
        if(bitmap == null && !mIsDiskLruCacheCreated){
            Log.w(TAG,"encounter error,DiskLruCache is not created!");
            bitmap = downloadBitmapFromUrl(url);
        }

        return bitmap;
    }

    /**
     *
     * @param url
     * @return
     */
    private Bitmap loadBitmapFromMemCache(String url) {
        final String key = hashKeyFromUrl(url);
        Bitmap bitmap = getBitmapFromMemoryCache(key);
        return bitmap;
    }

    /**
     * 从磁盘缓存中加载Bitmap
     * @param url
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    private Bitmap loadBitmapFromDiskCache(String url,int reqWidth,int reqHeight) throws IOException{
        //不能在主线程调用
        if(Looper.myLooper() == Looper.getMainLooper()){
            throw new RuntimeException("cannot visit network from UI Thread");
        }

        if(mDiskLruCache == null){
            return null;
        }

        Bitmap bitmap = null;
        String key = hashKeyFromUrl(url);
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        if (snapshot != null) {
            FileInputStream fileInputStream = (FileInputStream)snapshot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = mImageResizer.decodeSampleBitmapFromFileDescriptor(fileDescriptor,reqWidth,reqHeight);

            // 添加到内存缓存
            if (bitmap != null){
                addBitmapToMemoryCache(key,bitmap);
            }
        }

        return bitmap;
    }

    /**
     * 从网络中获取图片，存入磁盘缓存，然后加载到内存缓存中
     * @param url
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    private Bitmap loadBitmapFromHttp(String url,int reqWidth,int reqHeight) throws IOException{

        //不能在主线程调用
        if(Looper.myLooper() == Looper.getMainLooper()){
            throw new RuntimeException("cannot visit network from UI Thread");
        }

        if(mDiskLruCache == null){
            return null;
        }

        String key = hashKeyFromUrl(url);
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if(editor != null){
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);

            //存到磁盘缓存
            if(downloadUrlToStream(url,outputStream)){
                editor.commit();
            }else {
                editor.abort();
            }

            mDiskLruCache.flush();
        }

        return loadBitmapFromDiskCache(url,reqWidth,reqHeight);
    }

    /**
     * 从网络加载图片，转成流然后转成bitmap
     * @param urlString
     * @return
     */
    public Bitmap downloadBitmapFromUrl(String urlString) {
        Bitmap bitmap = null;
        HttpURLConnection urlConnection = null;
        BufferedInputStream in = null;

        try {
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(),
                    IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);
        } catch (final IOException e) {
            Log.e(TAG, "Error in downloadBitmap: " + e);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            MyUtils.close(in);
        }
        return bitmap;
    }

    /**
     * 从网络加载图片转成输出流
     * @param urlString
     * @param outputStream
     * @return
     */
    public boolean downloadUrlToStream(String urlString,OutputStream outputStream){
        HttpURLConnection urlConnection = null;
        BufferedInputStream in = null;
        BufferedOutputStream out = null;

        try{
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(),IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream,IO_BUFFER_SIZE);

            int b;
            while ((b = in.read()) != -1){
                out.write(b);
            }

            return true;

        }catch (IOException e){
            e.printStackTrace();
        }finally {
            if(urlConnection != null){
                urlConnection.disconnect();
            }

            MyUtils.close(in);
            MyUtils.close(out);
        }

        return false;
    }

    /**
     * 将 url 转换成磁盘缓存的 key
     * @param url
     * @return
     */
    private String hashKeyFromUrl(String url){
        String cacaheKey = null;
        try{
            //一个MD5转换器
            final MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            // 转换并返回结果，也是字节数组，包含16个元素
            messageDigest.update(url.getBytes());
            // 字符数组转换成字符串返回
            cacaheKey = bytesToHexString(messageDigest.digest());
        }catch (NoSuchAlgorithmException e){
            e.printStackTrace();
        }

        return cacaheKey;
    }

    /**
     * 字符数组转换成字符串
     * @param bytes
     * @return
     */
    private String bytesToHexString(byte[] bytes){
        StringBuilder sb = new StringBuilder();
        for(int i=0;i<bytes.length;i++){
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if(hex.length() == 1){
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    /**
     * 初始化磁盘缓存
     * 获取磁盘缓存的地址
     * @param context
     * @param uniqueName
     * @return
     */
    public File getDiskCacheDir(Context context, String uniqueName) {
        boolean externalStorageAvailable = Environment
                .getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        final String cachePath;
        if (externalStorageAvailable) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }

        Log.d(TAG,"磁盘缓存地址："+cachePath);
        //磁盘缓存地址：/storage/emulated/0/Android/data/com.innovator.imageloader/cache
        return new File(cachePath + File.separator + uniqueName);
    }

    /**
     * 初始化磁盘缓存
     * 获取可用的存储空间
     * @param path
     * @return
     */
    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    private long getUsableSpace(File path) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            return path.getUsableSpace();
        }
        final StatFs stats = new StatFs(path.getPath());
        return (long) stats.getBlockSize() * (long) stats.getAvailableBlocks();
    }


    private static class LoaderResult {
        public ImageView imageView;
        public String uri;
        public Bitmap bitmap;

        public LoaderResult(ImageView imageView, String uri, Bitmap bitmap) {
            this.imageView = imageView;
            this.uri = uri;
            this.bitmap = bitmap;
        }
    }
}
