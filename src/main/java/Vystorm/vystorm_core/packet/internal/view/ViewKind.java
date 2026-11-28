package Vystorm.vystorm_core.packet.internal.view;

/** What the byte side does with an encoded buffer: the three packets whose bytes the view rewrites. Internal. */
public enum ViewKind { CHUNK, LIGHT, SECTION }
