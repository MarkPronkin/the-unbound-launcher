#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stddef.h>
#include <stdio.h>
#include "pointer_state.h"

// Called only by UnityPlayer's GL-thread job queue. Use the shipped IL2CPP API;
// no process-memory writes, private structure offsets, or renderer binary hooks.
static void *(*domain_get)(void);
static const void **(*domain_get_assemblies)(void *, size_t *);
static const void *(*assembly_get_image)(const void *);
static void *(*class_from_name)(const void *, const char *, const char *);
static const void *(*class_get_method)(void *, const char *, int);
static void *(*runtime_invoke)(const void *, void *, void **, void **);
static void *(*object_unbox)(void *);
static void *(*thread_current)(void);
static const void *getters[POINTER_PROPERTIES], *setters[POINTER_PROPERTIES];
static PointerState state;
static const char *properties[POINTER_PROPERTIES] = {
    "RaycastersActive", "RaycastersUpdatePose", "ControllerVisualActive", "LaserVisualActive", "ReticleVisualActive"
};

static bool resolve(void) {
    if (getters[0]) return thread_current() != NULL;
    void *library = dlopen("libil2cpp.so", RTLD_NOW | RTLD_NOLOAD);
    if (!library) return false;
#define RESOLVE(variable, name) *(void **)(&variable) = dlsym(library, name); if (!variable) return false
    RESOLVE(domain_get, "il2cpp_domain_get");
    RESOLVE(domain_get_assemblies, "il2cpp_domain_get_assemblies");
    RESOLVE(assembly_get_image, "il2cpp_assembly_get_image");
    RESOLVE(class_from_name, "il2cpp_class_from_name");
    RESOLVE(class_get_method, "il2cpp_class_get_method_from_name");
    RESOLVE(runtime_invoke, "il2cpp_runtime_invoke");
    RESOLVE(object_unbox, "il2cpp_object_unbox");
    RESOLVE(thread_current, "il2cpp_thread_current");
#undef RESOLVE
    if (!thread_current()) return false;
    void *domain = domain_get();
    if (!domain) return false;
    size_t count = 0;
    const void **assemblies = domain_get_assemblies(domain, &count);
    void *type = NULL;
    for (size_t i = 0; i < count && !type; i++)
        type = class_from_name(assembly_get_image(assemblies[i]), "NREAL.XR", "XRInput");
    if (!type) return false;
    const void *get[POINTER_PROPERTIES], *set[POINTER_PROPERTIES];
    for (int i = 0; i < POINTER_PROPERTIES; i++) {
        char name[64];
        snprintf(name, sizeof(name), "get_%s", properties[i]);
        get[i] = class_get_method(type, name, 0);
        snprintf(name, sizeof(name), "set_%s", properties[i]);
        set[i] = class_get_method(type, name, 1);
        if (!get[i] || !set[i]) return false;
    }
    for (int i = 0; i < POINTER_PROPERTIES; i++) { getters[i] = get[i]; setters[i] = set[i]; }
    return true;
}

static bool read_property(void *context, int index, bool *value) {
    (void)context;
    void *exception = NULL;
    void *boxed = runtime_invoke(getters[index], NULL, NULL, &exception);
    if (exception || !boxed) return false;
    bool *unboxed = object_unbox(boxed);
    if (!unboxed) return false;
    *value = *unboxed;
    return true;
}

static bool write_property(void *context, int index, bool value) {
    (void)context;
    void *exception = NULL;
    void *args[] = { &value };
    runtime_invoke(setters[index], NULL, args, &exception);
    return !exception;
}

JNIEXPORT jint JNICALL Java_dev_beampro_port_PhonePointerCompat_nativeSetSuspended(JNIEnv *env, jclass cls, jboolean suspend) {
    (void)cls;
    const char *error = NULL;
    int mask = 0, saved = 0;
    if (!resolve()) error = "Pointer controls are unavailable on the Unity thread";
    else {
        PointerAccess access = { NULL, read_property, write_property };
        if (!(suspend ? pointer_suspend(&state, access) : pointer_restore(&state, access)))
            error = suspend ? "Could not pause the motion pointer" : "Could not restore the motion pointer";
        for (int i = 0; i < POINTER_PROPERTIES && !error; i++) {
            bool value;
            if (!read_property(NULL, i, &value)) { error = "Could not verify pointer state"; break; }
            if (value) mask |= 1 << i;
            if (state.saved[i]) saved |= 1 << i;
        }
    }
    if (error) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), error);
        return -1;
    }
    __android_log_print(ANDROID_LOG_INFO, "PortPhonePointer", "Motion pointer %s: flags=%d saved=%d",
                        suspend ? "suspended" : "restored", mask, saved);
    return mask;
}
