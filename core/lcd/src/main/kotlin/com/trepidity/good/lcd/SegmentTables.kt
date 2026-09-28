package com.trepidity.good.lcd

/**
 * Seven-segment font. Bits: A top, B upper right, C lower right, D bottom, E lower left, F upper left, G middle.
 * Letters are looked up case-insensitively and drawn in whichever case reads best (b, d, n, o, r, t, y).
 */
internal object SevenSegment {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val C = 1 shl 2
    const val D = 1 shl 3
    const val E = 1 shl 4
    const val F = 1 shl 5
    const val G = 1 shl 6

    private val table: Map<Char, Int> = mapOf(
        '0' to (A or B or C or D or E or F),
        '1' to (B or C),
        '2' to (A or B or G or E or D),
        '3' to (A or B or G or C or D),
        '4' to (F or G or B or C),
        '5' to (A or F or G or C or D),
        '6' to (A or F or G or E or D or C),
        '7' to (A or B or C),
        '8' to (A or B or C or D or E or F or G),
        '9' to (A or B or C or D or F or G),
        ' ' to 0,
        '-' to G,
        'A' to (A or B or C or E or F or G),
        'B' to (C or D or E or F or G),
        'C' to (A or D or E or F),
        'D' to (B or C or D or E or G),
        'E' to (A or D or E or F or G),
        'F' to (A or E or F or G),
        'H' to (B or C or E or F or G),
        'L' to (D or E or F),
        'N' to (C or E or G),
        'O' to (C or D or E or G),
        'P' to (A or B or E or F or G),
        'R' to (E or G),
        'T' to (D or E or F or G),
        'U' to (B or C or D or E or F),
        'Y' to (B or C or D or F or G),
    )

    /** Lit segments for [c]; 0 (blank) for anything the font cannot draw. */
    fun mask(c: Char): Int = table[c.uppercaseChar()] ?: 0
}

/**
 * Fourteen-segment font. Bits: A..F as in [SevenSegment], G1/G2 left and right middle, H upper-left diagonal,
 * I upper centre, J upper-right diagonal, K lower-left diagonal, L lower centre, M lower-right diagonal.
 */
internal object FourteenSegment {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val C = 1 shl 2
    const val D = 1 shl 3
    const val E = 1 shl 4
    const val F = 1 shl 5
    const val G1 = 1 shl 6
    const val G2 = 1 shl 7
    const val H = 1 shl 8
    const val I = 1 shl 9
    const val J = 1 shl 10
    const val K = 1 shl 11
    const val L = 1 shl 12
    const val M = 1 shl 13

    private const val RING = A or B or C or D or E or F

    private val table: Map<Char, Int> = mapOf(
        'A' to (A or B or C or E or F or G1 or G2),
        'B' to (A or B or C or D or G2 or I or L),
        'C' to (A or D or E or F),
        'D' to (A or B or C or D or I or L),
        'E' to (A or D or E or F or G1),
        'F' to (A or E or F or G1),
        'G' to (A or C or D or E or F or G2),
        'H' to (B or C or E or F or G1 or G2),
        'I' to (A or D or I or L),
        'J' to (B or C or D or E),
        'K' to (E or F or G1 or J or M),
        'L' to (D or E or F),
        'M' to (B or C or E or F or H or J),
        'N' to (B or C or E or F or H or M),
        'O' to RING,
        'P' to (A or B or E or F or G1 or G2),
        'Q' to (RING or M),
        'R' to (A or B or E or F or G1 or G2 or M),
        'S' to (A or C or D or F or G1 or G2),
        'T' to (A or I or L),
        'U' to (B or C or D or E or F),
        'V' to (E or F or J or K),
        'W' to (B or C or E or F or K or M),
        'X' to (H or J or K or M),
        'Y' to (H or J or L),
        'Z' to (A or D or J or K),
        '0' to RING,
        '1' to (B or C or J),
        '2' to (A or B or D or E or G1 or G2),
        '3' to (A or B or C or D or G2),
        '4' to (B or C or F or G1 or G2),
        '5' to (A or C or D or F or G1 or G2),
        '6' to (A or C or D or E or F or G1 or G2),
        '7' to (A or B or C),
        '8' to (RING or G1 or G2),
        '9' to (A or B or C or D or F or G1 or G2),
        ' ' to 0,
        '-' to (G1 or G2),
        '+' to (G1 or G2 or I or L),
        '/' to (J or K),
        '*' to (G1 or G2 or H or I or J or K or L or M),
    )

    /** Lit segments for [c]; 0 (blank) for anything the font cannot draw. */
    fun mask(c: Char): Int = table[c.uppercaseChar()] ?: 0
}
