package io.github.lcebot.ferryqr.content

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sample follows a vCard 2.1 export as phones write it: every byte of a value that is not plain
 * ASCII written in quoted-printable, and lines kept to 76 characters by soft line breaks, which may
 * fall between the bytes of one character.
 */
class VCardTest {

    @Test
    fun decodesQuotedPrintableAcrossSoftLineBreaks() {
        val vCard = listOf(
            "BEGIN:VCARD",
            "VERSION:2.1",
            "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=E6=AC=A7=E9=98=B3;=E5=B0=8F=E6=98=8E;;;",
            "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=E6=AC=A7=E9=98=B3=E5=B0=8F=E6=",
            "=98=8E",
            "TEL;CELL:138-0013-8000",
            "ADR;HOME;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:;;=E4=B8=AD=E5=85=B3=E6=9D=91=",
            "=E5=A4=A7=E8=A1=97=31=E5=8F=B7;=E5=8C=97=E4=BA=AC;;=31=30=30=30=38=30;",
            "END:VCARD",
        ).joinToString("\r\n")
        val contact = ContactParser.vCard(vCard)
        assertEquals("欧阳小明", contact.name)
        assertEquals(listOf("138-0013-8000"), contact.phones)
        assertEquals("中关村大街1号, 北京, 100080", contact.address)
    }
}
