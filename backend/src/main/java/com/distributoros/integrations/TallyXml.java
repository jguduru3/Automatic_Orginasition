package com.distributoros.integrations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Builds TallyPrime import envelopes. Amounts: negative = debit, positive = credit (Tally convention). */
public final class TallyXml {
    private static final DateTimeFormatter D = DateTimeFormatter.BASIC_ISO_DATE;

    private TallyXml() {}

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> { if (c >= 0x20 || c == '\n' || c == '\t') b.append(c); }
            }
        }
        return b.toString();
    }

    private static String amt(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    private static String envelope(String company, String voucher) {
        String sv = company == null || company.isBlank() ? "" :
                "<STATICVARIABLES><SVCURRENTCOMPANY>" + esc(company) + "</SVCURRENTCOMPANY></STATICVARIABLES>";
        return "<ENVELOPE><HEADER><TALLYREQUEST>Import Data</TALLYREQUEST></HEADER><BODY><IMPORTDATA>"
                + "<REQUESTDESC><REPORTNAME>Vouchers</REPORTNAME>" + sv + "</REQUESTDESC>"
                + "<REQUESTDATA><TALLYMESSAGE xmlns:UDF=\"TallyUDF\">" + voucher + "</TALLYMESSAGE></REQUESTDATA>"
                + "</IMPORTDATA></BODY></ENVELOPE>";
    }

    public static String sales(String company, LocalDate date, String number, String party, String salesLedger,
                               String cgstLedger, String sgstLedger, BigDecimal taxable, BigDecimal tax) {
        BigDecimal half = tax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        BigDecimal other = tax.subtract(half);
        BigDecimal total = taxable.add(tax);
        String v = "<VOUCHER VCHTYPE=\"Sales\" ACTION=\"Create\">"
                + "<DATE>" + D.format(date) + "</DATE><VOUCHERTYPENAME>Sales</VOUCHERTYPENAME>"
                + "<VOUCHERNUMBER>" + esc(number) + "</VOUCHERNUMBER>"
                + "<PARTYLEDGERNAME>" + esc(party) + "</PARTYLEDGERNAME><PERSISTEDVIEW>Accounting Voucher View</PERSISTEDVIEW>"
                + ledger(party, true, total)
                + ledger(salesLedger, false, taxable)
                + ledger(cgstLedger, false, half)
                + ledger(sgstLedger, false, other)
                + "</VOUCHER>";
        return envelope(company, v);
    }

    public static String receipt(String company, LocalDate date, String number, String party, String bankLedger,
                                 BigDecimal amount, String narration) {
        String v = "<VOUCHER VCHTYPE=\"Receipt\" ACTION=\"Create\">"
                + "<DATE>" + D.format(date) + "</DATE><VOUCHERTYPENAME>Receipt</VOUCHERTYPENAME>"
                + "<VOUCHERNUMBER>" + esc(number) + "</VOUCHERNUMBER>"
                + "<NARRATION>" + esc(narration) + "</NARRATION>"
                + ledger(bankLedger, true, amount)
                + ledger(party, false, amount)
                + "</VOUCHER>";
        return envelope(company, v);
    }

    private static String ledger(String name, boolean debit, BigDecimal amount) {
        return "<ALLLEDGERENTRIES.LIST><LEDGERNAME>" + esc(name) + "</LEDGERNAME>"
                + "<ISDEEMEDPOSITIVE>" + (debit ? "Yes" : "No") + "</ISDEEMEDPOSITIVE>"
                + "<AMOUNT>" + (debit ? "-" : "") + amt(amount) + "</AMOUNT></ALLLEDGERENTRIES.LIST>";
    }
}
