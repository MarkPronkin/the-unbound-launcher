#ifndef UNBOUND_POINTER_STATE_H
#define UNBOUND_POINTER_STATE_H
#include <stdbool.h>

enum { POINTER_PROPERTIES = 5 };
typedef struct { bool saved[POINTER_PROPERTIES]; bool held; } PointerState;
typedef struct {
    void *context;
    bool (*read)(void *, int, bool *);
    bool (*write)(void *, int, bool);
} PointerAccess;

static bool pointer_restore(PointerState *state, PointerAccess access) {
    if (!state->held) return true;
    bool ok = true;
    // Reactivate interaction last, after restoring the original pose and visuals.
    for (int i = POINTER_PROPERTIES - 1; i >= 0; i--) {
        bool value;
        if (!access.write(access.context, i, state->saved[i])
                || !access.read(access.context, i, &value) || value != state->saved[i]) ok = false;
    }
    if (ok) state->held = false;
    return ok;
}

static bool pointer_suspend(PointerState *state, PointerAccess access) {
    if (!state->held) {
        bool saved[POINTER_PROPERTIES];
        for (int i = 0; i < POINTER_PROPERTIES; i++)
            if (!access.read(access.context, i, &saved[i])) return false;
        for (int i = 0; i < POINTER_PROPERTIES; i++) state->saved[i] = saved[i];
        state->held = true;
    }
    for (int i = 0; i < POINTER_PROPERTIES; i++) {
        bool value;
        if (!access.write(access.context, i, false)
                || !access.read(access.context, i, &value) || value) {
            pointer_restore(state, access);
            return false;
        }
    }
    return true;
}
#endif
