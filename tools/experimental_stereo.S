// Tail filter for GlassUtil.GetHomeGlassModeFromSettings. The original model
// selector runs first. Only mono EGlassMode values 1, 5, 10 and 11 can become 3.
.text
experimental_stereo:
    cmp w0, #1
    b.eq read_flag
    cmp w0, #5
    b.eq read_flag
    cmp w0, #10
    b.eq read_flag
    cmp w0, #11
    b.eq read_flag
    ret
read_flag:
    sub sp, sp, #64
    stp x0, x1, [sp]
    stp x2, x8, [sp, #16]
    stp x9, x10, [sp, #32]
    mov x0, #-100
    adr x1, preference_path
    mov x2, #0
    mov x8, #56                 // openat
    svc #0
    tbnz x0, #63, done
    mov x9, x0
    add x1, sp, #48
    mov x2, #2                 // Reject anything except exactly one byte.
    mov x8, #63                 // read
    svc #0
    mov x10, x0
    mov x0, x9
    mov x8, #57                 // close, even after a failed read
    svc #0
    cmp x10, #1
    b.ne done
    ldrb w10, [sp, #48]
    cmp w10, #1
    b.ne done
    mov x10, #3                 // Legacy stereo SBS 60 Hz.
    str x10, [sp]
done:
    ldp x0, x1, [sp]
    ldp x2, x8, [sp, #16]
    ldp x9, x10, [sp, #32]
    add sp, sp, #64
    ret
preference_path:
    // Pinned original literal; package_identity.py relocates it after native patches.
    .asciz "/data/user/0/com.xreal.evapro.nebula/files/unbound-experimental-stereo"
    .balign 4
