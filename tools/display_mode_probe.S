// Test-only replacement of an existing exported JNI entry. It calls the real
// libnr_api Display.Mode constructor at its unchanged address. No probe bytes
// are packaged into the launcher.
.text
.global display_mode_probe
display_mode_probe:
    stp x29, x30, [sp, #-0x80]!
    mov x29, sp
    add x0, sp, #0x10
    // BL 0x1e8f2bc from the existing JNI entry's PC 0x181fe68.
    .inst 0x9419bd15
    mov w0, #0
    ldr x8, [sp, #0x38]  // mode ID
    cbz x8, done
    ldr x8, [sp, #0x40]  // physical width
    cbz x8, done
    ldr x8, [sp, #0x48]  // physical height
    cbz x8, done
    ldr x8, [sp, #0x50]  // refresh rate
    cbz x8, done
    ldr x8, [sp, #0x58]  // unused HDR method ID
    cbnz x8, done
    mov w0, #1
done:
    ldp x29, x30, [sp], #0x80
    ret
