#include <jni.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>

// Sample only existing Unity state, on UnityPlayer's job thread. No SDK mode
// commands, native binary hooks, private structure offsets, or pixel readback.
static void *(*domain_get)(void);
static const void **(*domain_get_assemblies)(void *, size_t *);
static const void *(*assembly_get_image)(const void *);
static void *(*class_from_name)(const void *, const char *, const char *);
static void *(*class_get_parent)(void *);
static void *(*class_get_nested_types)(void *, void **);
static const char *(*class_get_name)(void *);
static int32_t (*class_value_size)(void *, uint32_t *);
static const void *(*class_get_method_from_name)(void *, const char *, int);
static void *(*class_get_field_from_name)(void *, const char *);
static void *(*field_get_value_object)(void *, void *);
static void *(*runtime_invoke)(const void *, void *, void **, void **);
static void *(*object_unbox)(void *);
static void *(*object_get_class)(void *);
static void *(*value_box)(void *, void *);
static void *(*thread_current)(void);
static void *library;
static bool ready;
static char output[16000];
static size_t used;

static void emit(const char *format, ...) {
    if (used >= sizeof(output) - 1) return;
    va_list args; va_start(args, format);
    int size = vsnprintf(output + used, sizeof(output) - used, format, args);
    va_end(args);
    if (size > 0) used += (size_t)size < sizeof(output) - used ? (size_t)size : sizeof(output) - used - 1;
}

static bool resolve(void) {
    if (ready) return thread_current() != NULL;
    if (!library) library = dlopen("libil2cpp.so", RTLD_NOW | RTLD_NOLOAD);
    if (!library) return false;
#define RESOLVE(name) *(void **)(&name) = dlsym(library, "il2cpp_" #name); if (!name) return false
    RESOLVE(domain_get); RESOLVE(domain_get_assemblies); RESOLVE(assembly_get_image);
    RESOLVE(class_from_name); RESOLVE(class_get_parent); RESOLVE(class_get_nested_types);
    RESOLVE(class_get_name); RESOLVE(class_value_size); RESOLVE(class_get_method_from_name);
    RESOLVE(class_get_field_from_name); RESOLVE(field_get_value_object); RESOLVE(runtime_invoke);
    RESOLVE(object_unbox); RESOLVE(object_get_class); RESOLVE(value_box); RESOLVE(thread_current);
#undef RESOLVE
    ready = true;
    return thread_current() != NULL;
}

static void *type(const char *ns, const char *name) {
    void *domain = domain_get();
    if (!domain) return NULL;
    size_t count = 0;
    const void **assemblies = domain_get_assemblies(domain, &count);
    for (size_t i = 0; assemblies && i < count && i < 512; i++) {
        void *klass = class_from_name(assembly_get_image(assemblies[i]), ns, name);
        if (klass) return klass;
    }
    return NULL;
}

static const void *method(void *klass, const char *name, int count) {
    for (; klass; klass = class_get_parent(klass)) {
        const void *found = class_get_method_from_name(klass, name, count);
        if (found) return found;
    }
    return NULL;
}

static void *call(void *klass, void *object, const char *name, int count, void **args, bool *ok) {
    *ok = false;
    const void *target = method(klass, name, count);
    if (!target) { emit("%s=unavailable (method missing)\n", name); return NULL; }
    void *exception = NULL;
    void *result = runtime_invoke(target, object, args, &exception);
    if (exception) { emit("%s=unavailable (managed exception)\n", name); return NULL; }
    *ok = true;
    return result;
}

static void *field(void *klass, void *object, const char *name) {
    for (; klass; klass = class_get_parent(klass)) {
        void *found = class_get_field_from_name(klass, name);
        if (found) return field_get_value_object(found, object);
    }
    return NULL;
}

static int size_of(void *klass) { return klass ? class_value_size(klass, NULL) : 0; }

static bool number(void *boxed, int *value) {
    if (!boxed || size_of(object_get_class(boxed)) != 4) return false;
    memcpy(value, object_unbox(boxed), 4); return true;
}

static void show(const char *label, void *boxed) {
    emit("%s=", label);
    if (!boxed) { emit("unavailable\n"); return; }
    void *klass = object_get_class(boxed);
    const char *name = class_get_name(klass);
    void *data = object_unbox(boxed);
    int size = size_of(klass);
    if (!strcmp(name, "Boolean") && size == 1) emit("%s", *(uint8_t *)data ? "true" : "false");
    else if (!strcmp(name, "Single") && size == 4) { float f; memcpy(&f, data, 4); emit("%.7g", (double)f); }
    else if (size == 4) { int n; memcpy(&n, data, 4); emit("%d", n); }
    else if ((!strcmp(name, "Rect") && size == 16) || (!strcmp(name, "Matrix4x4") && size == 64)) {
        // Unity stores matrices column-major. Omit view matrices/head poses.
        float values[16]; memcpy(values, data, (size_t)size);
        emit("["); for (int i = 0; i < size / 4; i++) emit(i ? ",%.7g" : "%.7g", (double)values[i]); emit("]");
        if (size == 64) emit(" (column-major)");
        if (size == 64 && values[0] != 0) emit("; projectionScaleAspect=%.7g", (double)(values[5] / values[0]));
        if (size == 16 && values[3] != 0) emit("; viewportAspect=%.7g", (double)(values[2] / values[3]));
    } else emit("unavailable (unexpected value type)");
    emit("\n");
}

static void property(void *klass, void *object, const char *name) {
    bool ok; char getter[96]; snprintf(getter, sizeof(getter), "get_%s", name);
    void *value = call(klass, object, getter, 0, NULL, &ok);
    if (ok) show(name, value);
}

static void *nested(void *klass, const char *name) {
    void *iterator = NULL, *child;
    while (klass && (child = class_get_nested_types(klass, &iterator)))
        if (!strcmp(class_get_name(child), name)) return child;
    return NULL;
}

static void render_passes(void *display, void *camera) {
    void *klass = object_get_class(display);
    bool ok; int count = 0;
    void *boxed = call(klass, display, "GetRenderPassCount", 0, NULL, &ok);
    if (!ok || !number(boxed, &count)) return;
    emit("renderPassCount=%d\n", count);
    void *pass_type = nested(klass, "XRRenderPass"), *param_type = nested(klass, "XRRenderParameter");
    int pass_size = size_of(pass_type), param_size = size_of(param_type);
    if (pass_size <= 0 || pass_size > 1024 || param_size <= 0 || param_size > 1024) {
        emit("renderPassDetails=unavailable (value type metadata missing/oversized)\n"); return;
    }
    if (count > 8) emit("[render passes limited to 8]\n");
    for (int index = 0; index < count && index < 8; index++) {
        union { uint64_t align; uint8_t data[1024]; } pass = {0}, param = {0};
        void *args[] = {&index, pass.data};
        call(klass, display, "GetRenderPass", 2, args, &ok);
        if (!ok) continue;
        emit("pass=%d\n", index);
        void *pass_box = value_box(pass_type, pass.data);
        void *desc = field(pass_type, pass_box, "renderTargetDesc");
        if (desc) {
            const char *properties[] = {"width", "height", "volumeDepth", "dimension", "msaaSamples"};
            for (unsigned i = 0; i < sizeof(properties) / sizeof(properties[0]); i++) {
                char name[80]; snprintf(name, sizeof(name), "<%s>k__BackingField", properties[i]);
                show(properties[i], field(object_get_class(desc), desc, name));
            }
        } else emit("renderTargetDesc=unavailable\n");
        void *count_args[] = {pass.data}; int parameters = 0;
        boxed = call(pass_type, NULL, "GetRenderParameterCount_Injected", 1, count_args, &ok);
        if (!ok || !number(boxed, &parameters)) continue;
        emit("renderParameterCount=%d\n", parameters);
        if (!camera) { emit("renderParameters=unavailable (main camera missing)\n"); continue; }
        if (parameters > 4) emit("[render parameters limited to 4]\n");
        for (int eye = 0; eye < parameters && eye < 4; eye++) {
            memset(param.data, 0, sizeof(param.data));
            void *parameter_args[] = {pass.data, camera, &eye, param.data};
            call(pass_type, NULL, "GetRenderParameter_Injected", 4, parameter_args, &ok);
            if (!ok) continue;
            void *parameter = value_box(param_type, param.data);
            emit("parameter=%d\n", eye);
            show("viewport", field(param_type, parameter, "viewport"));
            show("textureArraySlice", field(param_type, parameter, "textureArraySlice"));
            show("projection", field(param_type, parameter, "projection"));
        }
    }
}

static const char *capture(void) {
    used = 0; output[0] = 0;
    emit("Renderer snapshot schema=1\n");
    if (!resolve()) { emit("Unity readback unavailable: IL2CPP not loaded, exports missing, or wrong thread\n"); return output; }
    void *glass = type("NREAL.Glass", "GlassConfig");
    show("GlassConfig.initialized", field(glass, NULL, "s_Inited"));
    show("GlassConfig.GlassMode", field(glass, NULL, "GlassMode"));
    show("GlassConfig.homeMode", field(glass, NULL, "m_HomeMode"));
    void *settings_type = type("Unity.XR.XREAL", "XREALSettings");
    void *settings = field(settings_type, NULL, "s_Settings");
    if (settings) {
        show("XREALSettings.StereoRendering", field(settings_type, settings, "StereoRendering"));
        show("XREALSettings.SupportMonoMode", field(settings_type, settings, "SupportMonoMode"));
    }
    void *xr = type("UnityEngine.XR", "XRSettings");
    const char *xr_properties[] = {"enabled", "isDeviceActive", "eyeTextureWidth", "eyeTextureHeight", "eyeTextureResolutionScale", "stereoRenderingMode"};
    for (unsigned i = 0; i < sizeof(xr_properties) / sizeof(xr_properties[0]); i++) property(xr, NULL, xr_properties[i]);
    void *camera_type = type("UnityEngine", "Camera"); bool ok;
    void *camera = call(camera_type, NULL, "get_main", 0, NULL, &ok);
    if (camera) {
        emit("Main camera\n");
        const char *camera_properties[] = {"stereoEnabled", "stereoTargetEye", "aspect", "fieldOfView", "pixelWidth", "pixelHeight", "pixelRect", "projectionMatrix"};
        for (unsigned i = 0; i < sizeof(camera_properties) / sizeof(camera_properties[0]); i++) property(camera_type, camera, camera_properties[i]);
    } else emit("Main camera unavailable\n");
    void *manager = type("UnityEngine", "SubsystemManager");
    void *list = field(manager, NULL, "s_IntegratedSubsystems");
    if (!list) { emit("XR display subsystem list unavailable\n"); return output; }
    void *list_type = object_get_class(list); int count = 0;
    void *boxed = call(list_type, list, "get_Count", 0, NULL, &ok);
    if (!ok || !number(boxed, &count)) return output;
    bool found = false;
    for (int index = 0; index < count && index < 32; index++) {
        void *args[] = {&index};
        void *subsystem = call(list_type, list, "get_Item", 1, args, &ok);
        if (!ok || !subsystem || strcmp(class_get_name(object_get_class(subsystem)), "XRDisplaySubsystem")) continue;
        found = true; emit("XRDisplaySubsystem\n");
        property(object_get_class(subsystem), subsystem, "running");
        render_passes(subsystem, camera);
    }
    if (!found) emit("XRDisplaySubsystem not present yet\n");
    return output;
}

#ifndef ANDROID_FIXTURE
JNIEXPORT jstring JNICALL Java_dev_beampro_settings_RendererDiagnostics_nativeCapture(JNIEnv *env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, capture());
}
#endif
