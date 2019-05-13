/*
 * Copyright (C) 2019 jjoeyang. All Rights Reserved.
 */
package com.yzz.cpucollector;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.yzz.cpucollector.util.BDCameraSyncRenderer;
import com.yzz.cpucollector.util.ShaderUtil;

import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;


public class CameraActivity extends Activity {

    // 渲染视图及相关参数
    private GLSurfaceView mSurfaceView;
    private SurfaceTexture mSurfaceTexture;
    private int mTextureId;
    private BDCameraSyncRenderer mBDCameraSyncRenderer; // 相机渲染器
    private byte[] mCameraData;

    private Camera mCamera;              // 相机
    private boolean isCameraFront = false;
    private ImageView mSwitchCamera;

    // 百度AR 开放API主要类实例及配置参数
    private int mPreviewWidth = 1280;   // 相机预览宽
    private int mPreviewHeight = 720;   // 相机预览高

    // 相机帧渲染MVP矩阵（实现翻转、缩放显示等）
    private float[] mCameraMVPMatrix = {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f};

    // 权限请求相关
    private static final String[] ALL_PERMISSIONS = new String[]{
            Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA};
    private static final int REQUEST_CODE_ASK_ALL_PERMISSIONS = 154;
    private boolean mIsDenyAllPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        // 请求权限
        requestAllPermissions(REQUEST_CODE_ASK_ALL_PERMISSIONS);

        mSwitchCamera = findViewById(R.id.iv_camera);
        mSwitchCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchCamera();
            }
        });

        initRender();

        CPUCollector.getInstance().setPkgName(this.getPackageName());
    }

    private void initRender() {
        mSurfaceView = findViewById(R.id.surfaceview);

        // 渲染相关准备工作
        mSurfaceView.setPreserveEGLContextOnPause(true);
        mSurfaceView.setEGLContextClientVersion(2);
        mSurfaceView.setRenderer(new GLSurfaceView.Renderer() {
            @Override
            public void onSurfaceCreated(GL10 gl, EGLConfig config) {
                GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
                if (mSurfaceTexture == null) {
                    mTextureId = ShaderUtil.createTexture(true);
                    mSurfaceTexture = new SurfaceTexture(mTextureId);
                    mBDCameraSyncRenderer = new BDCameraSyncRenderer();
                }
                startCamera(mSurfaceTexture, isCameraFront);
            }

            @Override
            public void onSurfaceChanged(GL10 gl, int width, int height) {
                GLES20.glViewport(0, 0, width, height);

                if (mBDCameraSyncRenderer != null) {
                    mBDCameraSyncRenderer.setInputOutputParam(mPreviewWidth, mPreviewHeight, width, height);
                }
            }

            @Override
            public void onDrawFrame(GL10 gl) {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
                mSurfaceTexture.updateTexImage();

                // 相机帧数据渲染
                if (mCameraData != null) {
                    mBDCameraSyncRenderer.draw(mCameraMVPMatrix, mCameraData);
                }
            }
        });
        mSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    private Camera.PreviewCallback mPreviewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            if (cameraTimeStamp > 0) {
                calculateCameraTime((System.currentTimeMillis() - cameraTimeStamp));
            }
            cameraTimeStamp = System.currentTimeMillis();
            mCameraData = data;
            if (mSurfaceView != null) {
                mSurfaceView.requestRender();
            }
        }
    };

    // 统计帧率相关变量及方法
    private int cameraFrameTimes = 0;
    private int maxFrameCount = 30; // 统计的帧数
    private double cameraFrameAVGTime;
    private long cameraTimeStamp;

    private void calculateCameraTime(double detectTime) {
        if (cameraFrameTimes >= maxFrameCount) {
            if (cameraFrameTimes == maxFrameCount) {
                Log.e("duguju", "相机输入帧率:" + (1000.0f / cameraFrameAVGTime));
                Log.e("duguju-cpu", "当前CPU占用:" + CPUCollector.getInstance().getCPURate() + "%"
                        + "  平均:" + CPUCollector.getInstance().getAvgCPU() + "%");
                cameraFrameTimes++;
            }
            cameraFrameTimes = 0;
            cameraFrameAVGTime = 0;
            return;
        }
        cameraFrameTimes++;
        double allTime = (cameraFrameTimes - 1) * cameraFrameAVGTime;
        cameraFrameAVGTime = (allTime + detectTime) / cameraFrameTimes;
    }

    /**
     * 开启相机
     *
     * @param surfaceTexture
     * @param isFront
     */
    private void startCamera(SurfaceTexture surfaceTexture, boolean isFront) {
        try {
            mCamera = getCameraInstance(isFront);
            if (mCamera == null) {
                startCamera(mSurfaceTexture, isFront);
                return;
            }
            Camera.Parameters params = mCamera.getParameters();
            params.setPreviewSize(mPreviewWidth, mPreviewHeight);
            if (!isFront) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }

            mCamera.setParameters(params);
//            mCamera.setPreviewDisplay(mHolder);
            mCamera.setPreviewTexture(surfaceTexture);
            mCamera.setDisplayOrientation(90);
            Matrix.setIdentityM(mCameraMVPMatrix, 0);
            if (isFront) {
                Matrix.rotateM(mCameraMVPMatrix, 0, 90, 0, 0, 1);
            } else {
                Matrix.rotateM(mCameraMVPMatrix, 0, 180f, 0f, 1f, 0f);
                Matrix.rotateM(mCameraMVPMatrix, 0, -90, 0, 0, 1);
            }
            mCamera.setPreviewCallback(mPreviewCallback);
            mCamera.startPreview();
            mCamera.cancelAutoFocus();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Camera getCameraInstance(boolean isFront) {
        Camera c = null;
        try {
            if (isFront) {
                c = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
            } else {
                c = Camera.open();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return c;
    }

    private void switchCamera() {
        releaseCamera();
        isCameraFront = !isCameraFront;
        startCamera(mSurfaceTexture, isCameraFront);
    }

    private boolean mPause = false;

    @Override
    protected void onResume() {
        super.onResume();
        if (mSurfaceView != null) {
            mSurfaceView.onResume();
        }
        if (mPause) {
            mPause = false;
            startCamera(mSurfaceTexture, false);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mSurfaceView != null) {
            mSurfaceView.onPause();
        }
        releaseCamera();
        mPause = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
        CPUCollector.getInstance().release();
    }

    public void releaseCamera() {
        if (null != mCamera) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_ASK_ALL_PERMISSIONS) {
            mIsDenyAllPermission = false;
            for (int i = 0; i < permissions.length; i++) {
                if (i >= grantResults.length || grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    mIsDenyAllPermission = true;
                    break;
                }
            }
            if (mIsDenyAllPermission) {
                Toast.makeText(CameraActivity.this, "缺少权限，请检查！", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    /**
     * 请求权限
     *
     * @param requestCode
     */
    private void requestAllPermissions(int requestCode) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                List<String> permissionsList = getRequestPermissions(this);
                if (permissionsList.size() == 0) {
                    return;
                }
                if (!mIsDenyAllPermission) {
                    requestPermissions(permissionsList.toArray(new String[permissionsList.size()]),
                            requestCode);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static List<String> getRequestPermissions(Activity activity) {
        List<String> permissionsList = new ArrayList();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (String permission : ALL_PERMISSIONS) {
                if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    permissionsList.add(permission);
                }
            }
        }
        return permissionsList;
    }
}
