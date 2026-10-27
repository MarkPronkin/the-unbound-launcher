// Direct-OES windows, including video apps such as YouTube, sample with
// GLES/OesUI. Its gamma helper is present but the call is commented out.
// _DecodeGamma defaults to 0, so a GPU whose sRGB override works is unchanged.
// X20 is the app or phone mirror for the call entries. X19 is the app for the
// texture-type change, which is a tail call and must return through its epilogue.
// Space continues the unreachable body after the disabled analytics RET.
.text
.equ PORT_BASE, 0x12cb04c
.macro call target
    .word 0x94000000 | (((\target - (PORT_BASE + (. - oes_color_select))) >> 2) & 0x03ffffff)
.endm
.macro branch target
    .word 0x14000000 | (((\target - (PORT_BASE + (. - oes_color_select))) >> 2) & 0x03ffffff)
.endm
.macro page reg, target
    .word 0x90000000 | (((((\target >> 12) - ((PORT_BASE + (. - oes_color_select)) >> 12)) & 3)) << 29) | (((((\target >> 12) - ((PORT_BASE + (. - oes_color_select)) >> 12)) >> 2) & 0x7ffff) << 5) | \reg
.endm
oes_color_select:
app_oes_entry:
    ldr x4, [x20, #0x90]
    b save_and_run
mirror_oes_entry:
    ldr x4, [x20, #0x40]
    b save_and_run
change_oes_entry:
    ldr x4, [x19, #0x90]
    mov x21, x0
    bl set_content_and_gamma
    branch 0x34ddd6c
save_and_run:
    stp x21, x30, [sp, #-16]!
    mov x21, x0
    bl set_content_and_gamma
    ldp x21, x30, [sp], #16
    ret
set_content_and_gamma:
    stp x20, x19, [sp, #-32]!
    stp x21, x30, [sp, #16]
    mov x20, x4
    call 0x34dcdac
    mov x0, x21
    mov x1, x20
    bl apply_gamma
    ldp x21, x30, [sp, #16]
    ldp x20, x19, [sp], #32
    ret
apply_gamma:
    cbz x1, apply_done
    stp x20, x21, [sp, #-48]!
    stp x22, x23, [sp, #16]
    str x30, [sp, #32]
    mov x20, x0
    mov x21, x1
    page 0, 0x4808518
    add x0, x0, #0x518
    call 0xd601e0
    page 0, 0x48091c0
    add x0, x0, #0x1c0
    call 0xd601e0
    mov x0, x21
    mov x1, xzr
    call 0x1860fb8
    mov x21, x0
    cbz x21, apply_pop
    adr x0, method_name
    call 0xe04d30
    mov x22, x0
    page 0, 0x48091c0
    ldr x0, [x0, #0x1c0]
    call 0x29bee44
    mov x2, x0
    mov x0, x21
    mov x1, x22
    page 3, 0x4808518
    ldr x3, [x3, #0x518]
    call 0x2c10dd0
    cbz w0, apply_pop
    ldr x20, [x20, #0x178]
    cbz x20, apply_pop
    adr x0, prop_name
    call 0xe04d30
    mov x1, x0
    mov x0, x20
    fmov s0, #1.0
    mov x2, xzr
    call 0x347d844
apply_pop:
    ldr x30, [sp, #32]
    ldp x22, x23, [sp, #16]
    ldp x20, x21, [sp], #48
apply_done:
    ret
method_name:
    .asciz "unboundNeedsSoftwareDecode"
prop_name:
    .asciz "_DecodeGamma"
.balign 4
