// Two material-creation call sites. The original FLIP_Y call is retained.
// X19 is the owning app/mirror; each contains the existing AndroidSurfaceTexture.
// Space is the unreachable body after a disabled analytics entry-point RET.
.text
.equ PORT_BASE, 0x12caf6c
.macro call target
    .word 0x94000000 | (((\target - (PORT_BASE + (. - app_color_select))) >> 2) & 0x03ffffff)
.endm
.macro page reg, target
    .word 0x90000000 | (((((\target >> 12) - ((PORT_BASE + (. - app_color_select)) >> 12)) & 3)) << 29) | (((((\target >> 12) - ((PORT_BASE + (. - app_color_select)) >> 12)) >> 2) & 0x7ffff) << 5) | \reg
.endm
app_color_select:
    ldr x4, [x19, #0x90]
    b common
mirror_color_select:
    ldr x4, [x19, #0x40]
    b common
common:
    stp x20, x21, [sp, #-48]!
    stp x22, x23, [sp, #16]
    str x30, [sp, #32]
    mov x20, x0
    mov x21, x4
    call 0x347ec8c                 // Material.EnableKeyword(original FLIP_Y)
    cbz x21, done
    page 0, 0x4808518
    add x0, x0, #0x518
    call 0xd601e0                  // Initialize Call<bool> MethodInfo slot.
    page 0, 0x48091c0
    add x0, x0, #0x1c0
    call 0xd601e0                  // Initialize Array.Empty<object> MethodInfo.
    mov x0, x21
    mov x1, xzr
    call 0x1860fb8                 // AndroidSurfaceTexture.get_NativeObject
    mov x21, x0
    cbz x21, done
    adr x0, method_name
    call 0xe04d30                  // il2cpp_string_new
    mov x22, x0
    page 0, 0x48091c0
    ldr x0, [x0, #0x1c0]
    call 0x29bee44                 // Array.Empty<object>
    mov x2, x0
    mov x0, x21
    mov x1, x22
    page 3, 0x4808518
    ldr x3, [x3, #0x518]
    call 0x2c10dd0                 // AndroidJavaObject.Call<bool>
    cbz w0, done                   // Hardware decoded: preserve stock shader.
    adr x0, keyword_name
    call 0xe04d30
    mov x1, x0
    mov x0, x20
    mov x2, xzr
    call 0x347ec8c                 // Enable the existing software-decode variant.
done:
    ldr x30, [sp, #32]
    ldp x22, x23, [sp, #16]
    ldp x20, x21, [sp], #48
    ret
method_name:
    .asciz "unboundNeedsSoftwareDecode"
keyword_name:
    .asciz "GAMMA_TO_LINEAR"
.balign 4
