package com.innovator.imageloader.loader;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.FileDescriptor;

/**
 * 图片压缩工具类
 */
public class ImageResizer {

    private static final String TAG = "ImageResizer";

    public ImageResizer(){

    }

    /**
     * 加载指定大小的图片
     * @param res
     * @param resId
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    public Bitmap decodeSampleBitmapFromResource(Resources res,int resId,int reqWidth,int reqHeight){

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        //解析图片的原始宽高，并不会真正加载图片
        BitmapFactory.decodeResource(res,resId,options);

        //计算采样率
        options.inSampleSize = calculateInSampleSize(options,reqWidth,reqHeight);

        options.inJustDecodeBounds = false;
        //真正加载图片
        return BitmapFactory.decodeResource(res,resId, options);
    }

    /**
     * 加载指定大小的图片
     * @param fd
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    public Bitmap decodeSampleBitmapFromFileDescriptor(FileDescriptor fd,int reqWidth,int reqHeight){

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        //解析图片的原始宽高，并不会真正加载图片
        BitmapFactory.decodeFileDescriptor(fd,null,options);

        //计算采样率
        options.inSampleSize = calculateInSampleSize(options,reqWidth,reqHeight);

        options.inJustDecodeBounds = false;
        //真正加载图片
        return BitmapFactory.decodeFileDescriptor(fd,null, options);
    }

    /**
     * 计算采样率
     * @param options
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    public int calculateInSampleSize(BitmapFactory.Options options,int reqWidth,int reqHeight){
        //不需要压缩
        if(reqWidth == 0 || reqHeight == 0){
            return 1;
        }

        //实际宽高
        final int height = options.outHeight;
        final int width = options.outWidth;
        Log.d(TAG,"图片实际宽："+width+"，实际图片高："+height);

        int sampleSize = 1;

        if(height > reqHeight || width > reqWidth){
            final int halfHeight = height / 2;
            final int halfWidth = height / 2;

            //保证宽高 / 采样率  >  要求的宽高 (同时符合)
            while ((halfHeight / sampleSize) > reqHeight && (halfWidth / sampleSize) > reqHeight ){
                sampleSize = sampleSize * 2;
            }

            Log.d(TAG,"采样率："+ sampleSize);

        }

        return sampleSize;

    }
}
