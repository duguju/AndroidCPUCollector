package com.yzz.cpucollector.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Shader helper functions.
 */
public class ShaderUtil {
    /**
     * Converts a raw text file, saved as a resource, into an OpenGL ES shader.
     *
     * @param type     The type of shader we will be creating.
     * @param filename The filename of the asset file about to be turned into a shader.
     * @return The shader object handler.
     */
    public static int loadGLShader(String tag, Context context, int type, String filename)
            throws IOException {
        String code = readRawTextFileFromAssets(context, filename);
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);

        // Get the compilation status.
        final int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);

        // If the compilation failed, delete the shader.
        if (compileStatus[0] == 0) {
            Log.e(tag, "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }

        if (shader == 0) {
            throw new RuntimeException("Error creating shader.");
        }

        return shader;
    }

    /**
     * Checks if we've had an error inside of OpenGL ES, and if so what that error is.
     *
     * @param label Label to report in case of error.
     * @throws RuntimeException If an OpenGL error is detected.
     */
    public static void checkGLError(String tag, String label) {
        int lastError = GLES20.GL_NO_ERROR;
        // Drain the queue of all errors.
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(tag, label + ": glError " + error);
            lastError = error;
        }
        if (lastError != GLES20.GL_NO_ERROR) {
            throw new RuntimeException(label + ": glError " + lastError);
        }
    }

    /**
     * Converts a raw text file into a string.
     *
     * @param filename The filename of the asset file about to be turned into a shader.
     * @return The context of the text file, or null in case of error.
     */
    private static String readRawTextFileFromAssets(Context context, String filename)
            throws IOException {
        try (InputStream inputStream = context.getAssets().open(filename);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }


    public static int createTexture(boolean cameraTexture) {
        int textureTarget = GLES20.GL_TEXTURE_2D;
        if (cameraTexture) {
            textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
        }
        return createTexture(textureTarget, null, GLES20.GL_LINEAR, GLES20.GL_LINEAR,
                GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_CLAMP_TO_EDGE);
    }

    public static int createTexture(int textureTarget, Bitmap bitmap, int minFilter,
                                    int magFilter, int wrapS, int wrapT) {
        int[] textureHandle = createTextures(textureTarget, 1, minFilter, magFilter, wrapS, wrapT);

        if (bitmap != null) {
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        }

        checkGLError("createTextures", "glTexParameter");
        return textureHandle[0];
    }

    /**
     * @param textureTarget Texture类型。
     *                      1. 相机用 GLES11Ext.GL_TEXTURE_EXTERNAL_OES
     *                      2. 图片用 GLES20.GL_TEXTURE_2D
     * @param count         创建纹理数量
     * @param minFilter     缩小过滤类型 (1.GL_NEAREST ; 2.GL_LINEAR)
     * @param magFilter     放大过滤类型
     * @param wrapS         X方向边缘环绕
     * @param wrapT         Y方向边缘环绕
     * @return 返回创建的 Texture ID
     */
    public static int[] createTextures(int textureTarget, int count, int minFilter, int magFilter, int wrapS,
                                       int wrapT) {
        int[] textureHandles = new int[count];

        for (int i = 0; i < count; i++) {
            GLES20.glGenTextures(1, textureHandles, i);
            checkGLError("createTextures", "glGenTextures");
            GLES20.glBindTexture(textureTarget, textureHandles[i]);
            checkGLError("createTextures", "glBindTexture " + textureHandles[i]);
            GLES20.glTexParameterf(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, minFilter);
            GLES20.glTexParameterf(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, magFilter); // 线性插值
            GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, wrapS);
            GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, wrapT);
        }

        return textureHandles;
    }
}
