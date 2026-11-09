/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: pure-Kotlin in-place StackBlur for downscaled ARGB_8888 backdrop bitmaps.
 */
package org.fcitx.fcitx5.android.input.glass

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object StackBlur {

    fun blur(bitmap: Bitmap, radius: Int) {
        val r = radius.coerceIn(1, 60)
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 1 || h <= 1) return

        val wh = w * h
        val pix = IntArray(wh)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val div = r + r + 1

        val rBuf = IntArray(wh)
        val gBuf = IntArray(wh)
        val bBuf = IntArray(wh)

        val vmin = IntArray(max(w, h))
        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (i in 0 until 256 * divsum) {
            dv[i] = i / divsum
        }

        val stack = IntArray(div * 3)
        val r1 = r + 1
        var yw = 0
        var yi = 0

        for (y in 0 until h) {
            var rinsum = 0
            var ginsum = 0
            var binsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var rsum = 0
            var gsum = 0
            var bsum = 0

            for (i in -r..r) {
                val p = pix[yi + min(wm, max(i, 0))]
                val sir = (i + r) * 3
                val pr = (p shr 16) and 0xff
                val pg = (p shr 8) and 0xff
                val pb = p and 0xff
                stack[sir] = pr
                stack[sir + 1] = pg
                stack[sir + 2] = pb
                val rbs = r1 - abs(i)
                rsum += pr * rbs
                gsum += pg * rbs
                bsum += pb * rbs
                if (i > 0) {
                    rinsum += pr
                    ginsum += pg
                    binsum += pb
                } else {
                    routsum += pr
                    goutsum += pg
                    boutsum += pb
                }
            }
            var stackpointer = r

            for (x in 0 until w) {
                rBuf[yi] = dv[rsum]
                gBuf[yi] = dv[gsum]
                bBuf[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                val stackstart = (stackpointer - r + div) % div
                val sirStart = stackstart * 3
                routsum -= stack[sirStart]
                goutsum -= stack[sirStart + 1]
                boutsum -= stack[sirStart + 2]

                if (y == 0) {
                    vmin[x] = min(x + r + 1, wm)
                }
                val p = pix[yw + vmin[x]]
                val pr = (p shr 16) and 0xff
                val pg = (p shr 8) and 0xff
                val pb = p and 0xff
                stack[sirStart] = pr
                stack[sirStart + 1] = pg
                stack[sirStart + 2] = pb

                rinsum += pr
                ginsum += pg
                binsum += pb

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                val sir = stackpointer * 3
                val sr = stack[sir]
                val sg = stack[sir + 1]
                val sb = stack[sir + 2]

                routsum += sr
                goutsum += sg
                boutsum += sb

                rinsum -= sr
                ginsum -= sg
                binsum -= sb

                yi++
            }
            yw += w
        }

        for (x in 0 until w) {
            var rinsum = 0
            var ginsum = 0
            var binsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var rsum = 0
            var gsum = 0
            var bsum = 0
            var yp = -r * w

            for (i in -r..r) {
                yi = max(0, yp) + x
                val sir = (i + r) * 3
                val sr = rBuf[yi]
                val sg = gBuf[yi]
                val sb = bBuf[yi]
                stack[sir] = sr
                stack[sir + 1] = sg
                stack[sir + 2] = sb
                val rbs = r1 - abs(i)
                rsum += sr * rbs
                gsum += sg * rbs
                bsum += sb * rbs
                if (i > 0) {
                    rinsum += sr
                    ginsum += sg
                    binsum += sb
                } else {
                    routsum += sr
                    goutsum += sg
                    boutsum += sb
                }
                if (i < hm) {
                    yp += w
                }
            }
            yi = x
            var stackpointer = r
            for (y in 0 until h) {
                pix[yi] = (0xff shl 24) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                val stackstart = (stackpointer - r + div) % div
                val sirStart = stackstart * 3
                routsum -= stack[sirStart]
                goutsum -= stack[sirStart + 1]
                boutsum -= stack[sirStart + 2]

                if (x == 0) {
                    vmin[y] = min(y + r1, hm) * w
                }
                val p = x + vmin[y]
                val sr = rBuf[p]
                val sg = gBuf[p]
                val sb = bBuf[p]
                stack[sirStart] = sr
                stack[sirStart + 1] = sg
                stack[sirStart + 2] = sb

                rinsum += sr
                ginsum += sg
                binsum += sb

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                val sir = stackpointer * 3
                val sr2 = stack[sir]
                val sg2 = stack[sir + 1]
                val sb2 = stack[sir + 2]

                routsum += sr2
                goutsum += sg2
                boutsum += sb2

                rinsum -= sr2
                ginsum -= sg2
                binsum -= sb2

                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }
}
