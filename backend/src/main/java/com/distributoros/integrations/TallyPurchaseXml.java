package com.distributoros.integrations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** Purchase voucher: purchase ledger and input GST debited, supplier credited. */
public final class TallyPurchaseXml {
    private TallyPurchaseXml() {}

    public static String purchase(String company, LocalDate date, String number, String supplierInvoiceNo, String supplier,
                                  String purchaseLedger, String inputCgst, String inputSgst, BigDecimal taxable, BigDecimal tax) {
        BigDecimal half = tax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        BigDecimal other = tax.subtract(half);
        BigDecimal total = taxable.add(tax);
        String v = "<VOUCHER VCHTYPE=\"Purchase\" ACTION=\"Create\">"
                + "<DATE>" + date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "</DATE><VOUCHERTYPENAME>Purchase</VOUCHERTYPENAME>"
                + "<VOUCHERNUMBER>" + TallyXml.esc(number) + "</VOUCHERNUMBER>"
                + "<REFERENCE>" + TallyXml.esc(supplierInvoiceNo) + "</REFERENCE>"
                + "<PARTYLEDGERNAME>" + TallyXml.esc(supplier) + "</PARTYLEDGERNAME><PERSISTEDVIEW>Accounting Voucher View</PERSISTEDVIEW>"
                + entry(supplier, false, total)
                + entry(purchaseLedger, true, taxable)
                + entry(inputCgst, true, half)
                + entry(inputSgst, true, other)
                + "</VOUCHER>";
        return TallyXml.envelope(company, v);
    }

    private static String entry(String ledger, boolean debit, BigDecimal amount) {
        return "<ALLLEDGERENTRIES.LIST><LEDGERNAME>" + TallyXml.esc(ledger) + "</LEDGERNAME>"
                + "<ISDEEMEDPOSITIVE>" + (debit ? "Yes" : "No") + "</ISDEEMEDPOSITIVE>"
                + "<AMOUNT>" + (debit ? "-" : "") + amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + "</AMOUNT></ALLLEDGERENTRIES.LIST>";
    }
}
