// Initialize the floating icon created by EPHViewManager.PickUpApp.
// This wrapper replaces only its Instantiate call, then invokes the clone's
// existing virtual Initialized(original.m_EPHAppInfo) method. The disabled
// SetUserProperty collector retains its RET immediately before this code.
.text
.equ PORT_BASE, 0x12ca1e8
.macro call target
    .word 0x94000000 | (((\target - (PORT_BASE + (. - port_icon_clone))) >> 2) & 0x03ffffff)
.endm
port_icon_clone:
    stp x20, x19, [sp, #-32]!
    stp x29, x30, [sp, #16]
    mov x19, x0                 // Original icon; preserve Instantiate arguments.
    call 0x199abf8              // Object.Instantiate<EPHAppIconView>(icon, parent)
    mov x20, x0
    cbz x0, done                // Preserve original null handling in the caller.
    ldr x1, [x19, #0x88]        // Original EPHAppInfo.
    ldr x8, [x0]                // Clone's virtual Initialized(object) slot 4.
    ldr x9, [x8, #0x178]
    ldr x2, [x8, #0x180]        // Matching MethodInfo.
    blr x9
 done:
    mov x0, x20                 // Return the clone to the original pickup path.
    ldp x29, x30, [sp, #16]
    ldp x20, x19, [sp], #32
    ret
