@ ARM9 side of the test ROM: shows a frame on the top screen using VRAM
@ display mode. Upper half blue (red while A is held), lower half green.
.syntax unified
.arm
.global _start
_start:
    ldr r0, =0x04000304     @ POWCNT1: LCDs + 2D engine A, engine A on top
    ldr r1, =0x8003
    strh r1, [r0]
    ldr r0, =0x04000000     @ DISPCNT A: display mode 2 (VRAM), bank A
    ldr r1, =0x00020000
    str r1, [r0]
    ldr r0, =0x04000240     @ VRAMCNT_A: enabled, LCDC
    mov r1, #0x80
    strb r1, [r0]
loop:
    ldr r2, =0x04000130     @ KEYINPUT, active low
    ldrh r3, [r2]
    tst r3, #1
    ldreq r4, =0x001F       @ red (BGR555)
    ldrne r4, =0x7C00       @ blue
    ldr r0, =0x06800000
    mov r5, #0x6000         @ 256 * 96 pixels
1:  strh r4, [r0], #2
    subs r5, r5, #1
    bne 1b
    ldr r6, =0x03E0         @ green
    mov r5, #0x6000
2:  strh r6, [r0], #2
    subs r5, r5, #1
    bne 2b
    b loop
.ltorg
