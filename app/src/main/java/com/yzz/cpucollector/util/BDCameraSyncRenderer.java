package com.yzz.cpucollector.util;

import android.opengl.GLES20;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 相机帧（同步）渲染器
 * 输入相机yuv数据后执行同步渲染
 */
public class BDCameraSyncRenderer {

    private static final String TAG = BDCameraSyncRenderer.class.getSimpleName();

    /**
     * fragment shader，分别传递Y、UV两个纹理给GPU，进行渲染
     */
    private static final String FRAGMENT_SHADER_NV212BGR =
            "#ifdef GL_ES\n"
                    + "precision highp float;\n"
                    + "#endif\n"
                    + "varying vec2 textureCoordinate;\n"
                    + "uniform sampler2D luminanceTexture;\n"
                    + "uniform sampler2D chrominanceTexture;\n"
                    + "void main (void){\n"
                    + "   float r, g, b, y, u, v;\n"
                    // We had put the Y values of each pixel to the R,G,B components by
                    // GL_LUMINANCE, that's why we're pulling it from the R component,
                    // we could also use G or B
                    + "   y = texture2D(luminanceTexture, textureCoordinate).r;\n"
                    // We had put the U and V values of each pixel to the A and R,G,B
                    // components of the texture respectively using GL_LUMINANCE_ALPHA.
                    // Since U,V bytes are interspread in the texture, this is probably
                    // the fastest way to use them in the shader
                    + "   u = texture2D(chrominanceTexture, textureCoordinate).a - 0.5;\n"
                    + "   v = texture2D(chrominanceTexture, textureCoordinate).r - 0.5;\n"
                    // The numbers are just YUV to RGB conversion constants
                    + "   r = y + 1.13983*v;\n"
                    + "   g = y - 0.39465*u - 0.58060*v;\n"
                    + "   b = y + 2.03211*u;\n"
                    // We finally set the RGB color of our pixel
                    + "   gl_FragColor = vec4(r, g, b, 1.0);\n"
                    + "}\n";

    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;"
                    + "uniform mat4 uTexMatrix;"
                    + "attribute vec4 position;"
                    + "attribute vec4 inputTextureCoordinate;"
                    + "varying vec2 textureCoordinate;"
                    + "void main() {"
                    + "    gl_Position = uMVPMatrix * position;"
                    + "    textureCoordinate = (uTexMatrix * inputTextureCoordinate).xy;"
                    + "}";

    private int mProgramHandle; // 程序句柄

    private int mUniformInputYTexture;          // y通道纹理句柄
    private int mUniformInputUVTexture;         // uv通道纹理句柄
    private int[] mYUVTextureIds = {-1, -1};    // yuv纹理id
    private ByteBuffer[] mYUVPlanes = null;     // yuv数据buffer

    private int mInputWidth;    // 输入宽（即相机预览宽）
    private int mInputHeight;   // 输入高
    private int mOutputWidth;   // 输出宽（即绘制宽）
    private int mOutputHeight;  // 输出高

    private int mAttribPosition;     // 位置矩阵句柄
    private FloatBuffer mPositionArray;
    private static final float[] FULL_RECTANGLE_COORDS = {
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f,
    };
    private int mAttribTextureCoord; // 纹理矩阵句柄
    private FloatBuffer mTexCoordArray;
    private static final float[] FULL_RECTANGLE_TEX_COORDS = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
    };
    private int mUniformMVPMatrix;   // 位置变换矩阵句柄
    private float[] mMvpMatrix = {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f};
    private int mUniformTexMatrix;   // 纹理变换矩阵句柄
    private float[] mTexMatrix = {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f};

    public BDCameraSyncRenderer() {
        mProgramHandle = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_NV212BGR);
        if (mProgramHandle == 0) {
            return;
        }
        mAttribPosition = GLES20.glGetAttribLocation(mProgramHandle, "position");
        mAttribTextureCoord = GLES20.glGetAttribLocation(mProgramHandle, "inputTextureCoordinate");
        mUniformMVPMatrix = GLES20.glGetUniformLocation(mProgramHandle, "uMVPMatrix");
        mUniformTexMatrix = GLES20.glGetUniformLocation(mProgramHandle, "uTexMatrix");
        mUniformInputYTexture = GLES20.glGetUniformLocation(mProgramHandle, "luminanceTexture");
        mUniformInputUVTexture = GLES20.glGetUniformLocation(mProgramHandle, "chrominanceTexture");
        mTexCoordArray = createFloatBuffer(FULL_RECTANGLE_TEX_COORDS);
        mPositionArray = createFloatBuffer(FULL_RECTANGLE_COORDS);
    }

    /**
     * 设置输入输出宽高
     *
     * @param inputWidth   输入宽度（相机预览宽）
     * @param inputHeight  输入高度
     * @param outputWidth  输出宽度（绘制的视图宽）
     * @param outputHeight 输出高度
     */
    public void setInputOutputParam(int inputWidth, int inputHeight, int outputWidth, int outputHeight) {
        mInputWidth = inputWidth;
        mInputHeight = inputHeight;
        mOutputWidth = outputWidth;
        mOutputHeight = outputHeight;

        int sW = mInputWidth > mInputHeight ? mInputHeight : mInputWidth;
        int sH = mInputWidth > mInputHeight ? mInputWidth : mInputHeight;
        int tW = mOutputWidth;
        int tH = mOutputHeight;
        int sourceW = Math.min(sW, sH);
        int sourceH = Math.max(sW, sH);
        int targetW = Math.min(tW, tH);
        int targetH = Math.max(tW, tH);
        float widthRatio = targetW * 1.0f / sourceW;
        float heightRatio = targetH * 1.0f / sourceH;

        boolean clipCameraWidth;
        float ratio;
        if (Float.compare(heightRatio, widthRatio) < 0) {
            clipCameraWidth = true;
            ratio = heightRatio / widthRatio;
        } else {
            clipCameraWidth = false;
            ratio = widthRatio / heightRatio;
        }
        setClipParam(clipCameraWidth, (1 - ratio) / 2);
    }

    /**
     * 设置纹理裁剪参数
     *
     * @param isClipWidth true代表裁剪宽，false代表裁剪高
     * @param clipRatio   裁剪部分占整体的比例
     */
    private void setClipParam(boolean isClipWidth, float clipRatio) {
        float[] textureCoord = new float[FULL_RECTANGLE_TEX_COORDS.length];
        System.arraycopy(FULL_RECTANGLE_TEX_COORDS, 0, textureCoord, 0, FULL_RECTANGLE_TEX_COORDS.length);
        if (isClipWidth) {
            textureCoord[0] = clipRatio;
            textureCoord[2] = 1 - clipRatio;
            textureCoord[4] = clipRatio;
            textureCoord[6] = 1 - clipRatio;
            mTexCoordArray.position(0);
            mTexCoordArray.put(textureCoord, 0, textureCoord.length);
            mTexCoordArray.position(0);
        } else {
            textureCoord[1] = clipRatio;
            textureCoord[3] = clipRatio;
            textureCoord[5] = 1 - clipRatio;
            textureCoord[7] = 1 - clipRatio;
            mTexCoordArray.position(0);
            mTexCoordArray.put(textureCoord, 0, textureCoord.length);
            mTexCoordArray.position(0);
        }
    }

    /**
     * 渲染相机帧
     *
     * @param matrix 变换矩阵
     * @param data   相机帧数据
     */
    public void draw(float[] matrix, byte[] data) {
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glViewport(0, 0, mOutputWidth, mOutputHeight);
        mMvpMatrix = matrix;
        update(data);
        buildTextures();
        drawFrame();
    }

    /**
     * 拆分camera输出的默认NV21格式的preview数据
     *
     * @param data
     */
    private void update(byte[] data) {
        if (data == null) {
            return;
        }
        int ySize = mInputWidth * mInputHeight;
        if (mYUVPlanes == null) {
            mYUVPlanes = new ByteBuffer[2];
        }

        if (mYUVPlanes[0] == null || mYUVPlanes[0].capacity() != ySize) {
            mYUVPlanes[0] = ByteBuffer.allocateDirect(ySize);
        }
        if (mYUVPlanes[1] == null || mYUVPlanes[1].capacity() != ySize / 2) {
            mYUVPlanes[1] = ByteBuffer.allocateDirect(ySize / 2);
        }

        mYUVPlanes[0].put(data, 0, ySize);
        mYUVPlanes[1].put(data, ySize, ySize / 2);

        mYUVPlanes[0].position(0);
        mYUVPlanes[1].position(0);
    }

    /**
     * 创建纹理
     */
    private void buildTextures() {
        int size = mYUVTextureIds.length;
        for (int i = 0; i < size; i++) {
            if (mYUVTextureIds[i] < 0) {
                int w = (i == 0) ? mInputWidth : mInputWidth / 2;
                int h = (i == 0) ? mInputHeight : mInputHeight / 2;
                int[] textures = new int[1];
                GLES20.glGenTextures(1, textures, 0);
                ShaderUtil.checkGLError(TAG, "glGenTextures");
                mYUVTextureIds[i] = textures[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYUVTextureIds[i]);
                if (i == 0) {
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0,
                            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
                } else {
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, w, h, 0,
                            GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, null);
                }
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            }
        }
    }

    /**
     * 渲染帧
     */
    private void drawFrame() {
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        GLES20.glUseProgram(mProgramHandle);
        ShaderUtil.checkGLError(TAG, "glUseProgram");

        GLES20.glUniformMatrix4fv(mUniformMVPMatrix, 1, false, mMvpMatrix, 0);
        GLES20.glUniformMatrix4fv(mUniformTexMatrix, 1, false, mTexMatrix, 0);

        GLES20.glEnableVertexAttribArray(mAttribPosition);
        GLES20.glVertexAttribPointer(mAttribPosition, 2, GLES20.GL_FLOAT, false, 8, mPositionArray);

        GLES20.glEnableVertexAttribArray(mAttribTextureCoord);
        GLES20.glVertexAttribPointer(mAttribTextureCoord, 2, GLES20.GL_FLOAT, false, 8, mTexCoordArray);

        int size = mYUVTextureIds.length;
        for (int i = 0; i < size; i++) {
            int w = (i == 0) ? mInputWidth : mInputWidth / 2;
            int h = (i == 0) ? mInputHeight : mInputHeight / 2;
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYUVTextureIds[i]);
            if (i == 0) {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_LUMINANCE,
                        GLES20.GL_UNSIGNED_BYTE, mYUVPlanes[i]);
            } else {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_LUMINANCE_ALPHA,
                        GLES20.GL_UNSIGNED_BYTE, mYUVPlanes[i]);
            }
            int handle = -1;
            switch (i) {
                case 0:
                    handle = mUniformInputYTexture;
                    break;
                case 1:
                    handle = mUniformInputUVTexture;
                    break;
                default:
                    break;
            }
            if (checkLocation(handle)) {
                GLES20.glUniform1i(handle, i);
            }
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(mAttribPosition);
        GLES20.glDisableVertexAttribArray(mAttribTextureCoord);
        GLES20.glUseProgram(0);
        unbindTextures();
    }

    private void unbindTextures() {
        int num = mYUVTextureIds.length;
        for (int i = 0; i < num; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    private boolean checkLocation(int handle) {
        if (handle >= 0) {
            return true;
        }
        return false;
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            return 0;
        }
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        if (program == 0) {
            Log.e(TAG, "Could not create program");
        }
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, pixelShader);
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        return program;
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }

    private FloatBuffer createFloatBuffer(float[] coords) {
        ByteBuffer bb = ByteBuffer.allocateDirect(coords.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(coords);
        fb.position(0);
        return fb;
    }
}
