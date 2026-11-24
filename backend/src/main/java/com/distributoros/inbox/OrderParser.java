package com.distributoros.inbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns free-text retailer messages ("1.5 wire - 20 coil", "RCCB 40A 4 pole x 30") into order lines.
 * Each product carries regexes; the first product whose pattern matches a line wins, the matched text is
 * removed and the first remaining integer is the quantity. Pure and thread-safe.
 */
public final class OrderParser {
    public static final int MAX_LINES = 100;
    public static final int MAX_QTY = 100_000;
    private static final Pattern SPLIT = Pattern.compile("\\n|,|;|\\band\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*]");
    // explicit quantity: "x 30", "- 12", "qty 5", or a number followed by a pack unit
    private static final Pattern QTY_EXPLICIT = Pattern.compile(
            "(?:\\bx|×|\\bqty\\.?|\\s-|:)\\s*(\\d+)\\b|(\\d+)\\s*(?:coils?|pcs?|pieces?|nos?|box(?:es)?|pkts?|packets?|bundles?|units?|rolls?)\\b",
            Pattern.CASE_INSENSITIVE);
    // any whole number that is not a spec (32A, 9W, 20mm, 8 way, 4 pole, 1.5)
    private static final Pattern QTY_PLAIN = Pattern.compile(
            "(?<![\\d.])(\\d+)(?![\\d.])(?!\\s*(?:a\\b|amp|w\\b|watt|mm|sq|way|p\\b|pole|v\\b|volt|ma\\b))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    public record ProductPatterns(long productId, List<Pattern> patterns) {
        public static ProductPatterns of(long id, List<String> regexes) {
            List<Pattern> ps = new ArrayList<>();
            for (String r : regexes) {
                try {
                    ps.add(Pattern.compile(r, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                } catch (RuntimeException ignored) {
                    // invalid patterns are rejected on save; skip defensively
                }
            }
            return new ProductPatterns(id, ps);
        }
    }

    /** productId is null when the line looked like an item but matched nothing. */
    public record ParsedLine(String raw, Long productId, int qty) {}

    private final List<ProductPatterns> products;

    public OrderParser(List<ProductPatterns> products) {
        this.products = List.copyOf(products);
    }

    public List<ParsedLine> parse(String text) {
        List<ParsedLine> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String cleaned = BRACKETS.matcher(text.length() > 5000 ? text.substring(0, 5000) : text).replaceAll(" ");
        for (String part : SPLIT.split(cleaned)) {
            String raw = part.trim();
            if (raw.isEmpty()) continue;
            String s = raw.toLowerCase(Locale.ROOT);
            Long hit = null;
            String rest = s;
            outer:
            for (ProductPatterns p : products) {
                for (Pattern pat : p.patterns()) {
                    Matcher m = pat.matcher(s);
                    if (m.find()) {
                        hit = p.productId();
                        rest = s.substring(0, m.start()) + " " + s.substring(m.end());
                        break outer;
                    }
                }
            }
            String qtyText = null;
            Matcher q = QTY_EXPLICIT.matcher(rest);
            if (q.find()) {
                qtyText = q.group(1) != null ? q.group(1) : q.group(2);
            } else {
                Matcher plain = QTY_PLAIN.matcher(rest);
                if (plain.find()) qtyText = plain.group(1);
            }
            if (hit == null && (qtyText == null || !HAS_DIGIT.matcher(s).find())) continue;
            int qty = 1;
            if (qtyText != null) {
                try {
                    qty = Integer.parseInt(qtyText);
                } catch (NumberFormatException e) {
                    qty = MAX_QTY;
                }
            }
            qty = Math.max(1, Math.min(qty, MAX_QTY));
            out.add(new ParsedLine(raw.length() > 200 ? raw.substring(0, 200) : raw, hit, qty));
            if (out.size() >= MAX_LINES) break;
        }
        return out;
    }
}
