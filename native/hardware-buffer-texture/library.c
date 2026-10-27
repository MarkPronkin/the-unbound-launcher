#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <jni.h>
#include <string.h>

#define EGL_EGLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

/* The original library calls this pair. It is not a texture parameter, so it
 * leaves GL_INVALID_ENUM and does not select a filter. Keep it. */
#define ORIGINAL_TEX_PARAM 0x8FBF
#define ORIGINAL_TEX_VALUE 0x8C40

#define EXPORT __attribute__((visibility("default")))

static JavaVM* gJavaVM;
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC gTargetTexture;
static int gTargetReady;
static int gSamplingLogged;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    gJavaVM = vm;
    __android_log_print(ANDROID_LOG_INFO, "HardwareBufferTexture",
                        "Recents preview texture library loaded");
    return JNI_VERSION_1_6;
}

static void complete_texture(unsigned texture) {
    /* Default minification is GL_NEAREST_MIPMAP_LINEAR. This image has no
     * mipmaps, so the texture is incomplete and samples as black. v=0 stays
     * the top of the window, which is how this EGL image is addressed. */
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);
}

EXPORT int TransformHardwareBufferToTexture(jobject hardwareBuffer, int* width, int* height) {
    if (!gTargetReady) {
        gTargetReady = 1;
        gTargetTexture = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress(
            "glEGLImageTargetTexture2DOES");
    }
    if (!gTargetTexture) {
        __android_log_print(ANDROID_LOG_ERROR, "HardwareBufferTexture",
                            "Error: Failed to find glEGLImageTargetTexture2DOES at %s:%d",
                            "D:\\Project\\CLion\\HardwareBufferTexture\\library.cpp", 54);
        return 0;
    }
    JNIEnv* env = NULL;
    (*gJavaVM)->AttachCurrentThread(gJavaVM, &env, NULL);
    AHardwareBuffer* nativeBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    AHardwareBuffer_Desc desc;
    memset(&desc, 0, sizeof(desc));
    AHardwareBuffer_describe(nativeBuffer, &desc);
    *width = (int)desc.width;
    *height = (int)desc.height;
    EGLClientBuffer client = eglGetNativeClientBufferANDROID(nativeBuffer);
    const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLDisplay display = eglGetCurrentDisplay();
    EGLImageKHR image = eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, attributes);
    if (!image) {
        __android_log_print(ANDROID_LOG_ERROR, "HardwareBufferTexture", "Failed to create EGLImage.");
        return 0;
    }
    unsigned texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, ORIGINAL_TEX_PARAM, ORIGINAL_TEX_VALUE);
    gTargetTexture(GL_TEXTURE_2D, image);
    glBindTexture(GL_TEXTURE_2D, 0);
    eglDestroyImageKHR(eglGetCurrentDisplay(), image);
    complete_texture(texture);
    if (!gSamplingLogged) {
        gSamplingLogged = 1;
        __android_log_print(ANDROID_LOG_INFO, "HardwareBufferTexture",
                            "Recents preview texture uses linear sampling");
    }
    return (int)texture;
}

EXPORT void DeleteNativeTexture(int textureId) {
    if (!textureId) return;
    unsigned name = (unsigned)textureId;
    glDeleteTextures(1, &name);
}
