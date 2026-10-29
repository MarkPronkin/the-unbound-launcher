// AppInfo.isProtected is an SDK routing hint. On GPUs without the required
// sRGB override, use the copy surface for the port's non-secure virtual display.
// Android FLAG_SECURE and virtual-display flags are untouched.
.text
.equ PORT_BASE, 0x12ca1e8
.macro jump target
    .word 0x14000000 | (((\target - (PORT_BASE + (. - app_surface_routing))) >> 2) & 0x03ffffff)
.endm
.macro entry label, owner, resume
\label:
    strb w8, [\owner, #0x72]
    stp x0, x30, [sp, #-16]!
    mov x0, \owner
    bl filter
    ldp x0, x30, [sp], #16
    jump \resume
.endm
app_surface_routing:
    entry app_open, x19, 0x32ff37c
    entry app_intent, x20, 0x32ffc80
    entry app_flow, x20, 0x33000ac
    entry app_change, x21, 0x3309410
    entry separate_open, x21, 0x34ee2c0
    entry separate_intent, x22, 0x34ee5a4
filter:
    cbz w8, done
    sub sp, sp, #96
    stp x0, x1, [sp]
    stp x2, x8, [sp, #16]
    stp x9, x10, [sp, #32]
    stp x11, x12, [sp, #48]
    mrs x12, nzcv
    str x12, [sp, #64]
    mov x11, x0
    mov x0, #-100
    adr x1, flag_path
    mov x2, #0
    mov x8, #56
    svc #0
    tbnz x0, #63, restore
    mov x9, x0
    add x1, sp, #80
    mov x2, #2
    mov x8, #63
    svc #0
    mov x10, x0
    mov x0, x9
    mov x8, #57
    svc #0
    cmp x10, #1
    b.ne restore
    ldrb w10, [sp, #80]
    cmp w10, #1
    b.ne restore
    strb wzr, [x11, #0x72]
restore:
    ldr x12, [sp, #64]
    msr nzcv, x12
    ldp x11, x12, [sp, #48]
    ldp x9, x10, [sp, #32]
    ldp x2, x8, [sp, #16]
    ldp x0, x1, [sp]
    add sp, sp, #96
done:
    ret
flag_path:
    .asciz "/data/user/0/com.xreal.evapro.nebula/files/unbound-app-surface-fallback"
    .balign 4
