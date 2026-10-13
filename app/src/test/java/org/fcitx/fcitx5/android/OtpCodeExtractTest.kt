/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.otp.OtpCodeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpCodeExtractTest {

    private fun code(body: String) = OtpCodeManager.extract(body)

    @Test
    fun chineseBankAndApps() {
        assertEquals("123456", code("【招商银行】您的验证码为123456，有效期5分钟，请勿泄露。"))
        assertEquals("482913", code("验证码：482913。您正在登录淘宝，若非本人操作请忽略。【淘宝】"))
        assertEquals("7731", code("【美团】7731（登录验证码），请于2分钟内填写。"))
        assertEquals("093812", code("您的动态密码是 093812，尾号8836的卡将于10分钟内完成交易。"))
        assertEquals("556677", code("【某银行】您尾号1234的账户正在支付58.00元，校验码556677，5分钟内有效。"))
    }

    @Test
    fun englishAndAlphanumeric() {
        assertEquals("847261", code("Your verification code is 847261. It expires in 10 minutes."))
        assertEquals("G-123456".removePrefix("G-"), code("G-123456 is your Google verification code."))
        assertEquals("A7K2Q9", code("【Steam】您的验证码是 A7K2Q9"))
    }

    @Test
    fun notVerificationMessages() {
        assertNull(code("【京东】您的订单 12345678 已发货，预计明天送达。"))
        assertNull(code("您尾号1234的卡消费 2000 元，余额 8888.88 元。"))
    }

    @Test
    fun skipsAmountsDurationsAndCardTails() {
        // only numbers that are amounts / tails / durations besides the code
        assertEquals("2468", code("尾号5678的卡支付100元，验证码2468，30分钟内有效"))
    }
}
