package br.com.mncheck;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Secure, deterministic reader for the fields used from a Brazilian NF-e XML. */
public final class NfeXmlParser {
  public record Item(String sku, String ean, String description, int quantity) {}
  public record Nfe(String accessKey, String issuer, String issuerTaxId,
                    String recipient, String recipientTaxId, Instant issuedAt,
                    String hash, List<Item> items) {}

  private NfeXmlParser() {}

  public static Nfe parse(byte[] content) {
    if (content == null || content.length == 0) {
      throw new EnterpriseDatabase.EnterpriseException(400, "O XML da NF-e está vazio.");
    }
    if (content.length > 8 * 1024 * 1024) {
      throw new EnterpriseDatabase.EnterpriseException(413, "O XML excede o limite de 8 MB.");
    }
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
      Element info = first(document, "infNFe");
      String accessKey = info.getAttribute("Id").replaceFirst("^NFe", "").replaceAll("\\D", "");
      if (accessKey.length() != 44) {
        accessKey = text(document, "chNFe").replaceAll("\\D", "");
      }
      if (accessKey.length() != 44) {
        throw new EnterpriseDatabase.EnterpriseException(400, "Não foi possível identificar a chave de 44 dígitos da NF-e.");
      }
      String issued = value(document.getDocumentElement(), "dhEmi",
          value(document.getDocumentElement(), "dEmi", ""));
      Instant issuedAt = null;
      try {
        if (!issued.isBlank()) issuedAt = issued.length() == 10
            ? Instant.parse(issued + "T00:00:00Z")
            : Instant.parse(issued);
      } catch (Exception ignored) {
        // The access key and items remain useful even when an old issuer sends a non-ISO timestamp.
      }
      NodeList details = document.getElementsByTagNameNS("*", "det");
      List<Item> items = new ArrayList<>();
      for (int index = 0; index < details.getLength(); index++) {
        Element detail = (Element) details.item(index);
        Element product = first(detail, "prod");
        String sku = value(product, "cProd", "").trim();
        String ean = value(product, "cEAN", "").replaceAll("[^0-9]", "");
        if (ean.isBlank()) ean = value(product, "cEANTrib", "").replaceAll("[^0-9]", "");
        String description = value(product, "xProd", "").trim();
        double rawQuantity = Double.parseDouble(value(product, "qCom", "0").replace(',', '.'));
        int quantity = (int) Math.round(rawQuantity);
        if (sku.isBlank() || quantity <= 0 || Math.abs(rawQuantity - quantity) > 0.00001) {
          throw new EnterpriseDatabase.EnterpriseException(400,
              "A NF-e possui item sem código ou com quantidade não inteira, incompatível com o controle por unidade.");
        }
        items.add(new Item(sku, ean, description, quantity));
      }
      if (items.isEmpty()) {
        throw new EnterpriseDatabase.EnterpriseException(400, "A NF-e não contém itens de produto.");
      }
      String issuer = partyName(document, "emit");
      String issuerTaxId = partyTaxId(document, "emit");
      String recipient = partyName(document, "dest");
      String recipientTaxId = partyTaxId(document, "dest");
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
      return new Nfe(accessKey, issuer, issuerTaxId, recipient, recipientTaxId,
          issuedAt, hash, List.copyOf(items));
    } catch (EnterpriseDatabase.EnterpriseException error) {
      throw error;
    } catch (Exception error) {
      throw new EnterpriseDatabase.EnterpriseException(400, "XML de NF-e inválido ou não suportado.", error);
    }
  }

  private static String partyName(Document document, String partyTag) {
    NodeList parties = document.getElementsByTagNameNS("*", partyTag);
    if (parties.getLength() == 0) return "";
    return value((Element) parties.item(0), "xNome", "");
  }

  private static String partyTaxId(Document document, String partyTag) {
    NodeList parties = document.getElementsByTagNameNS("*", partyTag);
    if (parties.getLength() == 0) return "";
    Element party = (Element) parties.item(0);
    String value = value(party, "CNPJ", value(party, "CPF", ""));
    return value.replaceAll("\\D", "");
  }

  private static Element first(Document document, String name) {
    NodeList nodes = document.getElementsByTagNameNS("*", name);
    if (nodes.getLength() == 0) throw new EnterpriseDatabase.EnterpriseException(400, "XML não é uma NF-e válida.");
    return (Element) nodes.item(0);
  }

  private static Element first(Element element, String name) {
    NodeList nodes = element.getElementsByTagNameNS("*", name);
    if (nodes.getLength() == 0) throw new EnterpriseDatabase.EnterpriseException(400, "Estrutura de item da NF-e inválida.");
    return (Element) nodes.item(0);
  }

  private static String text(Document document, String name) {
    return value(document.getDocumentElement(), name, "");
  }

  private static String value(Element element, String name, String fallback) {
    NodeList nodes = element.getElementsByTagNameNS("*", name);
    return nodes.getLength() == 0 ? fallback : nodes.item(0).getTextContent().trim();
  }

  public static byte[] decode(String data) {
    String value = data == null ? "" : data.trim();
    int comma = value.indexOf(',');
    if (value.startsWith("data:") && comma >= 0) value = value.substring(comma + 1);
    try {
      return java.util.Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException error) {
      return value.getBytes(StandardCharsets.UTF_8);
    }
  }
}
