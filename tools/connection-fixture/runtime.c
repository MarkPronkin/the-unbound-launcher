#include <jni.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdatomic.h>
#include <string.h>

// Test-only IL2CPP API used to reproduce 2930's native readiness false negative.
// The corrected handoff must complete without consulting these optional states.
static atomic_int flags, anchor_active, advancing;
static bool values[5], active;
static int hand, frame = 100;
static const char *properties[] = {"RaycastersActive", "RaycastersUpdatePose", "ControllerVisualActive", "LaserVisualActive", "ReticleVisualActive"};
JNIEXPORT void JNICALL Java_dev_beampro_port_FixturePointer_configure(JNIEnv *env, jclass cls, jint mask, jboolean anchor, jboolean frames) {
    (void)env; (void)cls;
    atomic_store(&flags, mask); atomic_store(&anchor_active, anchor); atomic_store(&advancing, frames);
}
void *il2cpp_domain_get(void) { return (void *)1; }
const void **il2cpp_domain_get_assemblies(void *domain, size_t *count) {
    (void)domain; static const void *assemblies[] = {(void *)1}; *count = 1; return assemblies;
}
const void *il2cpp_assembly_get_image(const void *assembly) { return assembly; }
void *il2cpp_class_from_name(const void *image, const char *space, const char *name) {
    (void)image; (void)space;
    if (!strcmp(name, "XRInput")) return (void *)1;
    if (!strcmp(name, "ControllerAnchorsHelper")) return (void *)2;
    if (!strcmp(name, "Component")) return (void *)3;
    if (!strcmp(name, "GameObject")) return (void *)4;
    if (!strcmp(name, "Time")) return (void *)5;
    return NULL;
}
const void *il2cpp_class_get_method_from_name(void *type, const char *name, int args) {
    (void)type; (void)args;
    for (int i = 0; i < 5; i++) {
        if (!strncmp(name, "get_", 4) && !strcmp(name + 4, properties[i])) return (void *)(intptr_t)(1 + i);
        if (!strncmp(name, "set_", 4) && !strcmp(name + 4, properties[i])) return (void *)(intptr_t)(6 + i);
    }
    const char *methods[] = {"get_AnchorsHelper", "get_MainHand", "GetAnchor", "get_gameObject", "get_activeInHierarchy", "get_frameCount"};
    for (int i = 0; i < 6; i++) if (!strcmp(name, methods[i])) return (void *)(intptr_t)(20 + i);
    return NULL;
}
void *il2cpp_runtime_invoke(const void *method, void *object, void **args, void **exception) {
    (void)object; *exception = NULL;
    intptr_t id = (intptr_t)method;
    if (id >= 1 && id <= 5) { values[id - 1] = (atomic_load(&flags) & (1 << (id - 1))) != 0; return &values[id - 1]; }
    if (id >= 6 && id <= 10) {
        int bit = 1 << (id - 6);
        if (*(bool *)args[0]) atomic_fetch_or(&flags, bit); else atomic_fetch_and(&flags, ~bit);
        return NULL;
    }
    switch (id) {
        case 20: return (void *)1;
        case 21: return &hand;
        case 22: return (void *)2;
        case 23: return (void *)3;
        case 24: active = atomic_load(&anchor_active) != 0; return &active;
        case 25: if (atomic_load(&advancing)) frame++; return &frame;
        default: return NULL;
    }
}
void *il2cpp_object_unbox(void *object) { return object; }
void *il2cpp_thread_current(void) { return (void *)1; }
