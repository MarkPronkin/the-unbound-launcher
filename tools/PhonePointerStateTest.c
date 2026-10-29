#include <assert.h>
#include <stdio.h>
#include "../native/phone-pointer/pointer_state.h"

typedef struct { bool values[POINTER_PROPERTIES]; int fail_read, fail_write; } Fixture;
static bool read_value(void *context, int index, bool *value) {
    Fixture *fixture = context;
    if (fixture->fail_read == index) { fixture->fail_read = -1; return false; }
    *value = fixture->values[index]; return true;
}
static bool write_value(void *context, int index, bool value) {
    Fixture *fixture = context;
    if (fixture->fail_write == index) { fixture->fail_write = -1; return false; }
    fixture->values[index] = value; return true;
}
static void original(Fixture *fixture) {
    fixture->fail_read = fixture->fail_write = -1;
    for (int i = 0; i < POINTER_PROPERTIES; i++) fixture->values[i] = i != 3;
}
static void check_original(Fixture fixture) {
    for (int i = 0; i < POINTER_PROPERTIES; i++) assert(fixture.values[i] == (i != 3));
}
int main(void) {
    Fixture fixture; original(&fixture);
    PointerState state = {0}; PointerAccess access = {&fixture, read_value, write_value};
    assert(pointer_suspend(&state, access));
    for (int i = 0; i < POINTER_PROPERTIES; i++) assert(!fixture.values[i]);
    fixture.values[1] = true;
    assert(pointer_suspend(&state, access)); // Renew without replacing the snapshot.
    assert(!fixture.values[1]);
    assert(pointer_restore(&state, access)); check_original(fixture);
    fixture.values[3] = true;
    assert(pointer_restore(&state, access)); assert(fixture.values[3]); // No stale restore.
    original(&fixture); fixture.fail_read = 2;
    assert(!pointer_suspend(&state, access)); assert(!state.held); check_original(fixture);
    fixture.fail_write = 2;
    assert(!pointer_suspend(&state, access)); assert(!state.held); check_original(fixture);
    assert(pointer_suspend(&state, access)); fixture.fail_write = 1;
    assert(!pointer_restore(&state, access)); assert(state.held);
    assert(pointer_restore(&state, access)); assert(!state.held); check_original(fixture);
    puts("PASS pointer snapshot, pause, renewal, rollback, restoration and retry");
}
