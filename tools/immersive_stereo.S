// 0 = mono, 1/2 = half/full SBS, 3 = half top/bottom. Keep disabled entry RETs.
.text
.equ PORT_BASE, 0x12cfcfc
.macro jump target
    .word 0x14000000 | (((\target - (PORT_BASE + (. - immersive_stereo))) >> 2) & 0x03ffffff)
.endm
.macro call target
    .word 0x94000000 | (((\target - (PORT_BASE + (. - immersive_stereo))) >> 2) & 0x03ffffff)
.endm
.macro page reg, target
    .word 0x90000000 | (((((\target >> 12) - ((PORT_BASE + (. - immersive_stereo)) >> 12)) & 3)) << 29) | (((((\target >> 12) - ((PORT_BASE + (. - immersive_stereo)) >> 12)) >> 2) & 0x7ffff) << 5) | \reg
.endm
.global immersive_stereo, menu_start, menu_update, menu_hover, menu_out, menu_interactable
.global select_half, select_full, select_top_bottom, normalize_mode, overlay_refresh, overlay_apply
.global restore_input, reopen_app, reopen_intent
.macro wrapper name, original, after
\name:
    stp x19, x30, [sp, #-16]!
    mov x19, x0
    bl \original
    mov x0, x19
    bl \after
    ldp x19, x30, [sp], #16
    ret
.endm
immersive_stereo:
    wrapper menu_start, start_original, setup_option
start_original:
    stp x23, x22, [sp, #-0x30]!
    jump 0x33d7f08
    wrapper menu_hover, hover_original, refresh_picker
hover_original:
    str d8, [sp, #-0x30]!
    jump 0x33dd10c
    wrapper menu_out, out_original, refresh_picker
out_original:
    str d8, [sp, #-0x30]!
    jump 0x33dd4e4
    wrapper menu_interactable, interactable_original, refresh_picker
interactable_original:
    sub sp, sp, #0x60
    jump 0x33da6a8
menu_update:
    stp x19, x30, [sp, #-16]!
    mov x19, x0
    bl update_original
    mov x0, x19
    ldrb w8, [x19, #0x2ac]
    cbz w8, update_closed
    ldr x8, [x19, #0x2b0]
    cbz x8, update_closed
    ldr w8, [x8, #0x30]
    cbz w8, update_closed
    // Keep every option open across a mode change while the pointer is here.
    call 0x33dd108
    b update_done
update_closed:
    bl refresh_picker
update_done:
    ldp x19, x30, [sp], #16
    ret
update_original:
    sub sp, sp, #0x60
    jump 0x33dbb94
refresh_picker:
    stp x19, x30, [sp, #-16]!
    mov x19, x0
    bl labels
    mov x0, x19
    bl sync_option
    ldp x19, x30, [sp], #16
    ret
labels:
    ldr x8, [x0, #0x2b0]
    cbz x8, labels_done
    stp x19, x20, [sp, #-32]!
    stp x21, x30, [sp, #16]
    mov x19, x0
    mov w20, #1
    ldrb w9, [x19, #0x2ac]
    cbnz w9, fixed_labels
    ldr w20, [x8, #0x30]
    cbnz w20, selected_label
    ldr w20, [x8, #0x34]
selected_label:
    cmp w20, #4
    mov w9, #3
    csel w20, w9, w20, eq
    sub w9, w20, #1
    cmp w9, #2
    mov w9, #1
    csel w20, w20, w9, ls
fixed_labels:
    ldr x21, [x19, #0x110]
    cbz x21, labels_restore
    mov w0, w20
    bl mode_label
    mov x1, x0
    mov x0, x21
    bl set_text
    ldr x21, [x19, #0x108]
    cbz x21, labels_restore
    mov w0, #2
    bl mode_label
    mov x1, x0
    mov x0, x21
    bl set_text
labels_restore:
    ldp x21, x30, [sp, #16]
    ldp x19, x20, [sp], #32
labels_done:
    ret
set_text:
    ldr x8, [x0]
    ldr x9, [x8, #0x558]
    ldr x2, [x8, #0x560]
    br x9
mode_label:
    cmp w0, #3
    b.eq tb_label
    stp x19, x30, [sp, #-16]!
    cmp w0, #2
    b.eq full_label
    page 0, 0x45e4000
    ldr x19, [x0, #0xa68]
    b localize
full_label:
    page 0, 0x4587000
    ldr x19, [x0, #0x5c8]
localize:
    // Stock hover initializes these literals, but app-window creation can
    // refresh labels first. Resolve the metadata token before localization.
    mov x0, x19
    call 0xd601e0
    ldr x0, [x19]
    mov x1, xzr
    call 0x15bbc64
    ldp x19, x30, [sp], #16
    ret
tb_label:
    adr x0, tb_text
    jump 0xe04d30

.org 0x330
    ret
select_half:
    mov w1, #1
    b select_mode
select_full:
    mov w1, #2
    b select_mode
select_top_bottom:
top_bottom_handler:
    mov w1, #3
select_mode:
    stp x19, x20, [sp, #-64]!
    stp x21, x30, [sp, #16]
    mov x19, x0
    mov w20, w1
    ldr x8, [x19, #0x2b0]
    cbz x8, selection_done
    ldr w8, [x8, #0x30]
    cmp w8, w20
    b.eq selection_done
    ldr x8, [x19, #0x298]
    cbz x8, selection_done
    // The existing FullSBSMode Action supplies this window's ApplicationView.
    page 0, 0x457f000
    ldr x0, [x0, #0x918]
    call 0xd601e0
    page 0, 0x454d000
    ldr x0, [x0, #0xca0]
    call 0xd601e0
    page 1, 0x457f000
    ldr x1, [x1, #0x918]
    ldr x1, [x1]
    page 3, 0x454d000
    ldr x3, [x3, #0xca0]
    ldr x3, [x3]
    ldr x0, [x19, #0x298]
    add x2, sp, #32
    stp xzr, xzr, [sp, #32]
    str xzr, [sp, #48]
    call 0x336b54c
    tbz w0, #0, selection_done
    ldr x8, [sp, #40]
    cbz x8, selection_done
    ldr x0, [x8, #0x40]
    cbz x0, selection_done
    mov w1, w20
    mov w2, #1
    mov w3, wzr
    mov w4, #1
    mov x5, xzr
    call 0x34e0ed4
selection_done:
    ldp x21, x30, [sp, #16]
    ldp x19, x20, [sp], #64
    ret
normalize_mode:
    // A saved build-2970 Full T/B selection now enters working Half T/B.
    cmp w1, #4
    mov w8, #3
    csel w1, w8, w1, eq
    sub sp, sp, #0x70
    jump 0x34e0ed8

.org 0x57c
    ret
setup_option:
    stp x19, x20, [sp, #-48]!
    stp x21, x22, [sp, #16]
    str x30, [sp, #32]
    mov x19, x0
    bl find_option
    cbnz x0, setup_done
    ldr x0, [x19, #0xc0]
    cbz x0, setup_done
    mov x1, xzr
    call 0x324a7ac
    mov x20, x0
    mov x0, x19
    bl option_parent
    cbz x0, setup_done
    mov x1, x0
    mov x0, x20
    mov w2, wzr
    mov x3, xzr
    call 0x3485748
    mov x21, x0
    cbz x21, setup_done
    mov w1, wzr
    mov x2, xzr
    call 0x3486d28
    adr x0, option_name
    call 0xe04d30
    mov x1, x0
    mov x0, x21
    mov x2, xzr
    call 0x3484ff4
    mov x0, x21
    mov x1, xzr
    call 0x3486c6c
    mov x20, x0
    mov w1, #3
    mov x2, xzr
    call 0x324fa74
    mov x0, x20
    mov w1, #1
    bl component
    cbz x0, setup_done
    mov w8, #0x42900000
    fmov s0, w8
    mov x1, xzr
    call 0x1dc2b04
    mov x0, x20
    adr x1, text_path
    bl find_child
    cbz x0, setup_done
    mov w1, #2
    bl component
    cbz x0, setup_done
    mov x22, x0
    mov w1, #1
    mov x2, xzr
    call 0x32b7da8
    mov x0, x22
    fmov s0, #18.0
    mov x1, xzr
    call 0x32b7dfc
    mov w0, #3
    bl mode_label
    mov x1, x0
    mov x0, x22
    bl set_text
    mov x0, x20
    mov w1, wzr
    bl component
    mov x20, x0
    cbz x20, setup_done
    page 0, 0x4570000
    ldr x0, [x0, #0x740]
    ldr x0, [x0]
    call 0xd602d4
    mov x21, x0
    cbz x21, setup_done
    page 2, 0x45cb000
    ldr x2, [x2, #0x4a8]
    ldr x2, [x2]
    mov x1, x19
    mov x3, xzr
    call 0x1c4d4dc
    // Preserve the original void(instance) MethodInfo and managed target.
    // Only the native invocation addresses point to the added T/B handler.
    adr x8, top_bottom_handler
    str x8, [x21, #0x10]
    str x8, [x21, #0x18]
    mov x0, x20
    mov x1, x21
    mov x2, xzr
    call 0x1279908
setup_done:
    ldr x30, [sp, #32]
    ldp x21, x22, [sp, #16]
    ldp x19, x20, [sp], #48
    ret
option_parent:
    ldr x0, [x0, #0xc0]
    cbz x0, parent_done
    stp x19, x30, [sp, #-16]!
    mov x1, xzr
    call 0x324a770
    mov x1, xzr
    call 0x324e6b8
    ldp x19, x30, [sp], #16
parent_done:
    ret

.org 0x8a0
    ret
find_option:
    stp x19, x30, [sp, #-16]!
    bl option_parent
    cbz x0, find_done
    adr x1, option_name
    bl find_child
find_done:
    ldp x19, x30, [sp], #16
    ret
find_child:
    stp x19, x30, [sp, #-16]!
    mov x19, x0
    mov x0, x1
    call 0xe04d30
    mov x1, x0
    mov x0, x19
    mov x2, xzr
    call 0x324fb48
    ldp x19, x30, [sp], #16
    ret
component:
    stp x19, x20, [sp, #-32]!
    str x30, [sp, #16]
    mov x19, x0
    // Use already-shipped typed generic metadata, independent of type names
    // or Unity's legacy string lookup across managed assemblies.
    page 20, 0x4818000
    cmp w1, #1
    b.eq layout_component
    cmp w1, #2
    b.eq text_component
    add x20, x20, #0x640
    b component_ready
layout_component:
    add x20, x20, #0x900
    b component_ready
text_component:
    add x20, x20, #0xb48
component_ready:
    mov x0, x20
    call 0xd601e0
    ldr x1, [x20]
    mov x0, x19
    call 0x256e4f0
    ldr x30, [sp, #16]
    ldp x19, x20, [sp], #32
    ret
sync_option:
    stp x19, x20, [sp, #-32]!
    stp x21, x30, [sp, #16]
    mov x19, x0
    bl find_option
    cbz x0, sync_done
    mov x20, x0
    ldrb w21, [x19, #0x2ac]
    ldr x8, [x19, #0x2b0]
    cbz x8, option_closed
    ldr w8, [x8, #0x30]
    cmp w8, #0
    cset w8, gt
    and w21, w21, w8
    b option_visibility
option_closed:
    mov w21, wzr
option_visibility:
    mov x1, xzr
    call 0x324a7ac
    mov w1, w21
    mov x2, xzr
    call 0x3486d28
    mov x0, x20
    mov w1, wzr
    bl component
    cbz x0, sync_done
    mov x1, x0
    mov x0, x19
    ldrb w8, [x19, #0x288]
    and w2, w8, w21
    call 0x33dcfa8
sync_done:
    ldp x21, x30, [sp, #16]
    ldp x19, x20, [sp], #32
    ret
overlay_refresh:
    cmp w1, #3
    b.eq top_bottom_rects
    sub sp, sp, #0x60
    jump 0x34e1688
overlay_apply:
    ldr w8, [x0, #0x1dc]
    cmp w8, #3
    b.eq top_bottom_rects
    sub sp, sp, #0x50
    jump 0x34e3398
top_bottom_rects:
    ldr x0, [x0, #0x190]
    cbz x0, rects_done
    mov w8, #1
    strb w8, [x0, #0x5c]
    fmov s0, wzr
    fmov s1, #0.5
    fmov s2, #1.0
    stp s0, s1, [x0, #0x60]
    stp s2, s1, [x0, #0x68]
    stp s0, s0, [x0, #0x70]
    stp s2, s1, [x0, #0x78]
    mov x1, xzr
    jump 0x269ba94
rects_done:
    ret

.org 0xbc8
    ret
// Undo old Full T/B dimensions when reopening a saved build-2970 window.
reopen_app:
    cmp w9, #4
    b.ne reopen_app_original
    ldr w11, [x22, #0x14]
    asr w11, w11, #1
    str w11, [x22, #0x14]
reopen_app_original:
    cmp w9, #2
    jump 0x34ee340
reopen_intent:
    cmp w9, #4
    b.ne reopen_intent_original
    ldr w11, [x23, #0x14]
    asr w11, w11, #1
    str w11, [x23, #0x14]
reopen_intent_original:
    cmp w9, #2
    jump 0x34ee61c
restore_input:
    mov w1, w8
    cbnz w1, input_ready
    mov w1, #2
    ldr x9, [x19, #0x200]
    cbz x9, input_ready
    ldr x9, [x9, #0x2b0]
    cbz x9, input_ready
    ldr w10, [x9, #0x34]
    cmp w10, #4
    mov w9, #3
    csel w10, w9, w10, eq
    sub w9, w10, #1
    cmp w9, #2
    csel w1, w10, w1, ls
input_ready:
    jump 0x34e6f38
option_name: .asciz "UnboundHalfTopBottom"
text_path: .asciz "stateTxt"
tb_text: .asciz "Half Top/Bottom"
.balign 4
