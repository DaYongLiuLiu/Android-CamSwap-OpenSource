package io.github.zensu357.camswap;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Shared OpenGL ES constants and utilities for {@link GLVideoRenderer} and
 * {@link SurfaceRelay}.
 * Eliminates duplicated shader source, vertex data, and helper methods.
 */
public final class GLHelper {

    // ---- Shared Shader Sources ----

    public static final String VERTEX_SHADER = "uniform mat4 uSTMatrix;\n" +
            "uniform mat4 uRotMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uRotMatrix * aPosition;\n" +
            "    vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n";

    public static final String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform vec3 uAmbientColor;\n" +
            "uniform float uAmbientIntensity;\n" +
            "void main() {\n" +
            "    vec4 texColor = texture2D(sTexture, vTextureCoord);\n" +
            "    if (uAmbientIntensity > 0.0) {\n" +
            "        vec3 tinted = mix(texColor.rgb, texColor.rgb * (1.0 + uAmbientColor * uAmbientIntensity), 0.38);\n" +
            "        gl_FragColor = vec4(tinted, texColor.a);\n" +
            "    } else {\n" +
            "        gl_FragColor = texColor;\n" +
            "    }\n" +
            "}\n";

    // ---- Shared Geometry ----

    public static final float[] VERTICES = {
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f,
    };

    public static final float[] TEX_COORDS = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
    };

    private GLHelper() {
    } // no instances

    /**
     * Load, compile, and validate a GL shader.
     * 
     * @throws RuntimeException if compilation fails
     */
    public static int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + error);
        }
        return shader;
    }

    /**
     * Create a direct FloatBuffer from a float array.
     */
    public static FloatBuffer createFloatBuffer(float[] data) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(data).position(0);
        return buffer;
    }
}
