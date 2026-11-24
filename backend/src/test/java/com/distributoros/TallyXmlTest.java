package com.distributoros;

import com.distributoros.integrations.TallyXml;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TallyXmlTest {
    @Test void salesVoucherIsWellFormedAndBalanced() throws Exception {
        String x = TallyXml.sales("Co & Co", LocalDate.of(2026, 11, 24), "INV-1", "Om Sai <Electric> & 'Hardware'",
                "Sales", "CGST", "SGST", new BigDecimal("100.00"), new BigDecimal("18.01"));
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(x.getBytes(StandardCharsets.UTF_8)));
        assertThat(x).contains("<AMOUNT>-118.01</AMOUNT>", "<AMOUNT>100.00</AMOUNT>", "<AMOUNT>9.01</AMOUNT>", "<AMOUNT>9.00</AMOUNT>");
        assertThat(x).contains("<DATE>20261124</DATE>");
    }

    @Test void stripsControlCharacters() {
        assertThat(TallyXml.esc("a\u0001b<")).isEqualTo("ab&lt;");
    }
}
