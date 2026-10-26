// Fallback for an exported Intent handler absent from the launcher's app catalog.
// Lives after the retained RET in the disabled SetUserId analytics method.
// Existing catalog entries, Intent resolution and launch/input paths are preserved.
.text
.equ PORT_BASE, 0x12ca054
.macro call target
    .word 0x94000000 | (((\target - (PORT_BASE + (. - port_intent_appinfo))) >> 2) & 0x03ffffff)
.endm
port_intent_appinfo:
    stp x21, x20, [sp, #-32]!
    stp x19, x30, [sp, #16]
    mov x19, x1
    mov x20, x0               // Keep the app catalog for later window title lookups.
    call 0x3505840             // EPHAppInfoProxy.RetrieveAppInfo(packageName)
    cbnz x0, done
    mov x0, x19
    mov x1, xzr
    call 0x34b99a4             // AndroidNativeInterface.GetAppInfo(packageName)
    cbz x0, done
    mov x1, x0
    mov x0, xzr               // GenerateTempAppInfo does not use its this argument.
    mov x2, xzr
    call 0x350d360             // NativeTask.GenerateTempAppInfo(PackageInfo)
    cbz x0, done
    mov w8, #1                // APPType.Native
    str w8, [x0, #0x10]
    str wzr, [x0, #0x78]       // Same priority as a normal native catalog entry.
    mov x19, x0
    mov x1, xzr
    call 0x3506984             // EPHAppInfo.CompleteAppSizeInfo()
    mov w8, #1
    strb w8, [x19, #0x53]      // Metadata-only catalog entry: hideInHome = true.
    .word 0xb00198e0           // ADRP x0, 0x45e7000 at the final cave address.
    ldr x0, [x0, #0x5d8]
    call 0xd601e0              // Initialize Method$List<EPHAppInfo>.Add.
    .word 0xb00198e8           // ADRP x8, 0x45e7000 at the final cave address.
    ldr x8, [x8, #0x5d8]
    ldr x2, [x8]
    ldr x0, [x20, #0x30]       // EPHAppInfoProxy.m_AppInfos
    mov x1, x19
    call 0x1b65244             // List<EPHAppInfo>.Add(info)
    mov x0, x19
done:
    ldp x19, x30, [sp, #16]
    ldp x21, x20, [sp], #32
    ret
