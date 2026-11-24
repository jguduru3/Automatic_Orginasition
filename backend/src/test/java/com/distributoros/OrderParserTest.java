package com.distributoros;

import com.distributoros.inbox.OrderParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class OrderParserTest {
    static final OrderParser P = new OrderParser(List.of(
            OrderParser.ProductPatterns.of(1, List.of("1\\.5\\s*(sq\\s*mm|sqmm|mm)?\\s*wire", "wire\\s*1\\.5(\\s*(sq\\s*mm|mm))?")),
            OrderParser.ProductPatterns.of(5, List.of("32\\s*a\\s*(sp\\s*)?mcb", "mcb\\s*32\\s*a")),
            OrderParser.ProductPatterns.of(7, List.of("rccb(\\s*40\\s*a)?(\\s*4\\s*p(ole)?)?")),
            OrderParser.ProductPatterns.of(13, List.of("6\\s*a\\s*switch(es)?")),
            OrderParser.ProductPatterns.of(99, List.of("([invalid"))));

    static List<Object> flat(String text) {
        List<Object> out = new ArrayList<>();
        P.parse(text).forEach(l -> { out.add(l.productId()); out.add(l.qty()); });
        return out;
    }

    @Test void parsesTypicalRetailerMessage() {
        assertThat(flat("Good morning bhaiya\n1.5 wire - 20 coil\n32A MCB - 100 pcs\nRCCB 40A 4 pole x 30\nPlease send by evening"))
                .containsExactly(1L, 20, 5L, 100, 7L, 30);
    }

    @Test void prefersExplicitQuantityOverSpecNumbers() {
        assertThat(flat("changeover switch 63A x 2")).containsExactly(null, 2);
        assertThat(flat("6A switch 10 box")).containsExactly(13L, 10);
    }

    @Test void splitsOnCommasAndAnd() {
        assertThat(flat("1.5 wire 5 coil, 32A MCB 10 and rccb 2")).containsExactly(1L, 5, 5L, 10, 7L, 2);
    }

    @Test void ignoresChatterAndEmpty() {
        assertThat(P.parse("")).isEmpty();
        assertThat(P.parse(null)).isEmpty();
        assertThat(P.parse("thank you sir\nok")).isEmpty();
    }

    @Test void capsQuantityAndLines() {
        assertThat(flat("rccb 99999999999999")).containsExactly(7L, OrderParser.MAX_QTY);
        assertThat(P.parse("rccb 1\n".repeat(500))).hasSize(OrderParser.MAX_LINES);
    }

    @Test void handlesUnicodeAndBrackets() {
        assertThat(flat("[Voice note] मुझे 1.5 wire 10 coil चाहिए")).containsExactly(1L, 10);
    }

    @Test void fuzzNeverThrows() {
        Random r = new Random(7);
        for (int i = 0; i < 3000; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = r.nextInt(500); j > 0; j--) sb.append((char) (1 + r.nextInt(0x2FFF)));
            P.parse(sb.toString());
        }
    }
}
