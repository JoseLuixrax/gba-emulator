@ ARM7 side: takes the VBlank interrupt, which is where Action Replay
@ cheats run (melonDS hooks the ARM7 VBlank IRQ like the real device), and
@ keeps reading the microphone through the touchscreen controller like a
@ game would, leaving the latest reading (0-127, 64 = silence) at 0x02100004.
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
    ldr r4, =0x040001C0     @ SPICNT / SPIDATA
    ldr r5, =0x02100004
mic:
    ldr r1, =0x8A01         @ SPI on, touchscreen controller, keep chip select
    strh r1, [r4]
    mov r1, #0xEC           @ start, channel 6 (AUX = microphone), 8-bit
    strb r1, [r4, #2]
1:  ldrh r2, [r4]
    tst r2, #0x80           @ busy
    bne 1b
    ldr r1, =0x8201         @ last byte: release chip select
    strh r1, [r4]
    mov r1, #0
    strb r1, [r4, #2]
2:  ldrh r2, [r4]
    tst r2, #0x80
    bne 2b
    ldrb r3, [r4, #2]       @ top bits of the 12-bit reading
    str r3, [r5]
    b mic
irq:
    ldr r0, =0x04000214     @ IF: acknowledge what fired
    ldr r1, [r0]
    str r1, [r0]
    bx lr
.ltorg
