package com.lcebot.ferryqr.content

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierTest {

    private inline fun <reified T : Payload> classifyAs(raw: String): T {
        val payload = Classifier.classify(raw)
        assertTrue("expected ${T::class.simpleName} for '$raw' but got $payload", payload is T)
        return payload as T
    }

    @Test
    fun webLinks() {
        val web = classifyAs<WebPayload>("https://Example.com/path?q=1")
        assertEquals("example.com", web.host)
        assertEquals("https://Example.com/path?q=1", web.url)
        assertEquals("user.example.org", classifyAs<WebPayload>("http://me@user.example.org:8080/").host)
    }

    @Test
    fun bareDomainsNeedAHint() {
        assertEquals("https://www.example.com", classifyAs<WebPayload>("www.example.com").url)
        assertEquals("https://example.com/a", classifyAs<WebPayload>("example.com/a").url)
        classifyAs<TextPayload>("example.com")
        classifyAs<TextPayload>("3.14")
    }

    @Test
    fun portIsNotAScheme() {
        assertEquals("example.com", classifyAs<WebPayload>("example.com:8080/x").host)
    }

    @Test
    fun otherSchemes() {
        assertEquals("weixin", classifyAs<SchemePayload>("weixin://dl/scan").scheme)
        assertEquals("otpauth", classifyAs<SchemePayload>("otpauth://totp/a?secret=B").scheme)
        assertEquals("intent", classifyAs<SchemePayload>("intent://x#Intent;scheme=y;end").scheme)
        assertEquals("tel", classifyAs<SchemePayload>("tel:+8610086").scheme)
    }

    @Test
    fun dangerousSchemesStayText() {
        classifyAs<TextPayload>("javascript:alert(1)")
        classifyAs<TextPayload>("file:///sdcard/a")
        classifyAs<TextPayload>("content://media/external")
    }

    @Test
    fun textWithColonIsText() {
        classifyAs<TextPayload>("Note: hello world")
        classifyAs<TextPayload>("hello")
    }

    @Test
    fun fido() {
        classifyAs<FidoPayload>("FIDO:/1234567890")
        classifyAs<FidoPayload>("fido:/1234567890")
    }

    @Test
    fun esim() {
        classifyAs<EsimPayload>("LPA:1\$rsp.example.com\$ABC-123")
        classifyAs<EsimPayload>("1\$rsp.example.com\$ABC-123")
    }

    @Test
    fun dpp() {
        classifyAs<DppPayload>("DPP:K:MDkwEwYHKoZIzj0CAQ;;")
    }

    @Test
    fun wifiWithEscapes() {
        val wifi = classifyAs<WifiPayload>("WIFI:T:WPA;S:my\\;net;P:pa\\:ss\\\\word;H:true;;")
        assertEquals("my;net", wifi.ssid)
        assertEquals("pa:ss\\word", wifi.password)
        assertEquals(WifiSecurity.WPA, wifi.security)
        assertTrue(wifi.hidden)
    }

    @Test
    fun wifiSecurityTypes() {
        assertEquals(WifiSecurity.OPEN, classifyAs<WifiPayload>("WIFI:T:nopass;S:cafe;;").security)
        assertEquals(WifiSecurity.SAE, classifyAs<WifiPayload>("WIFI:T:SAE;S:a;P:12345678;;").security)
        assertEquals(WifiSecurity.EAP, classifyAs<WifiPayload>("WIFI:T:WPA2-EAP;S:corp;;").security)
        assertEquals(WifiSecurity.WEP, classifyAs<WifiPayload>("WIFI:T:WEP;S:old;P:abcde;;").security)
        assertEquals("quoted", classifyAs<WifiPayload>("WIFI:S:\"quoted\";T:WPA;P:\"12345678\";;").ssid)
        assertFalse(classifyAs<WifiPayload>("WIFI:S:x;;").hidden)
        classifyAs<TextPayload>("WIFI:T:WPA;P:nossid;;")
    }

    @Test
    fun meCard() {
        val contact = classifyAs<ContactPayload>("MECARD:N:Doe,John;TEL:+1234;EMAIL:j@d.com;ORG:ACME;;").contact
        assertEquals("John Doe", contact.name)
        assertEquals(listOf("+1234"), contact.phones)
        assertEquals(listOf("j@d.com"), contact.emails)
        assertEquals("ACME", contact.organization)
        assertEquals("张三", classifyAs<ContactPayload>("MECARD:N:张,三;;").contact.name)
    }

    @Test
    fun vCard() {
        val raw = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Doe;Jane;;;\r\nFN:Jane Doe\r\n" +
            "TEL;TYPE=cell:+1 555\r\nitem1.EMAIL;TYPE=work:jane@example.com\r\n" +
            "ADR:;;1 Main St;Springfield;;12345;US\r\nNOTE:line one\\nline two\r\n" +
            " continued\r\nEND:VCARD"
        val contact = classifyAs<ContactPayload>(raw).contact
        assertEquals("Jane Doe", contact.name)
        assertEquals(listOf("+1 555"), contact.phones)
        assertEquals(listOf("jane@example.com"), contact.emails)
        assertEquals("1 Main St, Springfield, 12345, US", contact.address)
        assertEquals("line one\nline twocontinued", contact.note)
    }

    @Test
    fun vEventInUtc() {
        val raw = "BEGIN:VEVENT\nSUMMARY:Launch\nDTSTART:20261026T090000Z\nDTEND:20261026T100000Z\n" +
            "LOCATION:Room 1\nEND:VEVENT"
        val event = classifyAs<EventPayload>(raw).event
        assertEquals("Launch", event.title)
        assertEquals(1793005200000L, event.begin)
        assertEquals(1793008800000L, event.end)
        assertFalse(event.allDay)
        assertEquals("Room 1", event.location)
    }

    @Test
    fun vEventAllDay() {
        val event = EventParser.parse("BEGIN:VEVENT\nSUMMARY:Day\nDTSTART;VALUE=DATE:20261026\nEND:VEVENT", ZoneOffset.UTC)!!
        assertTrue(event.allDay)
        assertEquals(1792972800000L, event.begin)
        assertNull(event.end)
    }

    @Test
    fun matmsgBecomesMailto() {
        val mail = classifyAs<SchemePayload>("MATMSG:TO:a@b.com;SUB:Hi there;BODY:x&y;;")
        assertEquals("mailto:a%40b.com?subject=Hi%20there&body=x%26y", mail.uri)
    }

    @Test
    fun smstoBecomesUri() {
        assertEquals("smsto:10086?body=CXLL", classifyAs<SchemePayload>("SMSTO:10086:CXLL").uri)
        assertEquals("smsto:10086?body=a%20b", classifyAs<SchemePayload>("SMS:10086:a b").uri)
        assertEquals("sms", classifyAs<SchemePayload>("sms:10086?body=x").scheme)
    }

    @Test
    fun whitespaceIsTrimmed() {
        assertEquals("example.com", classifyAs<WebPayload>("  https://example.com \n").host)
    }
}
