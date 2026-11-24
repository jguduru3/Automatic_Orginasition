package com.distributoros.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaAndTallyParsingTest {
    final ObjectMapper json = new ObjectMapper();

    @Test void speechResponses() {
        assertThat(MediaReader.parseSpeech(json, "{\"RecognitionStatus\":\"Success\",\"DisplayText\":\"1.5 wire 20 coil, 32A MCB 100 pieces.\"}"))
                .isEqualTo("1.5 wire 20 coil, 32A MCB 100 pieces.");
        assertThat(MediaReader.parseSpeech(json, "{\"RecognitionStatus\":\"NoMatch\"}")).isNull();
        assertThat(MediaReader.parseSpeech(json, "not json")).isNull();
    }

    @Test void visionReadResponses() {
        String r = "{\"readResult\":{\"blocks\":[{\"lines\":[{\"text\":\"Dolo 650 - 40\"},{\"text\":\"Amox 500 x 20\"}]}]}}";
        assertThat(MediaReader.parseRead(json, r)).isEqualTo("Dolo 650 - 40\nAmox 500 x 20");
        assertThat(MediaReader.parseRead(json, "{\"readResult\":{\"blocks\":[]}}")).isNull();
    }

    @Test void tallyMastersFromMock() throws Exception {
        var m = new MockTallyClient();
        var items = TallyMasters.stockItems(m.export(TallyMasters.stockItemsRequest("Co")));
        assertThat(items).extracting(TallyMasters.StockItem::name).contains("FR House Wire 1.5 sq mm", "Isolator 63A 4P");
        assertThat(items.get(0).closingQty()).isEqualByComparingTo("335");
        assertThat(items.get(2).rate()).isEqualByComparingTo("1240");
        var ledgers = TallyMasters.ledgers(m.export(TallyMasters.ledgersRequest("Co")));
        assertThat(TallyMasters.phone(ledgers.get(1).mobile())).isEqualTo("919823055555");
        assertThat(TallyMasters.phone("")).isNull();
    }

    @Test void tallyParserRejectsXxe() {
        assertThatThrownBy(() -> TallyMasters.stockItems(
                "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><ENVELOPE><STOCKITEM NAME=\"&e;\"/></ENVELOPE>"))
                .isInstanceOf(Exception.class);
    }

    @Test void purchaseVoucherBalances() throws Exception {
        String x = TallyPurchaseXml.purchase("Co", java.time.LocalDate.of(2026, 11, 24), "GRN-1", "INV/9", "Supplier & Co",
                "Purchase", "In CGST", "In SGST", new java.math.BigDecimal("1000"), new java.math.BigDecimal("180"));
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(x.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(x).contains("<AMOUNT>1180.00</AMOUNT>", "<AMOUNT>-1000.00</AMOUNT>", "<AMOUNT>-90.00</AMOUNT>");
    }
}
