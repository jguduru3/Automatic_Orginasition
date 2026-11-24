package com.distributoros.integrations;

/** Stands in for TallyPrime: accepts every voucher and answers exports with a small realistic sample. */
public class MockTallyClient implements TallyClient {
    @Override
    public String post(String xmlEnvelope, String fallbackNumber) {
        return fallbackNumber;
    }

    @Override
    public String export(String xmlRequest) {
        if (xmlRequest.contains("<TYPE>Ledger</TYPE>")) {
            return """
                    <ENVELOPE><BODY><DATA><COLLECTION>
                    <LEDGER NAME="Mahalaxmi Electricals"><PARENT>Sundry Debtors</PARENT><LEDGERMOBILE>9822041001</LEDGERMOBILE><PARTYGSTIN>27AAAPM1111A1Z1</PARTYGSTIN><CLOSINGBALANCE>-269100.00</CLOSINGBALANCE></LEDGER>
                    <LEDGER NAME="Sunrise Electric Works"><PARENT>Sundry Debtors</PARENT><LEDGERMOBILE>+91 98230 55555</LEDGERMOBILE><PARTYGSTIN></PARTYGSTIN><CLOSINGBALANCE>-41200.00</CLOSINGBALANCE></LEDGER>
                    <LEDGER NAME="Walk-in Cash Sales"><PARENT>Sundry Debtors</PARENT><LEDGERMOBILE></LEDGERMOBILE><CLOSINGBALANCE>0</CLOSINGBALANCE></LEDGER>
                    <LEDGER NAME="Volt Cables Pvt Ltd"><PARENT>Sundry Creditors</PARENT><LEDGERMOBILE>9820011100</LEDGERMOBILE><CLOSINGBALANCE>182000.00</CLOSINGBALANCE></LEDGER>
                    </COLLECTION></DATA></BODY></ENVELOPE>
                    """;
        }
        return """
                <ENVELOPE><BODY><DATA><COLLECTION>
                <STOCKITEM NAME="FR House Wire 1.5 sq mm"><PARENT>Wires</PARENT><BASEUNITS>coil</BASEUNITS><CLOSINGBALANCE> 335 coil</CLOSINGBALANCE><CLOSINGRATE>1,180.00/coil</CLOSINGRATE><STANDARDPRICE>1450.00/coil</STANDARDPRICE></STOCKITEM>
                <STOCKITEM NAME="MCB 32A SP C-curve"><PARENT>Switchgear</PARENT><BASEUNITS>pc</BASEUNITS><CLOSINGBALANCE> 640 pc</CLOSINGBALANCE><CLOSINGRATE>142.00/pc</CLOSINGRATE><STANDARDPRICE>180.00/pc</STANDARDPRICE></STOCKITEM>
                <STOCKITEM NAME="Isolator 63A 4P"><PARENT>Switchgear</PARENT><BASEUNITS>pc</BASEUNITS><CLOSINGBALANCE> 18 pc</CLOSINGBALANCE><CLOSINGRATE>890.00/pc</CLOSINGRATE><STANDARDPRICE>1,240.00/pc</STANDARDPRICE></STOCKITEM>
                <STOCKITEM NAME="Bad &amp; Odd &lt;Item&gt;"><PARENT></PARENT><BASEUNITS></BASEUNITS><CLOSINGBALANCE></CLOSINGBALANCE></STOCKITEM>
                </COLLECTION></DATA></BODY></ENVELOPE>
                """;
    }
}
