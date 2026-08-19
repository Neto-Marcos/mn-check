package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NfeXmlParserTest {
  @Test
  void parsesAccessKeyPartiesAndItems() {
    String xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe">
          <NFe><infNFe Id="NFe35260812345678000199550010000001231123456789">
            <ide><dhEmi>2026-08-18T09:30:00Z</dhEmi></ide>
            <emit><CNPJ>12345678000199</CNPJ><xNome>Fornecedor Teste</xNome></emit>
            <dest><CNPJ>99887766000155</CNPJ><xNome>MN Matriz</xNome></dest>
            <det nItem="1"><prod><cProd>SKU-10</cProd><cEAN>7891234567890</cEAN>
              <xProd>Produto de teste</xProd><qCom>12.0000</qCom></prod></det>
          </infNFe></NFe>
        </nfeProc>
        """;

    NfeXmlParser.Nfe result = NfeXmlParser.parse(xml.getBytes(StandardCharsets.UTF_8));

    assertEquals("35260812345678000199550010000001231123456789", result.accessKey());
    assertEquals("Fornecedor Teste", result.issuer());
    assertEquals("12345678000199", result.issuerTaxId());
    assertEquals("MN Matriz", result.recipient());
    assertEquals("99887766000155", result.recipientTaxId());
    assertEquals(1, result.items().size());
    assertEquals("SKU-10", result.items().get(0).sku());
    assertEquals(12, result.items().get(0).quantity());
  }

  @Test
  void rejectsExternalEntities() {
    String xml = """
        <?xml version="1.0"?>
        <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
        <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe"><NFe><infNFe Id="NFe35260812345678000199550010000001231123456789">
        <emit><xNome>&xxe;</xNome></emit></infNFe></NFe></nfeProc>
        """;
    assertThrows(EnterpriseDatabase.EnterpriseException.class,
        () -> NfeXmlParser.parse(xml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsFractionalUnitQuantity() {
    String xml = """
        <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe"><NFe>
          <infNFe Id="NFe35260812345678000199550010000001231123456789">
            <emit><xNome>Fornecedor</xNome></emit><dest><xNome>MN</xNome></dest>
            <det><prod><cProd>SKU-1</cProd><cEAN>SEM GTIN</cEAN><xProd>Item</xProd><qCom>1.5</qCom></prod></det>
          </infNFe></NFe></nfeProc>
        """;
    assertThrows(EnterpriseDatabase.EnterpriseException.class,
        () -> NfeXmlParser.parse(xml.getBytes(StandardCharsets.UTF_8)));
  }
}
