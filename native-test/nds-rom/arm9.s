@ ARM9 side of the test ROM: shows a frame on the top screen using VRAM
@ display mode. Upper half blue (red while A is held), lower half green.
@ A frame counter lives in main RAM at 0x02100000 (so cheats can poke it)
@ and is drawn as 12 bits in the first pixels of the top screen: red = 1.
@ Filling VRAM takes longer than a frame, so the halves are only painted
@ when their colour changes; the counter is written every frame.
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
    ldr r0, =0x0680C000     @ lower half: green, once
    ldr r6, =0x03E0
    mov r5, #0x6000         @ 256 * 96 pixels
1:  strh r6, [r0], #2
    subs r5, r5, #1
    bne 1b
    mov r10, #0             @ colour currently on the upper half (none yet)
    ldr r9, =0x02100000     @ frame counter
    mov r1, #0
    str r1, [r9]
loop:
    ldr r2, =0x04000006     @ VCOUNT: one pass per frame, at line 192
2:  ldrh r3, [r2]
    cmp r3, #192
    beq 2b
3:  ldrh r3, [r2]
    cmp r3, #192
    bne 3b
    ldr r8, [r9]
    add r8, r8, #1
    str r8, [r9]

    ldr r0, =0x06800000     @ counter bits over the first 12 pixels
    mov r5, #0
4:  mov r3, r8, lsr r5
    tst r3, #1
    ldrne r7, =0x001F
    moveq r7, #0
    strh r7, [r0], #2
    add r5, r5, #1
    cmp r5, #12
    bne 4b

    ldr r2, =0x04000130     @ KEYINPUT, active low
    ldrh r3, [r2]
    tst r3, #1
    ldreq r4, =0x001F       @ red (BGR555)
    ldrne r4, =0x7C00       @ blue
    cmp r4, r10
    beq loop
    mov r10, r4
    ldr r0, =0x06800018     @ upper half, after the counter pixels
    ldr r5, =0x5FF4
5:  strh r4, [r0], #2
    subs r5, r5, #1
    bne 5b
    b loop
.ltorg
