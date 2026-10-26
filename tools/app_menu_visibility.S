// LayoutConfig.json positions are stable ranks. INT_MAX means user-hidden.
// Runs after the original ReorderListByPositions metadata initialization.
// No array allocations or out-of-range indexing for removed apps/stale positions.
.text
.equ PORT_BASE, 0x12ca708
.macro call target
    .word 0x94000000 | (((\target - (PORT_BASE + (. - port_menu_order))) >> 2) & 0x03ffffff)
.endm
.macro jump target
    .word 0x14000000 | (((\target - (PORT_BASE + (. - port_menu_order))) >> 2) & 0x03ffffff)
.endm
.macro page reg, target
    .word 0x90000000 | (((((\target >> 12) - ((PORT_BASE + (. - port_menu_order)) >> 12)) & 3)) << 29) | (((((\target >> 12) - ((PORT_BASE + (. - port_menu_order)) >> 12)) >> 2) & 0x7ffff) << 5) | \reg
.endm
port_menu_order:
    cbz x19, done
    cbz x21, done
    ldr w20, [x19, #0x18]
    cbz w20, done
    cmp w20, #0x1000
    b.hi done
    ldr x22, [x19, #0x10]
    cbz x22, done
    ldr w8, [x22, #0x18]
    cmp w20, w8
    b.hi done
    add x22, x22, #0x20
    lsl x28, x20, #2
    add x28, x28, #15
    and x28, x28, #-16
    sub sp, sp, x28
    mov x26, sp
    mov w23, wzr
rank_loop:
    ldr x24, [x22, x23, lsl #3]
    mov w25, #0x7ffffffe
    cbz x24, store_rank
    ldr x1, [x24, #0x20]
    cbz x1, existing_hidden
    page 8, 0x45e1000
    ldr x8, [x8, #0x330]
    ldr x2, [x8]
    mov x0, x21
    call 0x2cb7430              // Dictionary<string,int>.ContainsKey.
    tbz w0, #0, existing_hidden
    page 8, 0x45d1000
    ldr x8, [x8, #0xb70]
    ldr x2, [x8]
    mov x0, x21
    ldr x1, [x24, #0x20]
    call 0x2cb71a8              // Dictionary<string,int>.get_Item.
    tbnz w0, #31, existing_hidden // Legacy -1 entries are unspecified, not hidden.
    mov w25, w0
    mov w8, #2
    str w8, [x24, #0x78]       // Preserve all explicit orders in subsequent AR saves.
    mov w9, #0x7fffffff
    cmp w25, w9
    b.ne existing_hidden
    mov w8, #1
    strb w8, [x24, #0x53]      // Retain metadata and use native hideInHome filtering.
existing_hidden:
    ldrb w8, [x24, #0x53]
    cbz w8, store_rank
    mov w25, #0x7fffffff
store_rank:
    str w25, [x26, x23, lsl #2]
    add w23, w23, #1
    cmp w23, w20
    b.lo rank_loop
    mov w23, #1
sort_loop:
    cmp w23, w20
    b.hs complete
    ldr x24, [x22, x23, lsl #3]
    ldr w25, [x26, x23, lsl #2]
    mov w27, w23
shift_loop:
    cbz w27, insert
    sub w8, w27, #1
    ldr w9, [x26, x8, lsl #2]
    cmp w9, w25
    b.ls insert
    ldr x10, [x22, x8, lsl #3]
    str x10, [x22, x27, lsl #3]
    str w9, [x26, x27, lsl #2]
    mov w27, w8
    b shift_loop
insert:
    str x24, [x22, x27, lsl #3]
    str w25, [x26, x27, lsl #2]
    add w23, w23, #1
    b sort_loop
complete:
    add sp, sp, x28
    ldr w8, [x19, #0x1c]
    add w8, w8, #1
    str w8, [x19, #0x1c]
done:
    jump 0x350c7e0              // Original epilogue restores all saved registers.

// The stock iterator creates pages only when it encounters a visible app.
// Keep one empty page when every app is hidden so focus and page markers never
// index an empty list. This uses the same CreatePage path as a normal first page.
port_menu_empty_page:
    cbz x20, empty_page_done
    ldr x8, [x20, #0x60]
    cbz x8, empty_page_done
    ldr w9, [x8, #0x18]
    cbnz w9, empty_page_done
    str wzr, [x20, #0x6c]       // An old page selection cannot survive an empty layout.
    mov x0, x20
    add x1, x19, #0x3c
    call 0x32f035c
    str x0, [x19, #0x30]
empty_page_done:
    ldr x0, [x19, #0x30]
    jump 0x32f5d84

port_menu_saved_position:
    ldrb w9, [x8, #0x53]
    cbz w9, original_index
    mov w0, #0x7fffffff
    ret
original_index:
    jump 0x1b65ec4              // Original List<string>.IndexOf.
