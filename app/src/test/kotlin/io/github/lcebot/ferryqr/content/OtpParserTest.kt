package io.github.lcebot.ferryqr.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The links follow Google Authenticator's key URI format and the ways real ones stray from it. */
class OtpParserTest {

    @Test
    fun readsTheExampleFromTheFormat() {
        val key = OtpParser.parse(
            "otpauth://totp/ACME%20Co:john.doe@email.com?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ&issuer=ACME%20Co&algorithm=SHA1&digits=6&period=30",
        )!!
        assertEquals("totp", key.type)
        assertEquals("ACME Co", key.issuer)
        assertEquals("john.doe@email.com", key.account)
        assertEquals("HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ", key.secret)
        assertEquals("SHA1", key.algorithm)
        assertEquals(6, key.digits)
        assertEquals(30, key.period)
        assertNull(key.counter)
    }

    @Test
    fun splitsTheLabelAtAnEncodedColonAndTrimsTheAccount() {
        val key = OtpParser.parse("otpauth://totp/Big%20Corporation%3A%20alice%40bigco.com?secret=JBSWY3DPEHPK3PXP")!!
        assertEquals("Big Corporation", key.issuer)
        assertEquals("alice@bigco.com", key.account)
    }

    @Test
    fun takesTheIssuerParameterOverTheLabel() {
        val key = OtpParser.parse("otpauth://totp/Old%20Name:alice?secret=JBSWY3DPEHPK3PXP&issuer=New+Name")!!
        assertEquals("New Name", key.issuer)
        assertEquals("alice", key.account)
    }

    @Test
    fun keepsAPlusInTheLabel() {
        val key = OtpParser.parse("otpauth://totp/alice+tag@example.com?secret=JBSWY3DPEHPK3PXP")!!
        assertNull(key.issuer)
        assertEquals("alice+tag@example.com", key.account)
    }

    @Test
    fun acceptsLooseSecretsAndAnyCase() {
        val key = OtpParser.parse("OTPAUTH://TOTP/Example:alice?Secret=jbsw%20y3dp-ehpk-3pxp====")!!
        assertEquals("totp", key.type)
        assertEquals("JBSWY3DPEHPK3PXP", key.secret)
    }

    @Test
    fun readsAnHotpCounterAndKeepsOtherTypes() {
        assertEquals(42L, OtpParser.parse("otpauth://hotp/Example:alice?secret=JBSWY3DPEHPK3PXP&counter=42")!!.counter)
        assertEquals("steam", OtpParser.parse("otpauth://steam/Steam:alice?secret=JBSWY3DPEHPK3PXP")!!.type)
    }

    @Test
    fun leavesAnEmptyLabelWithoutNames() {
        val key = OtpParser.parse("otpauth://totp/?secret=JBSWY3DPEHPK3PXP")!!
        assertNull(key.issuer)
        assertNull(key.account)
    }

    @Test
    fun refusesLinksThatAddNoKey() {
        assertNull(OtpParser.parse("otpauth://totp/Example:alice?issuer=Example"))
        assertNull(OtpParser.parse("otpauth://totp/Example:alice?secret=NOT-BASE32-1890"))
        assertNull(OtpParser.parse("otpauth://hotp/Example:alice?secret=JBSWY3DPEHPK3PXP"))
        assertNull(OtpParser.parse("otpauth://?secret=JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun classifiesKeysAndLeavesBrokenLinksAsLinks() {
        val key = Classifier.classify("otpauth://totp/Example:alice smith?secret=JBSWY3DPEHPK3PXP")
        assertIs<OtpPayload>(key)
        assertEquals("alice smith", key.key.account)
        assertIs<SchemePayload>(Classifier.classify("otpauth://totp/Example:alice?issuer=Example"))
    }

    @Test
    fun marksSensitiveKinds() {
        assertEquals(SensitiveKind.OTP, Classifier.classify("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP").sensitiveKind)
        assertEquals(SensitiveKind.WIFI_PASSWORD, Classifier.classify("WIFI:T:WPA;S:Home;P:secret;;").sensitiveKind)
        assertNull(Classifier.classify("WIFI:T:nopass;S:Cafe;;").sensitiveKind)
        assertNull(Classifier.classify("https://example.com").sensitiveKind)
    }
}
