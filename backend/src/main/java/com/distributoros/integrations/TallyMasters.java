package com.distributoros.integrations;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds Tally export requests and parses stock items and ledgers out of the responses. Pure, no I/O. */
public final class TallyMasters {
    private static final Pattern NUM = Pattern.compile("-?[\\d,]+(?:\\.\\d+)?");

    private TallyMasters() {}

    public record StockItem(String name, String group, String unit, BigDecimal closingQty, BigDecimal rate) {}
    public record Ledger(String name, String group, String mobile, String gstin, BigDecimal closingBalance) {}

    public static String request(String company, String collection, String type, String fetch) {
        String sv = company == null || company.isBlank() ? "" : "<SVCURRENTCOMPANY>" + TallyXml.esc(company) + "</SVCURRENTCOMPANY>";
        return "<ENVELOPE><HEADER><VERSION>1</VERSION><TALLYREQUEST>Export</TALLYREQUEST><TYPE>Collection</TYPE><ID>" + collection
                + "</ID></HEADER><BODY><DESC><STATICVARIABLES><SVEXPORTFORMAT>$$SysName:XML</SVEXPORTFORMAT>" + sv
                + "</STATICVARIABLES><TDL><TDLMESSAGE><COLLECTION NAME=\"" + collection + "\" ISMODIFY=\"No\"><TYPE>" + type
                + "</TYPE><FETCH>" + fetch + "</FETCH></COLLECTION></TDLMESSAGE></TDL></DESC></BODY></ENVELOPE>";
    }

    public static String stockItemsRequest(String company) {
        return request(company, "DOSStockItems", "StockItem", "Name,Parent,BaseUnits,ClosingBalance,ClosingRate,StandardPrice");
    }

    public static String ledgersRequest(String company) {
        return request(company, "DOSLedgers", "Ledger", "Name,Parent,LedgerMobile,LedgerPhone,PartyGSTIN,ClosingBalance");
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // no XXE
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setExpandEntityReferences(false);
        // Tally sometimes emits control characters like &#4; that are illegal in XML 1.0
        String clean = xml.replaceAll("&#(?:[0-8]|1[124-9]|2[0-9]|3[01]);", "").replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(clean.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(Element e, String tag) {
        NodeList n = e.getElementsByTagName(tag);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent().trim();
    }

    static BigDecimal number(String s) {
        if (s == null) return null;
        Matcher m = NUM.matcher(s);
        if (!m.find()) return null;
        try {
            return new BigDecimal(m.group().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static List<StockItem> stockItems(String xml) throws Exception {
        List<StockItem> out = new ArrayList<>();
        NodeList items = parse(xml).getElementsByTagName("STOCKITEM");
        for (int i = 0; i < items.getLength(); i++) {
            Element e = (Element) items.item(i);
            String name = e.getAttribute("NAME").trim();
            if (name.isEmpty()) name = text(e, "NAME");
            if (name.isEmpty()) continue;
            String rate = text(e, "STANDARDPRICE");
            if (number(rate) == null || number(rate).signum() == 0) rate = text(e, "CLOSINGRATE");
            out.add(new StockItem(name, text(e, "PARENT"), text(e, "BASEUNITS"), number(text(e, "CLOSINGBALANCE")), number(rate)));
        }
        return out;
    }

    public static List<Ledger> ledgers(String xml) throws Exception {
        List<Ledger> out = new ArrayList<>();
        NodeList items = parse(xml).getElementsByTagName("LEDGER");
        for (int i = 0; i < items.getLength(); i++) {
            Element e = (Element) items.item(i);
            String name = e.getAttribute("NAME").trim();
            if (name.isEmpty()) name = text(e, "NAME");
            if (name.isEmpty()) continue;
            String mobile = text(e, "LEDGERMOBILE");
            if (mobile.isBlank()) mobile = text(e, "LEDGERPHONE");
            out.add(new Ledger(name, text(e, "PARENT"), mobile, text(e, "PARTYGSTIN"), number(text(e, "CLOSINGBALANCE"))));
        }
        return out;
    }

    /** Indian mobile to E.164 digits without '+', or null if it doesn't look like one. */
    public static String phone(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("\\D", "");
        if (d.length() == 11 && d.startsWith("0")) d = d.substring(1);
        if (d.length() == 10) d = "91" + d;
        return d.length() >= 11 && d.length() <= 15 ? d : null;
    }
}
