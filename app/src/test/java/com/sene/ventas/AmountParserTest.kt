package com.sene.ventas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountParserTest {
    @Test fun parsesMexicanDecimalComma() {
        assertEquals(1234.56, parseAmount("1.234,56")!!, 0.000001)
    }

    @Test fun parsesUsDecimalPoint() {
        assertEquals(1234.56, parseAmount("1,234.56")!!, 0.000001)
    }

    @Test fun treatsThreeDigitCommaSuffixAsThousands() {
        assertEquals(1234.0, parseAmount("1,234")!!, 0.000001)
    }

    @Test fun acceptsCurrencyAndWhitespace() {
        assertEquals(25.5, parseAmount(" $ 25,50 ")!!, 0.000001)
        assertEquals(25.5, parseAmount("$\u00A025,50")!!, 0.000001)
    }

    @Test fun openingCashMustBeFiniteAndNonNegative() {
        assertEquals(0.0, parseOpeningCash("0")!!, 0.000001)
        assertEquals(125.5, parseOpeningCash("$ 125,50")!!, 0.000001)
        assertNull(parseOpeningCash("-0.01"))
        assertNull(parseOpeningCash("NaN"))
        assertNull(parseOpeningCash(""))
    }

    @Test fun rejectsEmptyAndMalformedAmounts() {
        assertNull(parseAmount(""))
        assertNull(parseAmount("abc"))
        assertNull(parseAmount("1.2.3"))
        assertNull(parseAmount("NaN"))
        assertNull(parseAmount("Infinity"))
    }

    @Test fun rejectsValuesWithTrailingGarbageAndKeepsNegativeAmountsParseable() {
        assertNull(parseAmount("25,50 MXN"))
        assertEquals(-12.5, parseAmount("-12,50")!!, 0.000001)
        assertNull(parseOpeningCash("-12,50"))
    }
}