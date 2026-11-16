@ ARM7 side: only takes the VBlank interrupt, which is where Action Replay
@ cheats run (melonDS hooks the ARM7 VBlank IRQ like the real device).
.syntax unified
.arm
.global _start
_start:
    ldr r0, =0x0380FFFC     @ IRQ handler address the BIOS jumps to
    adr r1, irq
    str r1, [r0]
    ldr r0, =0x04000004     @ DISPSTAT: VBlank IRQ enable
    mov r1, #0x8
    strh r1, [r0]
    ldr r0, =0x04000210     @ IE: VBlank
    mov r1, #1
    str r1, [r0]
    ldr r0, =0x04000208     @ IME
    str r1, [r0]
    mrs r0, cpsr
    bic r0, r0, #0x80       @ IRQs on
    msr cpsr_c, r0
idle:
    b idle
irq:
    ldr r0, =0x04000214     @ IF: acknowledge what fired
    ldr r1, [r0]
    str r1, [r0]
    bx lr
.ltorg
