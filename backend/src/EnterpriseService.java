package br.com.mncheck;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static br.com.mncheck.EnterpriseDatabase.EnterpriseException;

/** Business rules for receipts, inventory, picking, shipping and transfers. */
public final class EnterpriseService {
  private static final UUID MAIN_BRANCH = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final ObjectMapper JSON = new ObjectMapper();
  private final EnterpriseDatabase database;

  public EnterpriseService(EnterpriseDatabase database) {
    this.database = database;
  }

  public Map<String, Object> workspace(MmCheckServer.SessionPrincipal principal, String requestedBranch) {
    UUID branch = branchFor(principal, requestedBranch);
    boolean global = principal.isAdmin() && "all".equalsIgnoreCase(text(requestedBranch));
    String branchFilter = global ? "" : " WHERE f.id = CAST(? AS UUID) ";
    Object[] branchParams = global ? new Object[0] : new Object[]{branch};

    List<Map<String, Object>> branches = database.query("""
        SELECT id, codigo, nome, cnpj, ativa, criado_em
        FROM filiais ORDER BY ativa DESC, nome
        """);
    List<Map<String, Object>> products = database.query("""
        SELECT p.id, p.sku, p.codigo_interno, p.descricao, p.ativo,
               COALESCE(string_agg(e.ean, ',' ORDER BY e.principal DESC, e.ean), '') AS eans
        FROM produtos p LEFT JOIN produtos_eans e ON e.produto_id = p.id
        GROUP BY p.id ORDER BY p.ativo DESC, p.descricao
        """);
    List<Map<String, Object>> balances = database.query("""
        SELECT s.filial_id, f.codigo AS filial_codigo, f.nome AS filial_nome,
               p.id AS produto_id, p.sku, p.codigo_interno, p.descricao,
               s.fisico, s.disponivel, s.reservado, s.em_transito, s.quarentena,
               s.atualizado_em
        FROM saldos_estoque s
        JOIN filiais f ON f.id = s.filial_id
        JOIN produtos p ON p.id = s.produto_id
        """ + branchFilter + " ORDER BY p.descricao", branchParams);
    List<Map<String, Object>> receipts = database.query("""
        SELECT r.id, r.filial_id, f.nome AS filial, r.referencia, r.fornecedor, r.status,
               r.criado_por, r.criado_em, r.atualizado_em, r.finalizado_em,
               COALESCE(d.documentos, 0) AS documentos,
               COALESCE(i.esperado, 0) AS esperado,
               COALESCE(i.recebido, 0) AS recebido,
               COALESCE(i.quarentena, 0) AS quarentena
        FROM recebimentos r JOIN filiais f ON f.id = r.filial_id
        LEFT JOIN LATERAL (
          SELECT COUNT(*) AS documentos FROM recebimentos_documentos WHERE recebimento_id = r.id
        ) d ON TRUE
        LEFT JOIN LATERAL (
          SELECT SUM(quantidade_esperada) AS esperado,
                 SUM(quantidade_recebida + quantidade_avariada) AS recebido,
                 SUM(quantidade_quarentena + quantidade_avariada) AS quarentena
          FROM recebimentos_itens WHERE recebimento_id = r.id
        ) i ON TRUE
        """ + (global ? "" : " WHERE r.filial_id = CAST(? AS UUID) ") + """
        ORDER BY r.criado_em DESC LIMIT 100
        """, branchParams);
    List<Map<String, Object>> maps = database.query("""
        SELECT m.id, m.filial_id, f.nome AS filial, m.numero_mapa, m.pedidos, m.cliente,
               m.rota, m.arquivo_nome, m.status, m.criado_por, m.revisado_por,
               m.criado_em, m.publicado_em, m.finalizado_em,
               COALESCE(SUM(i.quantidade_esperada - i.quantidade_cancelada), 0) AS esperado,
               COALESCE(SUM(i.quantidade_esperada), 0) AS esperado_original,
               COALESCE(SUM(i.quantidade_separada), 0) AS separado,
               COALESCE(SUM(i.quantidade_conferida), 0) AS conferido
        FROM mapas_separacao m JOIN filiais f ON f.id = m.filial_id
        LEFT JOIN mapas_separacao_itens i ON i.mapa_id = m.id
        """ + (global ? "" : " WHERE m.filial_id = CAST(? AS UUID) ") + """
        GROUP BY m.id, f.nome ORDER BY m.criado_em DESC LIMIT 100
        """, branchParams);
    List<Map<String, Object>> transfers = database.query("""
        SELECT t.id, t.referencia, t.filial_origem_id, origem.nome AS filial_origem,
               t.filial_destino_id, destino.nome AS filial_destino, t.status, t.veiculo,
               t.rota, t.criado_por, t.aprovado_por, t.criado_em, t.expedido_em, t.recebido_em,
               COALESCE(SUM(i.quantidade), 0) AS quantidade,
               COALESCE(SUM(i.quantidade_recebida), 0) AS quantidade_recebida
        FROM transferencias t
        JOIN filiais origem ON origem.id = t.filial_origem_id
        JOIN filiais destino ON destino.id = t.filial_destino_id
        LEFT JOIN transferencias_itens i ON i.transferencia_id = t.id
        """ + (global ? "" : " WHERE t.filial_origem_id = CAST(? AS UUID) OR t.filial_destino_id = CAST(? AS UUID) ") + """
        GROUP BY t.id, origem.nome, destino.nome ORDER BY t.criado_em DESC LIMIT 100
        """, global ? new Object[0] : new Object[]{branch, branch});
    List<Map<String, Object>> exceptions = database.query("""
        SELECT d.id, d.filial_id, f.nome AS filial, d.operacao_tipo, d.operacao_id,
               d.item_id, d.tipo, d.quantidade, d.descricao, d.status, d.resolucao,
               d.justificativa, d.criado_por, d.resolvido_por, d.criado_em, d.resolvido_em
        FROM divergencias_operacionais d JOIN filiais f ON f.id = d.filial_id
        """ + (global ? "" : " WHERE d.filial_id = CAST(? AS UUID) ") + """
        ORDER BY CASE WHEN d.status = 'ABERTA' THEN 0 ELSE 1 END, d.criado_em DESC LIMIT 200
        """, branchParams);
    List<Map<String, Object>> movements = database.query("""
        SELECT m.id, m.filial_id, f.nome AS filial, p.sku, p.codigo_interno, p.descricao,
               m.tipo, m.delta_fisico, m.delta_disponivel, m.delta_reservado,
               m.delta_transito, m.delta_quarentena, m.origem_tipo, m.origem_id,
               m.estorno_de, m.motivo, m.criado_por, m.dispositivo, m.ocorrido_em
        FROM movimentos_estoque m JOIN filiais f ON f.id = m.filial_id
        JOIN produtos p ON p.id = m.produto_id
        """ + (global ? "" : " WHERE m.filial_id = CAST(? AS UUID) ") + """
        ORDER BY m.ocorrido_em DESC LIMIT 250
        """, branchParams);
    List<Map<String, Object>> audits = database.query("""
        SELECT a.id, a.filial_id, f.nome AS filial, a.usuario_id, a.acao,
               a.entidade_tipo, a.entidade_id, a.detalhes::text AS detalhes, a.criado_em
        FROM auditoria_empresarial a LEFT JOIN filiais f ON f.id = a.filial_id
        """ + (global ? "" : " WHERE a.filial_id = CAST(? AS UUID) ") + """
        ORDER BY a.criado_em DESC LIMIT 250
        """, branchParams);
    List<Map<String, Object>> profile = database.query("""
        SELECT usuario_id, filial_id FROM perfis_empresariais WHERE usuario_id = ?
        """, principal.id());
    List<Map<String, Object>> profiles = principal.isAdmin() ? database.query("""
        SELECT p.usuario_id, p.filial_id, f.nome AS filial_nome
        FROM perfis_empresariais p LEFT JOIN filiais f ON f.id = p.filial_id ORDER BY p.usuario_id
        """) : profile;
    List<Map<String, Object>> printers = database.query("""
        SELECT i.id, i.filial_id, f.nome AS filial_nome, i.nome, i.fabricante,
               i.largura_mm, i.altura_mm, i.dpi, i.ativa
        FROM impressoras i JOIN filiais f ON f.id = i.filial_id
        """ + (global ? "" : " WHERE i.filial_id = CAST(? AS UUID) ") + """
        ORDER BY i.ativa DESC, i.nome
        """, branchParams);
    List<Map<String, Object>> parameters = database.query("""
        SELECT filial_id, chave, valor::text AS valor, atualizado_por, atualizado_em
        FROM parametros_operacionais
        """ + (global ? "" : " WHERE filial_id = CAST(? AS UUID) ") + """
        ORDER BY chave
        """, branchParams);

    Map<String, Object> dashboard = dashboard(branch, global);
    return Map.ofEntries(
        Map.entry("branchId", global ? "all" : branch.toString()),
        Map.entry("branches", branches), Map.entry("products", products),
        Map.entry("balances", balances), Map.entry("receipts", receipts),
        Map.entry("maps", maps), Map.entry("transfers", transfers),
        Map.entry("exceptions", exceptions), Map.entry("movements", movements),
        Map.entry("audits", audits),
        Map.entry("profile", profile.isEmpty() ? Map.of() : profile.get(0)),
        Map.entry("profiles", profiles),
        Map.entry("printers", printers), Map.entry("parameters", parameters),
        Map.entry("dashboard", dashboard)
    );
  }

  public Map<String, Object> search(MmCheckServer.SessionPrincipal principal, String requestedBranch, String query) {
    String term = text(query);
    if (term.length() < 2) throw new EnterpriseException(400, "Digite ao menos dois caracteres para buscar.");
    UUID branch = branchFor(principal, requestedBranch);
    boolean global = principal.isAdmin() && "all".equalsIgnoreCase(text(requestedBranch));
    String pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    List<Map<String, Object>> results = new ArrayList<>();
    results.addAll(database.query("""
        SELECT 'PRODUTO' AS tipo, p.id::text AS id, p.codigo_interno AS titulo,
               p.sku || ' · ' || p.descricao AS subtitulo
        FROM produtos p LEFT JOIN produtos_eans e ON e.produto_id = p.id
        WHERE p.sku ILIKE ? ESCAPE '!' OR p.codigo_interno ILIKE ? ESCAPE '!'
           OR p.descricao ILIKE ? ESCAPE '!' OR e.ean ILIKE ? ESCAPE '!'
        GROUP BY p.id ORDER BY p.descricao LIMIT 10
        """, pattern, pattern, pattern, pattern));
    String filter = global ? "" : " AND filial_id = CAST(? AS UUID) ";
    Object[] documentParams = global ? new Object[]{pattern} : new Object[]{pattern, branch};
    results.addAll(database.query("""
        SELECT 'RECEBIMENTO' AS tipo, id::text AS id, referencia AS titulo,
               fornecedor || ' · ' || status AS subtitulo FROM recebimentos
        WHERE referencia ILIKE ? ESCAPE '!'
        """ + filter + " ORDER BY criado_em DESC LIMIT 8", documentParams));
    results.addAll(database.query("""
        SELECT 'MAPA' AS tipo, id::text AS id, numero_mapa AS titulo,
               cliente || ' · ' || status AS subtitulo FROM mapas_separacao
        WHERE (numero_mapa ILIKE ? ESCAPE '!' OR pedidos ILIKE ? ESCAPE '!')
        """ + (global ? "" : " AND filial_id = CAST(? AS UUID) ") + " ORDER BY criado_em DESC LIMIT 8",
        global ? new Object[]{pattern, pattern} : new Object[]{pattern, pattern, branch}));
    results.addAll(database.query("""
        SELECT 'TRANSFERENCIA' AS tipo, id::text AS id, referencia AS titulo,
               status AS subtitulo FROM transferencias
        WHERE referencia ILIKE ? ESCAPE '!'
        """ + (global ? "" : " AND (filial_origem_id = CAST(? AS UUID) OR filial_destino_id = CAST(? AS UUID)) ")
        + " ORDER BY criado_em DESC LIMIT 8",
        global ? new Object[]{pattern} : new Object[]{pattern, branch, branch}));
    return Map.of("query", term, "results", results);
  }

  public Map<String, Object> createBranch(MmCheckServer.SessionPrincipal principal, Map<String, Object> body,
                                          String idempotency) {
    requireAdmin(principal);
    UUID id = UUID.randomUUID();
    String code = required(body, "code").toUpperCase(Locale.ROOT);
    String name = required(body, "name");
    String taxId = text(body.get("taxId"));
    return database.transaction(connection -> {
      Claim claim = claim(connection, principal, idempotency, "CRIAR_FILIAL", "FILIAL", id.toString());
      UUID claimedId = uuid(claim.entityId(), "filial");
      if (!claim.fresh()) {
        return EnterpriseDatabase.one(connection,
            "SELECT id, codigo AS code, nome AS name, cnpj AS taxId FROM filiais WHERE id = ?", claimedId)
            .orElseThrow(() -> new EnterpriseException(409, "A operação idempotente referencia uma filial inexistente."));
      }
      EnterpriseDatabase.update(connection, """
          INSERT INTO filiais (id, codigo, nome, cnpj) VALUES (?, ?, ?, ?)
          """, id, code, name, taxId);
      audit(connection, id, principal, "FILIAL_CRIADA", "FILIAL", id.toString(), Map.of("codigo", code));
      return Map.of("id", id, "code", code, "name", name);
    });
  }

  public Map<String, Object> assignBranch(MmCheckServer.SessionPrincipal principal, Map<String, Object> body,
                                          String idempotency) {
    requireAdmin(principal);
    String userId = required(body, "userId");
    UUID branchId = uuid(required(body, "branchId"), "filial");
    return database.transaction(connection -> {
      Claim claim = claim(connection, principal, idempotency, "VINCULAR_USUARIO_FILIAL", "USUARIO", userId);
      if (!claim.fresh()) {
        Map<String, Object> existing = EnterpriseDatabase.one(connection,
            "SELECT usuario_id, filial_id FROM perfis_empresariais WHERE usuario_id = ?", userId)
            .orElseThrow(() -> new EnterpriseException(409, "O vínculo idempotente não foi localizado."));
        return Map.of("userId", existing.get("usuario_id"), "branchId", existing.get("filial_id"));
      }
      requireBranch(connection, branchId);
      EnterpriseDatabase.update(connection, """
          INSERT INTO perfis_empresariais (usuario_id, filial_id) VALUES (?, ?)
          ON CONFLICT (usuario_id) DO UPDATE SET filial_id = EXCLUDED.filial_id, atualizado_em = now()
          """, userId, branchId);
      audit(connection, branchId, principal, "USUARIO_VINCULADO", "USUARIO", userId, Map.of());
      return Map.of("userId", userId, "branchId", branchId);
    });
  }

  public Map<String, Object> saveProduct(MmCheckServer.SessionPrincipal principal, Map<String, Object> body,
                                         String idempotency) {
    requireAnyRole(principal, "admin", "supervisor", "stock");
    UUID id = text(body.get("id")).isBlank() ? UUID.randomUUID() : uuid(text(body.get("id")), "produto");
    String sku = required(body, "sku");
    String internalCode = required(body, "internalCode");
    String description = required(body, "description");
    List<String> eans = stringList(body.get("eans")).stream()
        .map(value -> value.replaceAll("\\D", "")).filter(value -> !value.isBlank()).distinct().toList();
    if (eans.stream().anyMatch(value -> value.length() < 8 || value.length() > 14)) {
      throw new EnterpriseException(400, "Cada EAN deve possuir entre 8 e 14 dígitos.");
    }
    return database.transaction(connection -> {
      Claim claim = claim(connection, principal, idempotency, "SALVAR_PRODUTO", "PRODUTO", id.toString());
      UUID claimedId = uuid(claim.entityId(), "produto");
      if (!claim.fresh()) return productResponse(connection, claimedId);
      Optional<Map<String, Object>> existing = EnterpriseDatabase.one(connection,
          "SELECT id FROM produtos WHERE id = ?", id);
      if (existing.isPresent()) {
        EnterpriseDatabase.update(connection, """
            UPDATE produtos SET sku = ?, codigo_interno = ?, descricao = ?, ativo = ?, atualizado_em = now()
            WHERE id = ?
            """, sku, internalCode, description, bool(body.get("active"), true), id);
        EnterpriseDatabase.update(connection, "DELETE FROM produtos_eans WHERE produto_id = ?", id);
      } else {
        EnterpriseDatabase.update(connection, """
            INSERT INTO produtos (id, sku, codigo_interno, descricao) VALUES (?, ?, ?, ?)
            """, id, sku, internalCode, description);
      }
      for (int index = 0; index < eans.size(); index++) {
        EnterpriseDatabase.update(connection,
            "INSERT INTO produtos_eans (ean, produto_id, principal) VALUES (?, ?, ?)",
            eans.get(index), id, index == 0);
      }
      EnterpriseDatabase.update(connection, """
          UPDATE recebimentos_itens i SET produto_id = ?, codigo_interno = ?,
            status = CASE WHEN status = 'PRODUTO_NAO_CADASTRADO' THEN 'PENDENTE' ELSE status END,
            atualizado_em = now()
          WHERE i.produto_id IS NULL AND (i.sku_informado = ?
            OR EXISTS (SELECT 1 FROM produtos_eans e WHERE e.produto_id = ? AND e.ean = i.ean))
          """, id, internalCode, sku, id);
      audit(connection, branchFor(principal, ""), principal, "PRODUTO_SALVO", "PRODUTO", id.toString(),
          Map.of("sku", sku, "codigoInterno", internalCode));
      return Map.of("id", id, "sku", sku, "internalCode", internalCode, "description", description, "eans", eans);
    });
  }

  public Map<String, Object> createReceipt(MmCheckServer.SessionPrincipal principal, Map<String, Object> body,
                                           String idempotency) {
    requireAnyRole(principal, "admin", "supervisor", "receiving");
    UUID branch = branchFor(principal, text(body.get("branchId")));
    UUID id = UUID.randomUUID();
    String reference = required(body, "reference");
    String supplier = text(body.get("supplier"));
    return database.transaction(connection -> {
      Claim claim = claim(connection, principal, idempotency, "CRIAR_RECEBIMENTO", "RECEBIMENTO", id.toString());
      if (!claim.fresh()) return receiptDetails(connection, uuid(claim.entityId(), "recebimento"));
      requireBranch(connection, branch);
      EnterpriseDatabase.update(connection, """
          INSERT INTO recebimentos (id, filial_id, referencia, fornecedor, status, criado_por)
          VALUES (?, ?, ?, ?, 'RASCUNHO', ?)
          """, id, branch, reference, supplier, principal.name());
      audit(connection, branch, principal, "RECEBIMENTO_CRIADO", "RECEBIMENTO", id.toString(), Map.of("referencia", reference));
      return receiptDetails(connection, id);
    });
  }

  public Map<String, Object> importNfe(MmCheckServer.SessionPrincipal principal, UUID receiptId,
                                       Map<String, Object> body, String idempotency) {
    requireAnyRole(principal, "admin", "supervisor", "receiving");
    byte[] content = NfeXmlParser.decode(required(body, "content"));
    NfeXmlParser.Nfe nfe = NfeXmlParser.parse(content);
    String filename = text(body.get("fileName"));
    return database.transaction(connection -> {
      Map<String, Object> receipt = lockReceipt(connection, receiptId);
      UUID branch = (UUID) receipt.get("filial_id");
      requireBranchAccess(connection, principal, branch);
      if (!claim(connection, principal, idempotency, "IMPORTAR_NFE", "RECEBIMENTO", receiptId.toString()).fresh()) {
        return receiptDetails(connection, receiptId);
      }
      requireStatus(receipt, List.of("RASCUNHO", "EM_RECEBIMENTO"), "recebimento");
      Map<String, Object> branchRow = EnterpriseDatabase.one(connection,
          "SELECT cnpj FROM filiais WHERE id = ?", branch).orElseThrow();
      String expectedRecipient = text(branchRow.get("cnpj")).replaceAll("\\D", "");
      if (!expectedRecipient.isBlank() && !nfe.recipientTaxId().isBlank()
          && !expectedRecipient.equals(nfe.recipientTaxId())) {
        throw new EnterpriseException(409, "O destinatário da NF-e não corresponde à filial deste recebimento.");
      }
      UUID documentId = UUID.randomUUID();
      EnterpriseDatabase.update(connection, """
          INSERT INTO recebimentos_documentos
            (id, recebimento_id, chave_nfe, hash_xml, nome_arquivo, emitente, emitente_cnpj,
             destinatario, destinatario_cnpj, emitida_em)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, documentId, receiptId, nfe.accessKey(), nfe.hash(), filename, nfe.issuer(),
          nfe.issuerTaxId(), nfe.recipient(), nfe.recipientTaxId(), nfe.issuedAt());
      for (NfeXmlParser.Item item : nfe.items()) {
        Optional<Map<String, Object>> product = findProduct(connection, item.sku(), item.ean());
        UUID itemId = UUID.randomUUID();
        EnterpriseDatabase.update(connection, """
            INSERT INTO recebimentos_itens
              (id, recebimento_id, documento_id, produto_id, sku_informado, codigo_interno,
               ean, descricao, quantidade_esperada, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, itemId, receiptId, documentId,
            product.map(row -> row.get("id")).orElse(null), item.sku(),
            product.map(row -> text(row.get("codigo_interno"))).orElse(""), item.ean(), item.description(),
            item.quantity(), product.isPresent() ? "PENDENTE" : "PRODUTO_NAO_CADASTRADO");
        if (product.isEmpty()) {
          createException(connection, branch, "RECEBIMENTO", receiptId, itemId, "PRODUTO_DESCONHECIDO",
              item.quantity(), "SKU/EAN da NF-e não está cadastrado.", principal);
        }
      }
      EnterpriseDatabase.update(connection, """
          UPDATE recebimentos SET fornecedor = CASE WHEN fornecedor = '' THEN ? ELSE fornecedor END,
          status = 'EM_RECEBIMENTO', atualizado_em = now() WHERE id = ?
          """, nfe.issuer(), receiptId);
      audit(connection, branch, principal, "NFE_IMPORTADA", "RECEBIMENTO", receiptId.toString(),
          Map.of("chave", nfe.accessKey(), "hash", nfe.hash(), "itens", nfe.items().size()));
      return receiptDetails(connection, receiptId);
    });
  }

  public Map<String, Object> receiptDetails(MmCheckServer.SessionPrincipal principal, UUID receiptId) {
    return database.transaction(connection -> {
      Map<String, Object> receipt = receiptDetails(connection, receiptId);
      requireBranchAccess(connection, principal, (UUID) receipt.get("filial_id"));
      return receipt;
    });
  }

  public Map<String, Object> scanReceipt(MmCheckServer.SessionPrincipal principal, UUID receiptId,
                                         Map<String, Object> body, String idempotency) {
    requireAnyRole(principal, "admin", "supervisor", "receiving");
    String code = required(body, "code").trim();
    int quantity = positiveInt(body.get("quantity"), "quantidade");
    String device = text(body.get("deviceId"));
    return database.transaction(connection -> {
      Map<String, Object> receipt = lockReceipt(connection, receiptId);
      UUID branch = (UUID) receipt.get("filial_id");
      requireBranchAccess(connection, principal, branch);
      requireStatus(receipt, List.of("EM_RECEBIMENTO"), "recebimento");
      if (!claim(connection, principal, idempotency, "LER_RECEBIMENTO", "RECEBIMENTO", receiptId.toString()).fresh()) {
        return receiptDetails(connection, receiptId);
      }

      List<Map<String, Object>> candidates = EnterpriseDatabase.query(connection, """
          SELECT i.*, p.sku, p.codigo_interno AS produto_codigo
          FROM recebimentos_itens i LEFT JOIN produtos p ON p.id = i.produto_id
          WHERE i.recebimento_id = ?
            AND (i.ean = ? OR i.sku_informado = ? OR p.sku = ? OR p.codigo_interno = ?
              OR EXISTS (SELECT 1 FROM produtos_eans e WHERE e.produto_id = p.id AND e.ean = ?))
          ORDER BY CASE WHEN i.quantidade_recebida < i.quantidade_esperada THEN 0 ELSE 1 END, i.id
          FOR UPDATE OF i
          """, receiptId, code, code, code, code, code);
      if (candidates.isEmpty()) {
        createException(connection, branch, "RECEBIMENTO", receiptId, null, "PRODUTO_INCORRETO", quantity,
            "Código " + code + " não pertence aos XMLs desta carga.", principal);
        audit(connection, branch, principal, "LEITURA_RECUSADA", "RECEBIMENTO", receiptId.toString(),
            Map.of("codigo", code, "quantidade", quantity, "idempotencia", idempotency));
        Map<String, Object> rejected = new LinkedHashMap<>(receiptDetails(connection, receiptId));
        rejected.put("rejected", true);
        rejected.put("error", "Produto não pertence aos XMLs desta carga. A ocorrência foi registrada.");
        return rejected;
      }
      int remaining = quantity;
      int regular = 0;
      for (Map<String, Object> candidate : candidates) {
        int capacity = Math.max(0, integer(candidate.get("quantidade_esperada"))
            - integer(candidate.get("quantidade_recebida")) - integer(candidate.get("quantidade_avariada")));
        int allocated = Math.min(remaining, capacity);
        if (allocated <= 0) continue;
        EnterpriseDatabase.update(connection, """
            UPDATE recebimentos_itens SET quantidade_recebida = quantidade_recebida + ?,
              status = CASE WHEN quantidade_avariada > 0 THEN 'DIVERGENTE'
                WHEN quantidade_recebida + ? >= quantidade_esperada
                THEN 'CONFIRMADO' ELSE 'PARCIAL' END, atualizado_em = now()
            WHERE id = ?
            """, allocated, allocated, candidate.get("id"));
        regular += allocated;
        remaining -= allocated;
        if (remaining == 0) break;
      }
      int excess = remaining;
      UUID itemId = (UUID) candidates.get(0).get("id");
      if (excess > 0) {
        EnterpriseDatabase.update(connection, """
            UPDATE recebimentos_itens SET quantidade_quarentena = quantidade_quarentena + ?,
              status = 'DIVERGENTE', atualizado_em = now() WHERE id = ?
            """, excess, itemId);
        createException(connection, branch, "RECEBIMENTO", receiptId, itemId, "EXCESSO", excess,
            "Quantidade recebida acima da NF-e.", principal);
      }
      audit(connection, branch, principal, "ITEM_RECEBIDO", "RECEBIMENTO", receiptId.toString(),
          Map.of("itemId", itemId, "codigo", code, "regular", regular, "quarentena", excess,
              "idempotencia", idempotency, "dispositivo", device));
      Map<String, Object> result = new LinkedHashMap<>(receiptDetails(connection, receiptId));
      if (excess > 0) result.put("warning", "Excesso encaminhado para quarentena.");
      return result;
    });
  }

  public Map<String, Object> finalizeReceipt(MmCheckServer.SessionPrincipal principal, UUID receiptId,
                                             String idempotency) {
    requireAnyRole(principal, "admin", "supervisor", "receiving");
    return database.transaction(connection -> {
      Map<String, Object> receipt = lockReceipt(connection, receiptId);
      UUID branch = (UUID) receipt.get("filial_id");
      requireBranchAccess(connection, principal, branch);
      if (!claim(connection, principal, idempotency, "FINALIZAR_RECEBIMENTO", "RECEBIMENTO", receiptId.toString()).fresh()) {
        return receiptDetails(connection, receiptId);
      }
      if (List.of("FINALIZADO", "FINALIZADO_DIVERGENCIA").contains(text(receipt.get("status")))) {
        return receiptDetails(connection, receiptId);
      }
      requireStatus(receipt, List.of("EM_RECEBIMENTO"), "recebimento");
      List<Map<String, Object>> items = EnterpriseDatabase.query(connection,
          "SELECT * FROM recebimentos_itens WHERE recebimento_id = ? ORDER BY id FOR UPDATE", receiptId);
      if (items.isEmpty()) throw new EnterpriseException(409, "Importe ao menos uma NF-e antes de finalizar.");
      boolean divergent = false;
      for (Map<String, Object> item : items) {
        UUID product = (UUID) item.get("produto_id");
        int expected = integer(item.get("quantidade_esperada"));
        int received = integer(item.get("quantidade_recebida"));
        int damaged = integer(item.get("quantidade_avariada"));
        int quarantined = integer(item.get("quantidade_quarentena"));
        if (product == null) {
          divergent = true;
          continue;
        }
        if (received + damaged < expected) {
          divergent = true;
          createExceptionIfAbsent(connection, branch, "RECEBIMENTO", receiptId, (UUID) item.get("id"),
              "FALTA", expected - received - damaged, "Quantidade não recebida até a finalização.", principal);
        }
        if (quarantined + damaged > 0) divergent = true;
        if (received > 0) {
          applyMovement(connection, new Movement(branch, product, "RECEBIMENTO", received, received, 0, 0, 0,
              "RECEBIMENTO", receiptId.toString(), idempotency + ":item:" + item.get("id") + ":regular",
              null, "Entrada confirmada por NF-e", principal.name(), "", null, Map.of()));
        }
        int quarantineTotal = quarantined + damaged;
        if (quarantineTotal > 0) {
          applyMovement(connection, new Movement(branch, product, "QUARENTENA_ENTRADA", quarantineTotal, 0, 0, 0, quarantineTotal,
              "RECEBIMENTO", receiptId.toString(), idempotency + ":item:" + item.get("id") + ":quarentena",
              null, "Exceção aguardando decisão", principal.name(), "", null, Map.of()));
        }
      }
      long open = count(connection, """
          SELECT COUNT(*) FROM divergencias_operacionais
          WHERE operacao_tipo = 'RECEBIMENTO' AND operacao_id = ? AND status = 'ABERTA'
          """, receiptId);
      divergent = divergent || open > 0;
      EnterpriseDatabase.update(connection, """
          UPDATE recebimentos SET status = ?, atualizado_em = now(), finalizado_em = now() WHERE id = ?
          """, divergent ? "FINALIZADO_DIVERGENCIA" : "FINALIZADO", receiptId);
      audit(connection, branch, principal, "RECEBIMENTO_FINALIZADO", "RECEBIMENTO", receiptId.toString(),
          Map.of("comDivergencia", divergent));
      return receiptDetails(connection, receiptId);
    });
  }

  public Map<String, Object> registerDamage(MmCheckServer.SessionPrincipal principal, UUID receiptId,
                                            Map<String, Object> body, String idempotency) {
    requireAnyRole(principal, "admin", "supervisor", "receiving");
    String code = required(body, "code");
    int quantity = positiveInt(body.get("quantity"), "quantidade avariada");
    String reason = required(body, "reason");
    return database.transaction(connection -> {
      Map<String, Object> receipt = lockReceipt(connection, receiptId);
      UUID branch = (UUID) receipt.get("filial_id");
      requireBranchAccess(connection, principal, branch);
      requireStatus(receipt, List.of("EM_RECEBIMENTO"), "recebimento");
      if (!claim(connection, principal, idempotency, "REGISTRAR_AVARIA", "RECEBIMENTO", receiptId.toString()).fresh()) {
        return receiptDetails(connection, receiptId);
      }
      List<Map<String, Object>> candidates = EnterpriseDatabase.query(connection, """
          SELECT i.*, p.sku, p.codigo_interno AS produto_codigo
          FROM recebimentos_itens i LEFT JOIN produtos p ON p.id = i.produto_id
          WHERE i.recebimento_id = ? AND (i.ean = ? OR i.sku_informado = ? OR p.sku = ?
            OR p.codigo_interno = ? OR EXISTS (
              SELECT 1 FROM produtos_eans e WHERE e.produto_id = p.id AND e.ean = ?))
          ORDER BY i.id FOR UPDATE OF i
          """, receiptId, code, code, code, code, code);
      if (candidates.isEmpty()) {
        createException(connection, branch, "RECEBIMENTO", receiptId, null, "PRODUTO_INCORRETO", quantity,
            "Produto avariado não pertence aos XMLs da carga: " + code, principal);
        audit(connection, branch, principal, "AVARIA_RECUSADA", "RECEBIMENTO", receiptId.toString(),
            Map.of("codigo", code, "quantidade", quantity, "motivo", reason, "idempotencia", idempotency));
        Map<String, Object> rejected = new LinkedHashMap<>(receiptDetails(connection, receiptId));
        rejected.put("rejected", true);
        rejected.put("error", "Produto não pertence à carga. A ocorrência foi registrada.");
        return rejected;
      }
      int capacity = candidates.stream().mapToInt(item -> Math.max(0,
          integer(item.get("quantidade_esperada")) - integer(item.get("quantidade_recebida"))
              - integer(item.get("quantidade_avariada")))).sum();
      UUID itemId = (UUID) candidates.get(0).get("id");
      if (quantity > capacity) {
        createException(connection, branch, "RECEBIMENTO", receiptId, itemId, "EXCESSO_AVARIA",
            quantity - capacity, "A quantidade avariada excede o saldo pendente da NF-e.", principal);
        audit(connection, branch, principal, "AVARIA_RECUSADA", "RECEBIMENTO", receiptId.toString(),
            Map.of("codigo", code, "quantidade", quantity, "motivo", reason, "idempotencia", idempotency));
        Map<String, Object> rejected = new LinkedHashMap<>(receiptDetails(connection, receiptId));
        rejected.put("rejected", true);
        rejected.put("error", "A quantidade avariada excede o pendente da NF-e.");
        return rejected;
      }
      int remaining = quantity;
      for (Map<String, Object> candidate : candidates) {
        int open = Math.max(0, integer(candidate.get("quantidade_esperada"))
            - integer(candidate.get("quantidade_recebida")) - integer(candidate.get("quantidade_avariada")));
        int allocated = Math.min(open, remaining);
        if (allocated <= 0) continue;
        EnterpriseDatabase.update(connection, """
            UPDATE recebimentos_itens SET quantidade_avariada = quantidade_avariada + ?,
              status = 'DIVERGENTE', atualizado_em = now() WHERE id = ?
            """, allocated, candidate.get("id"));
        remaining -= allocated;
        if (remaining == 0) break;
      }
      createException(connection, branch, "RECEBIMENTO", receiptId, itemId, "AVARIA", quantity, reason, principal);
      audit(connection, branch, principal, "AVARIA_REGISTRADA", "RECEBIMENTO", receiptId.toString(),
          Map.of("codigo", code, "quantidade", quantity, "motivo", reason, "idempotencia", idempotency));
      Map<String, Object> result = new LinkedHashMap<>(receiptDetails(connection, receiptId));
      result.put("warning", "Avaria encaminhada para quarentena e decisão do supervisor.");
      return result;
    });
  }

  public Map<String, Object> createMap(MmCheckServer.SessionPrincipal principal, Map<String, Object> body,
                                       String idempotency) {
    requireAnyRole(principal, "admin", "supervisor", "separation");
    UUID branch = branchFor(principal, text(body.get("branchId")));
    UUID id = UUID.randomUUID();
    String number = required(body, "mapNumber");
    String fileName = required(body, "fileName");
    String fileHash = required(body, "fileHash").toLowerCase(Locale.ROOT);
    if (!fileHash.matches("[0-9a-f]{64}")) {
      throw new EnterpriseException(400, "Não foi possível validar o hash do PDF oficial.");
    }
    List<Map<String, Object>> items = objectList(body.get("items"));
    if (items.isEmpty()) throw new EnterpriseException(400, "O mapa precisa ter ao menos um item revisado.");
    return database.transaction(connection -> {
      Claim claim = claim(connection, principal, idempotency, "CRIAR_MAPA", "MAPA", id.toString());
      if (!claim.fresh()) return mapDetails(connection, uuid(claim.entityId(), "mapa"));
      requireBranch(connection, branch);
      EnterpriseDatabase.update(connection, """
          INSERT INTO mapas_separacao
            (id, filial_id, numero_mapa, pedidos, cliente, rota, arquivo_nome, arquivo_hash, status, criado_por)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RASCUNHO', ?)
          """, id, branch, number, text(body.get("orders")), text(body.get("customer")), text(body.get("route")),
          fileName, fileHash, principal.name());
      for (Map<String, Object> raw : items) {
        Map<String, Object> product = requireProduct(connection, required(raw, "code"));
        EnterpriseDatabase.update(connection, """
            INSERT INTO mapas_separacao_itens
              (id, mapa_id, produto_id, quantidade_esperada) VALUES (?, ?, ?, ?)
            """, UUID.randomUUID(), id, product.get("id"), positiveInt(raw.get("quantity"), "quantidade"));
      }
      audit(connection, branch, principal, "MAPA_REVISADO_CRIADO", "MAPA", id.toString(), Map.of("numero", number));
      return mapDetails(connection, id);
    });
  }

  public Map<String, Object> mapDetails(MmCheckServer.SessionPrincipal principal, UUID mapId) {
    return database.transaction(connection -> {
      Map<String, Object> map = mapDetails(connection, mapId);
      requireBranchAccess(connection, principal, (UUID) map.get("filial_id"));
      return map;
    });
  }

  public Map<String, Object> publishMap(MmCheckServer.SessionPrincipal principal, UUID mapId,
                                        String idempotency) {
    requireAnyRole(principal, "admin", "supervisor", "separation");
    return database.transaction(connection -> {
      Map<String, Object> map = lockMap(connection, mapId);
      UUID branch = (UUID) map.get("filial_id");
      requireBranchAccess(connection, principal, branch);
      if (!claim(connection, principal, idempotency, "PUBLICAR_MAPA", "MAPA", mapId.toString()).fresh()) {
        return mapDetails(connection, mapId);
      }
      if (!"RASCUNHO".equals(text(map.get("status")))) return mapDetails(connection, mapId);
      List<Map<String, Object>> items = EnterpriseDatabase.query(connection,
          "SELECT * FROM mapas_separacao_itens WHERE mapa_id = ? ORDER BY id FOR UPDATE", mapId);
      for (Map<String, Object> item : items) {
        UUID product = (UUID) item.get("produto_id");
        int quantity = integer(item.get("quantidade_esperada"));
        applyMovement(connection, new Movement(branch, product, "RESERVA", 0, -quantity, quantity, 0, 0,
            "MAPA", mapId.toString(), idempotency + ":item:" + item.get("id"), null,
            "Reserva para separação", principal.name(), "", null, Map.of()));
        EnterpriseDatabase.update(connection, """
            INSERT INTO reservas_estoque
              (id, filial_id, produto_id, mapa_id, item_id, quantidade)
            VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), branch, product, mapId, item.get("id"), quantity);
      }
      EnterpriseDatabase.update(connection, """
          UPDATE mapas_separacao SET status = 'PUBLICADO', revisado_por = ?, publicado_em = now() WHERE id = ?
          """, principal.name(), mapId);
      audit(connection, branch, principal, "MAPA_PUBLICADO", "MAPA", mapId.toString(), Map.of());
      return mapDetails(connection, mapId);
    });
  }

  public Map<String, Object> scanMap(MmCheckServer.SessionPrincipal principal, UUID mapId,
                                     Map<String, Object> body, boolean conference, String idempotency) {
    requireAnyRole(principal, "admin", "supervisor", conference ? "expedition" : "separation");
    String code = required(body, "code");
    int quantity = positiveInt(body.get("quantity"), "quantidade");
    return database.transaction(connection -> {
      Map<String, Object> map = lockMap(connection, mapId);
      UUID branch = (UUID) map.get("filial_id");
      requireBranchAccess(connection, principal, branch);
      String expectedStatus = conference ? "AGUARDANDO_CONFERENCIA" : "PUBLICADO";
      requireStatus(map, List.of(expectedStatus), "mapa");
      String scanOperation = conference ? "LER_CONFERENCIA" : "LER_SEPARACAO";
      if (!claim(connection, principal, idempotency, scanOperation, "MAPA", mapId.toString()).fresh()) {
        return mapDetails(connection, mapId);
      }
      Optional<Map<String, Object>> foundItem = EnterpriseDatabase.one(connection, """
          SELECT i.*, p.sku, p.codigo_interno FROM mapas_separacao_itens i
          JOIN produtos p ON p.id = i.produto_id LEFT JOIN produtos_eans e ON e.produto_id = p.id
          WHERE i.mapa_id = ? AND (p.sku = ? OR p.codigo_interno = ? OR e.ean = ?)
          FOR UPDATE OF i
          """, mapId, code, code, code);
      if (foundItem.isEmpty()) {
        createException(connection, branch, "MAPA", mapId, null, "PRODUTO_INCORRETO", quantity,
            "Código " + code + " não pertence ao mapa.", principal);
        audit(connection, branch, principal, "LEITURA_RECUSADA", "MAPA", mapId.toString(),
            Map.of("codigo", code, "quantidade", quantity, "idempotencia", idempotency));
        Map<String, Object> rejected = new LinkedHashMap<>(mapDetails(connection, mapId));
        rejected.put("rejected", true);
        rejected.put("error", "Produto não pertence ao mapa. A ocorrência foi registrada.");
        return rejected;
      }
      Map<String, Object> item = foundItem.get();
      String field = conference ? "quantidade_conferida" : "quantidade_separada";
      int current = integer(item.get(field));
      int expected = integer(item.get("quantidade_esperada")) - integer(item.get("quantidade_cancelada"));
      if (current + quantity > expected) {
        createException(connection, branch, "MAPA", mapId, (UUID) item.get("id"), "EXCESSO", current + quantity - expected,
            conference ? "Quantidade conferida acima do mapa." : "Quantidade separada acima do mapa.", principal);
        audit(connection, branch, principal, "LEITURA_RECUSADA", "MAPA", mapId.toString(),
            Map.of("codigo", code, "quantidade", quantity, "idempotencia", idempotency));
        Map<String, Object> rejected = new LinkedHashMap<>(mapDetails(connection, mapId));
        rejected.put("rejected", true);
        rejected.put("error", "Quantidade excede o previsto. A ocorrência foi registrada.");
        return rejected;
      }
      EnterpriseDatabase.update(connection, "UPDATE mapas_separacao_itens SET " + field + " = " + field + " + ?, status = ? WHERE id = ?",
          quantity, current + quantity == expected ? (conference ? "CONFERIDO" : "SEPARADO") : "PARCIAL", item.get("id"));
      audit(connection, branch, principal, conference ? "ITEM_CONFERIDO" : "ITEM_SEPARADO", "MAPA", mapId.toString(),
          Map.of("idempotencia", idempotency, "itemId", item.get("id"), "quantidade", quantity));
      return mapDetails(connection, mapId);
    });
  }

  public Map<String, Object> finishPicking(MmCheckServer.SessionPrincipal principal, UUID mapId,
                                           String idempotency) {
    requireAnyRole(principal, "admin", "supervisor", "separation");
    return database.transaction(connection -> {
      Map<String, Object> map = lockMap(connection, mapId);
      UUID branch = (UUID) map.get("filial_id");
      requireBranchAccess(connection, principal, branch);
      if (!claim(connection, principal, idempotency, "FINALIZAR_SEPARACAO", "MAPA", mapId.toString()).fresh()) {
        return mapDetails(connection, mapId);
      }
      requireStatus(map, List.of("PUBLICADO"), "mapa");
      long pending = count(connection, """
          SELECT COUNT(*) FROM mapas_separacao_itens
          WHERE mapa_id = ? AND quantidade_separada <> quantidade_esperada - quantidade_cancelada
          """, mapId);
      if (pending > 0) throw new EnterpriseException(409, "Ainda existem itens pendentes na separação.");
      EnterpriseDatabase.update(connection,
          "UPDATE mapas_separacao SET status = 'AGUARDANDO_CONFERENCIA' WHERE id = ?", mapId);
      audit(connection, branch, principal, "SEPARACAO_FINALIZADA", "MAPA", mapId.toString(), Map.of());
      return mapDetails(connection, mapId);
    });
  }

  public Map<String, Object> authorizeMapShortage(MmCheckServer.SessionPrincipal principal, UUID mapId,
                                                   Map<String, Object> body, String idempotency) {
    requireSupervisor(principal);
    String stage = required(body, "stage").toUpperCase(Locale.ROOT);
    String reason = required(body, "reason");
    boolean conference;
    if ("PICKING".equals(stage)) conference = false;
    else if ("CONFERENCE".equals(stage)) conference = true;
    else throw new EnterpriseException(400, "Etapa de autorização inválida.");
    return database.transaction(connection -> {
      Map<String, Object> map = lockMap(connection, mapId);
      UUID branch = (UUID) map.get("filial_id");
      requireBranchAccess(connection, principal, branch);
      String expectedStatus = conference ? "AGUARDANDO_CONFERENCIA" : "PUBLICADO";
      requireStatus(map, List.of(expectedStatus), "mapa");
      if (!claim(connection, principal, idempotency, "AUTORIZAR_FALTA_" + stage, "MAPA", mapId.toString()).fresh()) {
        return mapDetails(connection, mapId);
      }
      List<Map<String, Object>> items = EnterpriseDatabase.query(connection,
          "SELECT * FROM mapas_separacao_itens WHERE mapa_id = ? ORDER BY id FOR UPDATE", mapId);
      int totalShortage = 0;
      for (Map<String, Object> item : items) {
        int effective = integer(item.get("quantidade_esperada")) - integer(item.get("quantidade_cancelada"));
        int confirmed = integer(item.get(conference ? "quantidade_conferida" : "quantidade_separada"));
        int shortage = Math.max(0, effective - confirmed);
        if (shortage == 0) continue;
        UUID itemId = (UUID) item.get("id");
        UUID product = (UUID) item.get("produto_id");
        applyMovement(connection, new Movement(branch, product, "LIBERACAO_RESERVA_AUTORIZADA",
            0, shortage, -shortage, 0, 0, "MAPA", mapId.toString(),
            idempotency + ":item:" + itemId, null, reason, principal.name(), "", null,
            Map.of("etapa", stage)));
        EnterpriseDatabase.update(connection, """
            UPDATE reservas_estoque SET liberada = liberada + ?,
              status = CASE WHEN liberada + ? >= quantidade - consumida THEN 'LIBERADA' ELSE 'PARCIAL' END
            WHERE item_id = ?
            """, shortage, shortage, itemId);
        EnterpriseDatabase.update(connection, """
            UPDATE mapas_separacao_itens SET quantidade_cancelada = quantidade_cancelada + ?, status = ?
            WHERE id = ?
            """, shortage, conference ? "CONFERIDO_AUTORIZADO" : "SEPARADO_AUTORIZADO", itemId);
        UUID exceptionId = createException(connection, branch, "MAPA", mapId, itemId,
            conference ? "FALTA_CONFERENCIA" : "FALTA_SEPARACAO", shortage, reason, principal);
        EnterpriseDatabase.update(connection, """
            UPDATE divergencias_operacionais SET status = 'RESOLVIDA', resolucao = 'AUTORIZADA',
              justificativa = ?, resolvido_por = ?, resolvido_em = now() WHERE id = ?
            """, reason, principal.name(), exceptionId);
        totalShortage += shortage;
      }
      if (totalShortage == 0) throw new EnterpriseException(409, "Não existem faltas pendentes para autorizar.");
      audit(connection, branch, principal, "FALTA_AUTORIZADA", "MAPA", mapId.toString(),
          Map.of("etapa", stage, "quantidade", totalShortage, "justificativa", reason));
      return mapDetails(connection, mapId);
    });
  }

  public Map<String, Object> dispatchMap(MmCheckServer.SessionPrincipal principal, UUID mapId,
                                         String idempotency) {
    requireAnyRole(principal, "admin", "supervisor", "expedition");
    return database.transaction(connection -> {
      Map<String, Object> map = lockMap(connection, mapId);
      UUID branch = (UUID) map.get("filial_id");
      requireBranchAccess(connection, principal, branch);
      if (!claim(connection, principal, idempotency, "EXPEDIR_MAPA", "MAPA", mapId.toString()).fresh()) {
        return mapDetails(connection, mapId);
      }
      if ("EXPEDIDO".equals(text(map.get("status")))) return mapDetails(connection, mapId);
      requireStatus(map, List.of("AGUARDANDO_CONFERENCIA"), "mapa");
      List<Map<String, Object>> items = EnterpriseDatabase.query(connection,
          "SELECT * FROM mapas_separacao_itens WHERE mapa_id = ? ORDER BY id FOR UPDATE", mapId);
      if (items.stream().anyMatch(item -> integer(item.get("quantidade_conferida"))
          != integer(item.get("quantidade_esperada")) - integer(item.get("quantidade_cancelada")))) {
        throw new EnterpriseException(409, "A reconferência precisa estar completa antes da expedição.");
      }
      for (Map<String, Object> item : items) {
        int quantity = integer(item.get("quantidade_esperada")) - integer(item.get("quantidade_cancelada"));
        applyMovement(connection, new Movement(branch, (UUID) item.get("produto_id"), "EXPEDICAO",
            -quantity, 0, -quantity, 0, 0, "MAPA", mapId.toString(),
            idempotency + ":item:" + item.get("id"), null, "Expedição reconferida", principal.name(), "", null, Map.of()));
        EnterpriseDatabase.update(connection, """
            UPDATE reservas_estoque SET consumida = ?, status = 'CONSUMIDA' WHERE item_id = ?
            """, quantity, item.get("id"));
      }
      EnterpriseDatabase.update(connection,
          "UPDATE mapas_separacao SET status = 'EXPEDIDO', finalizado_em = now() WHERE id = ?", mapId);
      audit(connection, branch, principal, "EXPEDICAO_FINALIZADA", "MAPA", mapId.toString(), Map.of());
      return mapDetails(connection, mapId);
    });
  }

  public Map<String, Object> cancelMap(MmCheckServer.SessionPrincipal principal, UUID mapId,
                                       String reason, String idempotency) {
    requireSupervisor(principal);
    if (reason == null || reason.isBlank()) throw new EnterpriseException(400, "Informe o motivo do cancelamento.");
    return database.transaction(connection -> {
      Map<String, Object> map = lockMap(connection, mapId);
      UUID branch = (UUID) map.get("filial_id");
      requireBranchAccess(connection, principal, branch);
      if (!claim(connection, principal, idempotency, "CANCELAR_MAPA", "MAPA", mapId.toString()).fresh()) {
        return mapDetails(connection, mapId);
      }
      if ("CANCELADO".equals(text(map.get("status")))) return mapDetails(connection, mapId);
      requireStatus(map, List.of("RASCUNHO", "PUBLICADO", "AGUARDANDO_CONFERENCIA"), "mapa");
      List<Map<String, Object>> reservations = EnterpriseDatabase.query(connection, """
          SELECT * FROM reservas_estoque WHERE mapa_id = ? AND status = 'ATIVA' FOR UPDATE
          """, mapId);
      for (Map<String, Object> reservation : reservations) {
        int open = integer(reservation.get("quantidade")) - integer(reservation.get("consumida"))
            - integer(reservation.get("liberada"));
        if (open > 0) {
          applyMovement(connection, new Movement(branch, (UUID) reservation.get("produto_id"), "LIBERACAO_RESERVA",
              0, open, -open, 0, 0, "MAPA", mapId.toString(),
              idempotency + ":reserva:" + reservation.get("id"), null, reason, principal.name(), "", null, Map.of()));
          EnterpriseDatabase.update(connection, """
              UPDATE reservas_estoque SET liberada = liberada + ?, status = 'LIBERADA' WHERE id = ?
              """, open, reservation.get("id"));
        }
      }
      EnterpriseDatabase.update(connection, "UPDATE mapas_separacao SET status = 'CANCELADO' WHERE id = ?", mapId);
      audit(connection, branch, principal, "MAPA_CANCELADO", "MAPA", mapId.toString(), Map.of("motivo", reason));
      return mapDetails(connection, mapId);
    });
  }

  public Map<String, Object> createTransfer(MmCheckServer.SessionPrincipal principal, Map<String, Object> body,
                                            String idempotency) {
    requireAnyRole(principal, "admin", "supervisor", "expedition", "stock");
    UUID origin = branchFor(principal, required(body, "originBranchId"));
    UUID destination = uuid(required(body, "destinationBranchId"), "filial de destino");
    if (origin.equals(destination)) throw new EnterpriseException(400, "Origem e destino devem ser diferentes.");
    UUID id = UUID.randomUUID();
    String reference = required(body, "reference");
    List<Map<String, Object>> items = objectList(body.get("items"));
    if (items.isEmpty()) throw new EnterpriseException(400, "Informe ao menos um item da transferência.");
    return database.transaction(connection -> {
      Claim claim = claim(connection, principal, idempotency, "CRIAR_TRANSFERENCIA", "TRANSFERENCIA", id.toString());
      if (!claim.fresh()) return transferDetails(connection, uuid(claim.entityId(), "transferência"));
      requireBranch(connection, origin);
      requireBranch(connection, destination);
      EnterpriseDatabase.update(connection, """
          INSERT INTO transferencias
            (id, referencia, filial_origem_id, filial_destino_id, veiculo, rota, criado_por)
          VALUES (?, ?, ?, ?, ?, ?, ?)
          """, id, reference, origin, destination, text(body.get("vehicle")), text(body.get("route")), principal.name());
      for (Map<String, Object> raw : items) {
        Map<String, Object> product = requireProduct(connection, required(raw, "code"));
        EnterpriseDatabase.update(connection, """
            INSERT INTO transferencias_itens (id, transferencia_id, produto_id, quantidade)
            VALUES (?, ?, ?, ?)
            """, UUID.randomUUID(), id, product.get("id"), positiveInt(raw.get("quantity"), "quantidade"));
      }
      audit(connection, origin, principal, "TRANSFERENCIA_CRIADA", "TRANSFERENCIA", id.toString(), Map.of());
      return transferDetails(connection, id);
    });
  }

  public Map<String, Object> transferDetails(MmCheckServer.SessionPrincipal principal, UUID transferId) {
    return database.transaction(connection -> {
      Map<String, Object> transfer = transferDetails(connection, transferId);
      UUID origin = (UUID) transfer.get("filial_origem_id");
      UUID destination = (UUID) transfer.get("filial_destino_id");
      if (!principal.isAdmin()) {
        try {
          requireBranchAccess(connection, principal, origin);
        } catch (EnterpriseException originDenied) {
          requireBranchAccess(connection, principal, destination);
        }
      }
      return transfer;
    });
  }

  public Map<String, Object> approveTransfer(MmCheckServer.SessionPrincipal principal, UUID transferId,
                                             String idempotency) {
    requireSupervisor(principal);
    return database.transaction(connection -> {
      Map<String, Object> transfer = lockTransfer(connection, transferId);
      UUID origin = (UUID) transfer.get("filial_origem_id");
      requireBranchAccess(connection, principal, origin);
      if (!claim(connection, principal, idempotency, "APROVAR_TRANSFERENCIA", "TRANSFERENCIA", transferId.toString()).fresh()) {
        return transferDetails(connection, transferId);
      }
      if (!"RASCUNHO".equals(text(transfer.get("status")))) return transferDetails(connection, transferId);
      for (Map<String, Object> item : transferItems(connection, transferId)) {
        int quantity = integer(item.get("quantidade"));
        applyMovement(connection, new Movement(origin, (UUID) item.get("produto_id"), "RESERVA_TRANSFERENCIA",
            0, -quantity, quantity, 0, 0, "TRANSFERENCIA", transferId.toString(),
            idempotency + ":item:" + item.get("id"), null, "Reserva para transferência", principal.name(), "", null, Map.of()));
      }
      EnterpriseDatabase.update(connection, """
          UPDATE transferencias SET status = 'APROVADA', aprovado_por = ? WHERE id = ?
          """, principal.name(), transferId);
      audit(connection, origin, principal, "TRANSFERENCIA_APROVADA", "TRANSFERENCIA", transferId.toString(), Map.of());
      return transferDetails(connection, transferId);
    });
  }

  public Map<String, Object> shipTransfer(MmCheckServer.SessionPrincipal principal, UUID transferId,
                                          String idempotency) {
    requireAnyRole(principal, "admin", "supervisor", "expedition");
    return database.transaction(connection -> {
      Map<String, Object> transfer = lockTransfer(connection, transferId);
      UUID origin = (UUID) transfer.get("filial_origem_id");
      requireBranchAccess(connection, principal, origin);
      if (!claim(connection, principal, idempotency, "EXPEDIR_TRANSFERENCIA", "TRANSFERENCIA", transferId.toString()).fresh()) {
        return transferDetails(connection, transferId);
      }
      if ("EM_TRANSITO".equals(text(transfer.get("status")))) return transferDetails(connection, transferId);
      requireStatus(transfer, List.of("APROVADA"), "transferência");
      for (Map<String, Object> item : transferItems(connection, transferId)) {
        int quantity = integer(item.get("quantidade"));
        applyMovement(connection, new Movement(origin, (UUID) item.get("produto_id"), "TRANSFERENCIA_EXPEDIDA",
            -quantity, 0, -quantity, quantity, 0, "TRANSFERENCIA", transferId.toString(),
            idempotency + ":item:" + item.get("id"), null, "Transferência expedida", principal.name(), "", null, Map.of()));
      }
      EnterpriseDatabase.update(connection, """
          UPDATE transferencias SET status = 'EM_TRANSITO', expedido_em = now() WHERE id = ?
          """, transferId);
      audit(connection, origin, principal, "TRANSFERENCIA_EXPEDIDA", "TRANSFERENCIA", transferId.toString(), Map.of());
      return transferDetails(connection, transferId);
    });
  }

  public Map<String, Object> cancelTransfer(MmCheckServer.SessionPrincipal principal, UUID transferId,
                                            String reason, String idempotency) {
    requireSupervisor(principal);
    if (reason == null || reason.isBlank()) throw new EnterpriseException(400, "Informe o motivo do cancelamento.");
    return database.transaction(connection -> {
      Map<String, Object> transfer = lockTransfer(connection, transferId);
      UUID origin = (UUID) transfer.get("filial_origem_id");
      requireBranchAccess(connection, principal, origin);
      if (!claim(connection, principal, idempotency, "CANCELAR_TRANSFERENCIA", "TRANSFERENCIA", transferId.toString()).fresh()) {
        return transferDetails(connection, transferId);
      }
      String status = text(transfer.get("status"));
      if ("CANCELADA".equals(status)) return transferDetails(connection, transferId);
      requireStatus(transfer, List.of("RASCUNHO", "APROVADA"), "transferência");
      if ("APROVADA".equals(status)) {
        for (Map<String, Object> item : transferItems(connection, transferId)) {
          int quantity = integer(item.get("quantidade"));
          applyMovement(connection, new Movement(origin, (UUID) item.get("produto_id"), "LIBERACAO_TRANSFERENCIA",
              0, quantity, -quantity, 0, 0, "TRANSFERENCIA", transferId.toString(),
              idempotency + ":item:" + item.get("id"), null, reason, principal.name(), "", null, Map.of()));
        }
      }
      EnterpriseDatabase.update(connection, "UPDATE transferencias SET status = 'CANCELADA' WHERE id = ?", transferId);
      audit(connection, origin, principal, "TRANSFERENCIA_CANCELADA", "TRANSFERENCIA", transferId.toString(),
          Map.of("motivo", reason));
      return transferDetails(connection, transferId);
    });
  }

  public Map<String, Object> receiveTransfer(MmCheckServer.SessionPrincipal principal, UUID transferId,
                                             Map<String, Object> body, String idempotency) {
    requireAnyRole(principal, "admin", "supervisor", "receiving");
    Map<String, Integer> received = new LinkedHashMap<>();
    List<Map<String, Object>> receivedItems = objectList(body.get("items"));
    if (receivedItems.isEmpty()) {
      throw new EnterpriseException(400, "Reconfira e informe todos os itens recebidos.");
    }
    for (Map<String, Object> item : receivedItems) {
      String itemId = required(item, "itemId");
      if (received.put(itemId, nonNegativeInt(item.get("quantity"), "quantidade recebida")) != null) {
        throw new EnterpriseException(400, "O mesmo item foi informado mais de uma vez na reconferência.");
      }
    }
    return database.transaction(connection -> {
      Map<String, Object> transfer = lockTransfer(connection, transferId);
      UUID origin = (UUID) transfer.get("filial_origem_id");
      UUID destination = (UUID) transfer.get("filial_destino_id");
      requireBranchAccess(connection, principal, destination);
      if (!claim(connection, principal, idempotency, "RECEBER_TRANSFERENCIA", "TRANSFERENCIA", transferId.toString()).fresh()) {
        return transferDetails(connection, transferId);
      }
      if ("RECEBIDA".equals(text(transfer.get("status")))) return transferDetails(connection, transferId);
      requireStatus(transfer, List.of("EM_TRANSITO"), "transferência");
      List<Map<String, Object>> transferItems = transferItems(connection, transferId);
      if (received.size() != transferItems.size()
          || transferItems.stream().anyMatch(item -> !received.containsKey(item.get("id").toString()))) {
        throw new EnterpriseException(409, "A reconferência deve informar exatamente todos os itens da transferência.");
      }
      boolean divergent = false;
      for (Map<String, Object> item : transferItems) {
        UUID itemId = (UUID) item.get("id");
        UUID product = (UUID) item.get("produto_id");
        int expected = integer(item.get("quantidade"));
        int actual = received.getOrDefault(itemId.toString(), expected);
        int regular = Math.min(expected, actual);
        int shortage = Math.max(0, expected - actual);
        int excess = Math.max(0, actual - expected);
        applyMovement(connection, new Movement(origin, product, "TRANSFERENCIA_BAIXA_TRANSITO",
            0, 0, 0, -expected, 0, "TRANSFERENCIA", transferId.toString(),
            idempotency + ":item:" + itemId + ":transito", null, "Baixa do trânsito", principal.name(), "", null, Map.of()));
        if (regular > 0) {
          applyMovement(connection, new Movement(destination, product, "TRANSFERENCIA_RECEBIDA",
              regular, regular, 0, 0, 0, "TRANSFERENCIA", transferId.toString(),
              idempotency + ":item:" + itemId + ":destino", null, "Entrada no destino", principal.name(), "", null, Map.of()));
        }
        if (shortage > 0 || excess > 0) {
          divergent = true;
          int difference = shortage > 0 ? shortage : excess;
          createException(connection, destination, "TRANSFERENCIA", transferId, itemId,
              shortage > 0 ? "FALTA" : "EXCESSO", difference, "Divergência na reconferência da transferência.", principal);
          if (excess > 0) {
            applyMovement(connection, new Movement(destination, product, "QUARENTENA_ENTRADA",
                excess, 0, 0, 0, excess, "TRANSFERENCIA", transferId.toString(),
                idempotency + ":item:" + itemId + ":quarentena", null, "Excesso na transferência", principal.name(), "", null, Map.of()));
          }
        }
        EnterpriseDatabase.update(connection, """
            UPDATE transferencias_itens SET quantidade_recebida = ?, quantidade_quarentena = ? WHERE id = ?
            """, regular, excess, itemId);
      }
      EnterpriseDatabase.update(connection, """
          UPDATE transferencias SET status = ?, recebido_em = now() WHERE id = ?
          """, divergent ? "RECEBIDA_DIVERGENCIA" : "RECEBIDA", transferId);
      audit(connection, destination, principal, "TRANSFERENCIA_RECEBIDA", "TRANSFERENCIA", transferId.toString(),
          Map.of("comDivergencia", divergent));
      return transferDetails(connection, transferId);
    });
  }

  public Map<String, Object> resolveException(MmCheckServer.SessionPrincipal principal, UUID exceptionId,
                                              Map<String, Object> body, String idempotency) {
    requireSupervisor(principal);
    String resolution = required(body, "resolution").toUpperCase(Locale.ROOT);
    String reason = required(body, "reason");
    return database.transaction(connection -> {
      Map<String, Object> exception = EnterpriseDatabase.one(connection,
          "SELECT * FROM divergencias_operacionais WHERE id = ? FOR UPDATE", exceptionId)
          .orElseThrow(() -> new EnterpriseException(404, "Divergência não encontrada."));
      if (!"ABERTA".equals(text(exception.get("status")))) return exception;
      UUID branch = (UUID) exception.get("filial_id");
      requireBranchAccess(connection, principal, branch);
      if (!claim(connection, principal, idempotency, "RESOLVER_DIVERGENCIA", "DIVERGENCIA", exceptionId.toString()).fresh()) {
        return exception;
      }
      UUID itemId = (UUID) exception.get("item_id");
      int quantity = Math.abs(integer(exception.get("quantidade")));
      UUID product = null;
      if (itemId != null && "RECEBIMENTO".equals(text(exception.get("operacao_tipo")))) {
        product = EnterpriseDatabase.one(connection,
            "SELECT produto_id FROM recebimentos_itens WHERE id = ?", itemId)
            .map(row -> (UUID) row.get("produto_id")).orElse(null);
      } else if (itemId != null && "TRANSFERENCIA".equals(text(exception.get("operacao_tipo")))) {
        product = EnterpriseDatabase.one(connection,
            "SELECT produto_id FROM transferencias_itens WHERE id = ?", itemId)
            .map(row -> (UUID) row.get("produto_id")).orElse(null);
      } else if (itemId != null && "MAPA".equals(text(exception.get("operacao_tipo")))) {
        product = EnterpriseDatabase.one(connection,
            "SELECT produto_id FROM mapas_separacao_itens WHERE id = ?", itemId)
            .map(row -> (UUID) row.get("produto_id")).orElse(null);
      }
      if ("LIBERAR_ESTOQUE".equals(resolution)) {
        if (product == null) throw new EnterpriseException(409, "Cadastre e vincule o produto antes de liberar a quarentena.");
        applyMovement(connection, new Movement(branch, product, "QUARENTENA_LIBERADA", 0, quantity, 0, 0,
            -quantity, "DIVERGENCIA", exceptionId.toString(), idempotency, null, reason,
            principal.name(), "", null, Map.of()));
      } else if ("DEVOLVER".equals(resolution) || "DESCARTAR".equals(resolution)) {
        if (product != null && ("EXCESSO".equals(text(exception.get("tipo")))
            || "AVARIA".equals(text(exception.get("tipo"))))) {
          applyMovement(connection, new Movement(branch, product, "QUARENTENA_SAIDA", -quantity, 0, 0, 0,
              -quantity, "DIVERGENCIA", exceptionId.toString(), idempotency, null, reason,
              principal.name(), "", null, Map.of()));
        }
      } else if (!List.of("ACEITAR_FALTA", "CORRIGIDO", "CANCELAR_ITEM").contains(resolution)) {
        throw new EnterpriseException(400, "Resolução inválida.");
      }
      EnterpriseDatabase.update(connection, """
          UPDATE divergencias_operacionais SET status = 'RESOLVIDA', resolucao = ?, justificativa = ?,
          resolvido_por = ?, resolvido_em = now() WHERE id = ?
          """, resolution, reason, principal.name(), exceptionId);
      audit(connection, branch, principal, "DIVERGENCIA_RESOLVIDA", "DIVERGENCIA", exceptionId.toString(),
          Map.of("resolucao", resolution, "justificativa", reason));
      return EnterpriseDatabase.one(connection, "SELECT * FROM divergencias_operacionais WHERE id = ?", exceptionId).orElseThrow();
    });
  }

  public Map<String, Object> createPrinter(MmCheckServer.SessionPrincipal principal, Map<String, Object> body,
                                           String idempotency) {
    requireSupervisor(principal);
    UUID branch = branchFor(principal, text(body.get("branchId")));
    UUID id = UUID.randomUUID();
    String name = required(body, "name");
    String manufacturer = required(body, "manufacturer").toUpperCase(Locale.ROOT);
    if (!List.of("ZEBRA", "ARGOX", "NAVEGADOR").contains(manufacturer)) {
      throw new EnterpriseException(400, "Fabricante de impressora não suportado.");
    }
    int width = positiveInt(body.getOrDefault("widthMm", 60), "largura");
    int height = positiveInt(body.getOrDefault("heightMm", 40), "altura");
    int dpi = positiveInt(body.getOrDefault("dpi", 203), "DPI");
    return database.transaction(connection -> {
      Claim claim = claim(connection, principal, idempotency, "CRIAR_IMPRESSORA", "IMPRESSORA", id.toString());
      UUID claimedId = uuid(claim.entityId(), "impressora");
      if (!claim.fresh()) return printerResponse(connection, claimedId);
      requireBranch(connection, branch);
      EnterpriseDatabase.update(connection, """
          INSERT INTO impressoras (id, filial_id, nome, fabricante, largura_mm, altura_mm, dpi)
          VALUES (?, ?, ?, ?, ?, ?, ?)
          """, id, branch, name, manufacturer, width, height, dpi);
      audit(connection, branch, principal, "IMPRESSORA_CRIADA", "IMPRESSORA", id.toString(),
          Map.of("nome", name, "fabricante", manufacturer));
      return printerResponse(connection, id);
    });
  }

  public Map<String, Object> createPrintJob(MmCheckServer.SessionPrincipal principal, Map<String, Object> body,
                                            String idempotency) {
    requireAnyRole(principal, "admin", "supervisor", "receiving");
    UUID branch = branchFor(principal, text(body.get("branchId")));
    UUID product = uuid(required(body, "productId"), "produto");
    UUID originId = uuid(required(body, "originId"), "origem");
    int labels = positiveInt(body.get("labels"), "quantidade de etiquetas");
    boolean reprint = bool(body.get("reprint"), false);
    String reason = text(body.get("reason"));
    if (reprint && reason.isBlank()) throw new EnterpriseException(400, "Informe o motivo da reimpressão.");
    UUID id = UUID.randomUUID();
    return database.transaction(connection -> {
      Claim claim = claim(connection, principal, idempotency, "CRIAR_IMPRESSAO", "IMPRESSAO", id.toString());
      if (!claim.fresh()) return printJobResponse(connection, uuid(claim.entityId(), "impressão"));
      requireBranch(connection, branch);
      UUID printerId = text(body.get("printerId")).isBlank() ? null : uuid(text(body.get("printerId")), "impressora");
      int width = positiveInt(body.getOrDefault("widthMm", 60), "largura");
      int height = positiveInt(body.getOrDefault("heightMm", 40), "altura");
      int dpi = positiveInt(body.getOrDefault("dpi", 203), "DPI");
      if (printerId != null) {
        Map<String, Object> printer = EnterpriseDatabase.one(connection, """
            SELECT * FROM impressoras WHERE id = ? AND filial_id = ? AND ativa = TRUE
            """, printerId, branch).orElseThrow(() -> new EnterpriseException(404,
            "Impressora não encontrada, inativa ou pertencente a outra filial."));
        width = integer(printer.get("largura_mm"));
        height = integer(printer.get("altura_mm"));
        dpi = integer(printer.get("dpi"));
      }
      Map<String, Object> productRow = EnterpriseDatabase.one(connection, """
          SELECT p.*, COALESCE((SELECT ean FROM produtos_eans WHERE produto_id = p.id ORDER BY principal DESC LIMIT 1), '') AS ean
          FROM produtos p WHERE p.id = ?
          """, product).orElseThrow(() -> new EnterpriseException(404, "Produto não encontrado."));
      EnterpriseDatabase.update(connection, """
          INSERT INTO trabalhos_impressao
            (id, filial_id, produto_id, impressora_id, origem_tipo, origem_id, quantidade_etiquetas,
             largura_mm, altura_mm, dpi, reimpressao, motivo, criado_por)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, id, branch, product, printerId, text(body.getOrDefault("originType", "RECEBIMENTO")),
          originId, labels, width, height, dpi, reprint, reason, principal.name());
      audit(connection, branch, principal, reprint ? "ETIQUETA_REIMPRESSA" : "ETIQUETA_IMPRESSA",
          "IMPRESSAO", id.toString(), Map.of("produtoId", product, "quantidade", labels));
      return printJobResponse(connection, id);
    });
  }

  public Map<String, Object> saveParameter(MmCheckServer.SessionPrincipal principal, String parameterKey,
                                           Map<String, Object> body, String idempotency) {
    requireSupervisor(principal);
    UUID branch = branchFor(principal, text(body.get("branchId")));
    String key = text(parameterKey).toLowerCase(Locale.ROOT);
    if (!key.matches("[a-z0-9][a-z0-9._-]{1,79}")) {
      throw new EnterpriseException(400, "A chave do parâmetro deve usar letras, números, ponto, hífen ou sublinhado.");
    }
    if (!body.containsKey("value") || body.get("value") == null) {
      throw new EnterpriseException(400, "Informe o valor do parâmetro.");
    }
    String value;
    try {
      value = JSON.writeValueAsString(body.get("value"));
    } catch (JsonProcessingException error) {
      throw new EnterpriseException(400, "O valor do parâmetro não é um JSON válido.");
    }
    if (value.length() > 20_000) throw new EnterpriseException(400, "O valor do parâmetro é muito grande.");
    String entityId = branch + ":" + key;
    return database.transaction(connection -> {
      Claim claim = claim(connection, principal, idempotency, "SALVAR_PARAMETRO", "PARAMETRO", entityId);
      if (!claim.fresh()) return parameterResponse(connection, branch, key);
      requireBranch(connection, branch);
      EnterpriseDatabase.update(connection, """
          INSERT INTO parametros_operacionais (filial_id, chave, valor, atualizado_por)
          VALUES (?, ?, CAST(? AS JSONB), ?)
          ON CONFLICT (filial_id, chave) DO UPDATE
          SET valor = EXCLUDED.valor, atualizado_por = EXCLUDED.atualizado_por, atualizado_em = now()
          """, branch, key, value, principal.name());
      audit(connection, branch, principal, "PARAMETRO_ATUALIZADO", "PARAMETRO", key,
          Map.of("valor", body.get("value")));
      return parameterResponse(connection, branch, key);
    });
  }

  public Map<String, Object> applyCount(MmCheckServer.SessionPrincipal principal, Map<String, Object> body,
                                        String idempotency) {
    requireSupervisor(principal);
    UUID branch = branchFor(principal, text(body.get("branchId")));
    boolean opening = bool(body.get("opening"), false);
    String countReference = required(body, "countReference");
    List<Map<String, Object>> items = objectList(body.get("items"));
    if (items.isEmpty()) throw new EnterpriseException(400, "A contagem não possui itens para publicar.");
    return database.transaction(connection -> {
      requireBranch(connection, branch);
      if (!claim(connection, principal, idempotency, "APLICAR_CONTAGEM", "CONTAGEM", countReference).fresh()) {
        return Map.of("status", "APLICADA", "opening", opening, "reference", countReference);
      }
      if (opening && count(connection,
          "SELECT COUNT(*) FROM movimentos_estoque WHERE filial_id = ?", branch) > 0) {
        throw new EnterpriseException(409, "A filial já possui movimentos; publique como ajuste, não como abertura.");
      }
      for (Map<String, Object> raw : items) {
        Map<String, Object> product = requireProduct(connection, required(raw, "code"));
        UUID productId = (UUID) product.get("id");
        int counted = nonNegativeInt(raw.get("quantity"), "quantidade contada");
        Map<String, Object> balance = lockBalance(connection, branch, productId);
        int physical = integer(balance.get("fisico"));
        int reserved = integer(balance.get("reservado"));
        if (reserved > 0 && counted < reserved) {
          throw new EnterpriseException(409, "A contagem do SKU " + product.get("sku") + " é menor que sua reserva ativa.");
        }
        int delta = counted - physical;
        if (delta == 0) continue;
        applyMovement(connection, new Movement(branch, productId, opening ? "ABERTURA" : "AJUSTE_CONTAGEM",
            delta, delta, 0, 0, 0, "CONTAGEM", countReference,
            idempotency + ":produto:" + productId, null,
            opening ? "Saldo inicial por contagem física" : required(body, "reason"),
            principal.name(), "", null, Map.of("saldoAnterior", physical, "contado", counted)));
      }
      audit(connection, branch, principal, opening ? "SALDO_ABERTURA_PUBLICADO" : "CONTAGEM_AJUSTADA",
          "CONTAGEM", countReference, Map.of("itens", items.size()));
      return Map.of("status", "APLICADA", "opening", opening, "reference", countReference);
    });
  }

  public Map<String, Object> reverseMovement(MmCheckServer.SessionPrincipal principal, UUID movementId,
                                             String reason, String idempotency) {
    requireSupervisor(principal);
    if (reason == null || reason.isBlank()) throw new EnterpriseException(400, "Informe o motivo do estorno.");
    return database.transaction(connection -> {
      Map<String, Object> movement = EnterpriseDatabase.one(connection,
          "SELECT * FROM movimentos_estoque WHERE id = ? FOR UPDATE", movementId)
          .orElseThrow(() -> new EnterpriseException(404, "Movimento não encontrado."));
      UUID branch = (UUID) movement.get("filial_id");
      requireBranchAccess(connection, principal, branch);
      if (!claim(connection, principal, idempotency, "ESTORNAR_MOVIMENTO", "MOVIMENTO", movementId.toString()).fresh()) {
        Map<String, Object> reversal = EnterpriseDatabase.one(connection,
            "SELECT id FROM movimentos_estoque WHERE estorno_de = ?", movementId)
            .orElseThrow(() -> new EnterpriseException(409, "O estorno idempotente ainda não foi localizado."));
        return Map.of("id", reversal.get("id"), "reversed", movementId);
      }
      if (count(connection, "SELECT COUNT(*) FROM movimentos_estoque WHERE estorno_de = ?", movementId) > 0) {
        throw new EnterpriseException(409, "Este movimento já foi estornado.");
      }
      UUID reversalId = applyMovement(connection, new Movement(branch, (UUID) movement.get("produto_id"),
          "ESTORNO", -integer(movement.get("delta_fisico")), -integer(movement.get("delta_disponivel")),
          -integer(movement.get("delta_reservado")), -integer(movement.get("delta_transito")),
          -integer(movement.get("delta_quarentena")), "MOVIMENTO", movementId.toString(), idempotency,
          movementId, reason, principal.name(), "", null, Map.of("tipoOriginal", movement.get("tipo"))));
      audit(connection, branch, principal, "MOVIMENTO_ESTORNADO", "MOVIMENTO", movementId.toString(),
          Map.of("estorno", reversalId, "motivo", reason));
      return Map.of("id", reversalId, "reversed", movementId);
    });
  }

  public Map<String, Object> reconcile(MmCheckServer.SessionPrincipal principal, String requestedBranch) {
    requireAnyRole(principal, "admin", "supervisor", "stock", "auditor");
    UUID branch = branchFor(principal, requestedBranch);
    List<Map<String, Object>> differences = database.query("""
        WITH ledger AS (
          SELECT filial_id, produto_id, SUM(delta_fisico) AS fisico_calculado,
                 SUM(delta_disponivel) AS disponivel_calculado, SUM(delta_reservado) AS reservado_calculado,
                 SUM(delta_transito) AS transito_calculado, SUM(delta_quarentena) AS quarentena_calculada
          FROM movimentos_estoque WHERE filial_id = CAST(? AS UUID) GROUP BY filial_id, produto_id
        ), compared AS (
          SELECT COALESCE(s.produto_id, l.produto_id) AS produto_id,
                 COALESCE(s.fisico, 0) AS fisico, COALESCE(l.fisico_calculado, 0) AS fisico_calculado,
                 COALESCE(s.disponivel, 0) AS disponivel, COALESCE(l.disponivel_calculado, 0) AS disponivel_calculado,
                 COALESCE(s.reservado, 0) AS reservado, COALESCE(l.reservado_calculado, 0) AS reservado_calculado,
                 COALESCE(s.em_transito, 0) AS em_transito, COALESCE(l.transito_calculado, 0) AS transito_calculado,
                 COALESCE(s.quarentena, 0) AS quarentena, COALESCE(l.quarentena_calculada, 0) AS quarentena_calculada
          FROM (SELECT * FROM saldos_estoque WHERE filial_id = CAST(? AS UUID)) s
          FULL OUTER JOIN ledger l ON l.filial_id = s.filial_id AND l.produto_id = s.produto_id
        )
        SELECT p.sku, c.* FROM compared c JOIN produtos p ON p.id = c.produto_id
        WHERE c.fisico <> c.fisico_calculado OR c.disponivel <> c.disponivel_calculado
           OR c.reservado <> c.reservado_calculado OR c.em_transito <> c.transito_calculado
           OR c.quarentena <> c.quarentena_calculada
        """, branch, branch);
    return Map.of("branchId", branch, "ok", differences.isEmpty(), "differences", differences,
        "checkedAt", Instant.now().toString());
  }

  private Map<String, Object> dashboard(UUID branch, boolean global) {
    String filter = global ? "" : " WHERE filial_id = CAST(? AS UUID) ";
    Object[] parameters = global ? new Object[0] : new Object[]{branch};
    Map<String, Object> stock = database.one("""
        SELECT COALESCE(SUM(fisico), 0) AS fisico, COALESCE(SUM(disponivel), 0) AS disponivel,
               COALESCE(SUM(reservado), 0) AS reservado, COALESCE(SUM(em_transito), 0) AS em_transito,
               COALESCE(SUM(quarentena), 0) AS quarentena, COUNT(*) AS skus
        FROM saldos_estoque
        """ + filter, parameters).orElse(Map.of());
    String receiptFilter = global ? "" : " AND filial_id = CAST(? AS UUID) ";
    long pendingReceipts = scalar("SELECT COUNT(*) FROM recebimentos WHERE status IN ('RASCUNHO','EM_RECEBIMENTO')" + receiptFilter,
        parameters);
    long pendingMaps = scalar("SELECT COUNT(*) FROM mapas_separacao WHERE status IN ('RASCUNHO','PUBLICADO','AGUARDANDO_CONFERENCIA')" + receiptFilter,
        parameters);
    long openExceptions = scalar("SELECT COUNT(*) FROM divergencias_operacionais WHERE status = 'ABERTA'" + receiptFilter,
        parameters);
    return Map.of("stock", stock, "pendingReceipts", pendingReceipts,
        "pendingMaps", pendingMaps, "openExceptions", openExceptions);
  }

  private Map<String, Object> productResponse(Connection connection, UUID id) throws SQLException {
    Map<String, Object> row = EnterpriseDatabase.one(connection, """
        SELECT p.*, COALESCE(string_agg(e.ean, ',' ORDER BY e.principal DESC, e.ean), '') AS eans
        FROM produtos p LEFT JOIN produtos_eans e ON e.produto_id = p.id
        WHERE p.id = ? GROUP BY p.id
        """, id).orElseThrow(() -> new EnterpriseException(404, "Produto não encontrado."));
    return Map.of("id", id, "sku", row.get("sku"), "internalCode", row.get("codigo_interno"),
        "description", row.get("descricao"), "eans", stringList(row.get("eans")));
  }

  private Map<String, Object> printJobResponse(Connection connection, UUID id) throws SQLException {
    Map<String, Object> row = EnterpriseDatabase.one(connection, """
        SELECT j.id AS job_id, j.quantidade_etiquetas, j.largura_mm, j.altura_mm,
               p.sku, p.codigo_interno, p.descricao,
               COALESCE((SELECT ean FROM produtos_eans WHERE produto_id = p.id
                         ORDER BY principal DESC, ean LIMIT 1), '') AS ean
        FROM trabalhos_impressao j JOIN produtos p ON p.id = j.produto_id WHERE j.id = ?
        """, id).orElseThrow(() -> new EnterpriseException(404, "Trabalho de impressão não encontrado."));
    Map<String, Object> response = new LinkedHashMap<>(row);
    response.put("jobId", row.get("job_id"));
    response.put("labels", row.get("quantidade_etiquetas"));
    response.put("widthMm", row.get("largura_mm"));
    response.put("heightMm", row.get("altura_mm"));
    return response;
  }

  private Map<String, Object> printerResponse(Connection connection, UUID id) throws SQLException {
    return EnterpriseDatabase.one(connection, """
        SELECT i.id, i.filial_id, f.nome AS filial_nome, i.nome, i.fabricante,
               i.largura_mm, i.altura_mm, i.dpi, i.ativa
        FROM impressoras i JOIN filiais f ON f.id = i.filial_id WHERE i.id = ?
        """, id).orElseThrow(() -> new EnterpriseException(404, "Impressora não encontrada."));
  }

  private Map<String, Object> parameterResponse(Connection connection, UUID branch, String key) throws SQLException {
    return EnterpriseDatabase.one(connection, """
        SELECT p.filial_id, f.nome AS filial_nome, p.chave, p.valor::text AS valor,
               p.atualizado_por, p.atualizado_em
        FROM parametros_operacionais p JOIN filiais f ON f.id = p.filial_id
        WHERE p.filial_id = ? AND p.chave = ?
        """, branch, key).orElseThrow(() -> new EnterpriseException(404, "Parâmetro não encontrado."));
  }

  private Claim claim(Connection connection, MmCheckServer.SessionPrincipal principal, String key,
                      String operation, String entityType, String entityId) throws SQLException {
    int inserted = EnterpriseDatabase.update(connection, """
        INSERT INTO requisicoes_idempotentes (chave, usuario_id, operacao, entidade_tipo, entidade_id)
        VALUES (?, ?, ?, ?, ?) ON CONFLICT (chave) DO NOTHING
        """, key, principal.id(), operation, entityType, entityId);
    Map<String, Object> stored = EnterpriseDatabase.one(connection, """
        SELECT usuario_id, operacao, entidade_tipo, entidade_id
        FROM requisicoes_idempotentes WHERE chave = ?
        """, key).orElseThrow();
    if (!principal.id().equals(text(stored.get("usuario_id")))
        || !operation.equals(text(stored.get("operacao")))
        || !entityType.equals(text(stored.get("entidade_tipo")))) {
      throw new EnterpriseException(409, "A chave de idempotência já foi usada em outra operação.");
    }
    return new Claim(inserted == 1, text(stored.get("entidade_id")));
  }

  private Map<String, Object> receiptDetails(Connection connection, UUID id) throws SQLException {
    Map<String, Object> receipt = EnterpriseDatabase.one(connection, """
        SELECT r.*, f.codigo AS filial_codigo, f.nome AS filial_nome
        FROM recebimentos r JOIN filiais f ON f.id = r.filial_id WHERE r.id = ?
        """, id).orElseThrow(() -> new EnterpriseException(404, "Recebimento não encontrado."));
    Map<String, Object> result = new LinkedHashMap<>(receipt);
    result.put("documents", EnterpriseDatabase.query(connection, """
        SELECT * FROM recebimentos_documentos WHERE recebimento_id = ? ORDER BY importado_em
        """, id));
    result.put("items", EnterpriseDatabase.query(connection, """
        SELECT i.*, p.sku, p.codigo_interno AS produto_codigo, p.descricao AS produto_descricao
        FROM recebimentos_itens i LEFT JOIN produtos p ON p.id = i.produto_id
        WHERE i.recebimento_id = ? ORDER BY i.descricao, i.id
        """, id));
    result.put("exceptions", EnterpriseDatabase.query(connection, """
        SELECT * FROM divergencias_operacionais
        WHERE operacao_tipo = 'RECEBIMENTO' AND operacao_id = ? ORDER BY criado_em
        """, id));
    return result;
  }

  private Map<String, Object> mapDetails(Connection connection, UUID id) throws SQLException {
    Map<String, Object> map = EnterpriseDatabase.one(connection, """
        SELECT m.*, f.nome AS filial_nome FROM mapas_separacao m
        JOIN filiais f ON f.id = m.filial_id WHERE m.id = ?
        """, id).orElseThrow(() -> new EnterpriseException(404, "Mapa não encontrado."));
    Map<String, Object> result = new LinkedHashMap<>(map);
    result.put("items", EnterpriseDatabase.query(connection, """
        SELECT i.*, p.sku, p.codigo_interno, p.descricao
        FROM mapas_separacao_itens i JOIN produtos p ON p.id = i.produto_id
        WHERE i.mapa_id = ? ORDER BY p.descricao
        """, id));
    result.put("exceptions", EnterpriseDatabase.query(connection, """
        SELECT * FROM divergencias_operacionais WHERE operacao_tipo = 'MAPA' AND operacao_id = ? ORDER BY criado_em
        """, id));
    return result;
  }

  private Map<String, Object> transferDetails(Connection connection, UUID id) throws SQLException {
    Map<String, Object> transfer = EnterpriseDatabase.one(connection, """
        SELECT t.*, origem.nome AS filial_origem, destino.nome AS filial_destino
        FROM transferencias t JOIN filiais origem ON origem.id = t.filial_origem_id
        JOIN filiais destino ON destino.id = t.filial_destino_id WHERE t.id = ?
        """, id).orElseThrow(() -> new EnterpriseException(404, "Transferência não encontrada."));
    Map<String, Object> result = new LinkedHashMap<>(transfer);
    result.put("items", transferItems(connection, id));
    result.put("exceptions", EnterpriseDatabase.query(connection, """
        SELECT * FROM divergencias_operacionais
        WHERE operacao_tipo = 'TRANSFERENCIA' AND operacao_id = ? ORDER BY criado_em
        """, id));
    return result;
  }

  private List<Map<String, Object>> transferItems(Connection connection, UUID id) throws SQLException {
    return EnterpriseDatabase.query(connection, """
        SELECT i.*, p.sku, p.codigo_interno, p.descricao,
               COALESCE((SELECT string_agg(e.ean, ',' ORDER BY e.principal DESC, e.ean)
                         FROM produtos_eans e WHERE e.produto_id = p.id), '') AS eans
        FROM transferencias_itens i JOIN produtos p ON p.id = i.produto_id
        WHERE i.transferencia_id = ? ORDER BY p.descricao FOR UPDATE OF i
        """, id);
  }

  private Map<String, Object> lockReceipt(Connection connection, UUID id) throws SQLException {
    return EnterpriseDatabase.one(connection, "SELECT * FROM recebimentos WHERE id = ? FOR UPDATE", id)
        .orElseThrow(() -> new EnterpriseException(404, "Recebimento não encontrado."));
  }

  private Map<String, Object> lockMap(Connection connection, UUID id) throws SQLException {
    return EnterpriseDatabase.one(connection, "SELECT * FROM mapas_separacao WHERE id = ? FOR UPDATE", id)
        .orElseThrow(() -> new EnterpriseException(404, "Mapa não encontrado."));
  }

  private Map<String, Object> lockTransfer(Connection connection, UUID id) throws SQLException {
    return EnterpriseDatabase.one(connection, "SELECT * FROM transferencias WHERE id = ? FOR UPDATE", id)
        .orElseThrow(() -> new EnterpriseException(404, "Transferência não encontrada."));
  }

  private Optional<Map<String, Object>> findProduct(Connection connection, String sku, String ean) throws SQLException {
    return EnterpriseDatabase.one(connection, """
        SELECT DISTINCT p.* FROM produtos p LEFT JOIN produtos_eans e ON e.produto_id = p.id
        WHERE p.ativo = TRUE AND (p.sku = ? OR p.codigo_interno = ? OR e.ean = ? OR e.ean = ?)
        LIMIT 1
        """, sku, sku, ean, sku);
  }

  private Map<String, Object> requireProduct(Connection connection, String code) throws SQLException {
    return EnterpriseDatabase.one(connection, """
        SELECT DISTINCT p.* FROM produtos p LEFT JOIN produtos_eans e ON e.produto_id = p.id
        WHERE p.ativo = TRUE AND (p.id::text = ? OR p.sku = ? OR p.codigo_interno = ? OR e.ean = ?)
        LIMIT 1
        """, code, code, code, code).orElseThrow(() -> new EnterpriseException(404,
        "Produto " + code + " não está cadastrado."));
  }

  private Map<String, Object> lockBalance(Connection connection, UUID branch, UUID product) throws SQLException {
    EnterpriseDatabase.update(connection, """
        INSERT INTO saldos_estoque (filial_id, produto_id) VALUES (?, ?)
        ON CONFLICT (filial_id, produto_id) DO NOTHING
        """, branch, product);
    return EnterpriseDatabase.one(connection, """
        SELECT * FROM saldos_estoque WHERE filial_id = ? AND produto_id = ? FOR UPDATE
        """, branch, product).orElseThrow();
  }

  private UUID applyMovement(Connection connection, Movement movement) throws SQLException {
    Optional<Map<String, Object>> duplicate = EnterpriseDatabase.one(connection,
        "SELECT id FROM movimentos_estoque WHERE idempotencia = ?", movement.idempotency());
    if (duplicate.isPresent()) return (UUID) duplicate.get().get("id");
    Map<String, Object> balance = lockBalance(connection, movement.branch(), movement.product());
    int physical = integer(balance.get("fisico")) + movement.physical();
    int available = integer(balance.get("disponivel")) + movement.available();
    int reserved = integer(balance.get("reservado")) + movement.reserved();
    int transit = integer(balance.get("em_transito")) + movement.transit();
    int quarantine = integer(balance.get("quarentena")) + movement.quarantine();
    if (physical < 0 || available < 0 || reserved < 0 || transit < 0 || quarantine < 0
        || available + reserved > physical) {
      throw new EnterpriseException(409, "Saldo insuficiente ou estado de estoque inválido para esta movimentação.");
    }
    UUID id = UUID.randomUUID();
    EnterpriseDatabase.update(connection, """
        INSERT INTO movimentos_estoque
          (id, filial_id, produto_id, tipo, delta_fisico, delta_disponivel, delta_reservado,
           delta_transito, delta_quarentena, origem_tipo, origem_id, idempotencia, estorno_de,
           motivo, criado_por, dispositivo, ocorrido_cliente_em, metadados)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
        """, id, movement.branch(), movement.product(), movement.type(), movement.physical(),
        movement.available(), movement.reserved(), movement.transit(), movement.quarantine(),
        movement.sourceType(), movement.sourceId(), movement.idempotency(), movement.reversalOf(),
        movement.reason(), movement.createdBy(), movement.device(), movement.clientAt(), json(movement.metadata()));
    EnterpriseDatabase.update(connection, """
        UPDATE saldos_estoque SET fisico = ?, disponivel = ?, reservado = ?, em_transito = ?,
        quarentena = ?, atualizado_em = now() WHERE filial_id = ? AND produto_id = ?
        """, physical, available, reserved, transit, quarantine, movement.branch(), movement.product());
    return id;
  }

  private UUID createException(Connection connection, UUID branch, String operationType, UUID operationId,
                               UUID itemId, String type, int quantity, String description,
                               MmCheckServer.SessionPrincipal principal) throws SQLException {
    UUID id = UUID.randomUUID();
    EnterpriseDatabase.update(connection, """
        INSERT INTO divergencias_operacionais
          (id, filial_id, operacao_tipo, operacao_id, item_id, tipo, quantidade, descricao, criado_por)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, id, branch, operationType, operationId, itemId, type, quantity, description, principal.name());
    audit(connection, branch, principal, "DIVERGENCIA_ABERTA", "DIVERGENCIA", id.toString(),
        Map.of("tipo", type, "operacao", operationId));
    return id;
  }

  private void createExceptionIfAbsent(Connection connection, UUID branch, String operationType, UUID operationId,
                                       UUID itemId, String type, int quantity, String description,
                                       MmCheckServer.SessionPrincipal principal) throws SQLException {
    if (count(connection, """
        SELECT COUNT(*) FROM divergencias_operacionais
        WHERE operacao_tipo = ? AND operacao_id = ? AND item_id = ? AND tipo = ? AND status = 'ABERTA'
        """, operationType, operationId, itemId, type) == 0) {
      createException(connection, branch, operationType, operationId, itemId, type, quantity, description, principal);
    }
  }

  private void audit(Connection connection, UUID branch, MmCheckServer.SessionPrincipal principal,
                     String action, String entityType, String entityId, Map<String, Object> details) throws SQLException {
    EnterpriseDatabase.update(connection, """
        INSERT INTO auditoria_empresarial
          (filial_id, usuario_id, acao, entidade_tipo, entidade_id, detalhes)
        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
        """, branch, principal.id(), action, entityType, entityId, json(details));
  }

  private UUID branchFor(MmCheckServer.SessionPrincipal principal, String requested) {
    if (principal.isAdmin() && "all".equalsIgnoreCase(text(requested))) return MAIN_BRANCH;
    if (principal.isAdmin() && !text(requested).isBlank()) return uuid(requested, "filial");
    if (principal.isAdmin()) return MAIN_BRANCH;
    Optional<Map<String, Object>> profile = database.one(
        "SELECT filial_id FROM perfis_empresariais WHERE usuario_id = ?", principal.id());
    UUID assigned = profile.map(row -> (UUID) row.get("filial_id"))
        .orElseThrow(() -> new EnterpriseException(403,
            "Seu usuário ainda não foi vinculado a uma filial pelo administrador."));
    if (!text(requested).isBlank() && !assigned.toString().equals(requested)) {
      throw new EnterpriseException(403, "Usuário não possui acesso à filial informada.");
    }
    return assigned;
  }

  private void requireBranchAccess(Connection connection, MmCheckServer.SessionPrincipal principal, UUID branch)
      throws SQLException {
    if (principal.isAdmin()) return;
    UUID assigned = EnterpriseDatabase.one(connection,
        "SELECT filial_id FROM perfis_empresariais WHERE usuario_id = ?", principal.id())
        .map(row -> (UUID) row.get("filial_id"))
        .orElseThrow(() -> new EnterpriseException(403,
            "Seu usuário ainda não foi vinculado a uma filial pelo administrador."));
    if (!assigned.equals(branch)) throw new EnterpriseException(403, "Operação pertence a outra filial.");
  }

  private void requireBranch(Connection connection, UUID branch) throws SQLException {
    if (EnterpriseDatabase.one(connection, "SELECT id FROM filiais WHERE id = ? AND ativa = TRUE", branch).isEmpty()) {
      throw new EnterpriseException(404, "Filial não encontrada ou inativa.");
    }
  }

  private static void requireAdmin(MmCheckServer.SessionPrincipal principal) {
    requireAnyRole(principal, "admin");
  }

  private static void requireSupervisor(MmCheckServer.SessionPrincipal principal) {
    requireAnyRole(principal, "admin", "supervisor");
  }

  private static void requireAnyRole(MmCheckServer.SessionPrincipal principal, String... roles) {
    if (List.of(roles).contains(principal.role())) return;
    throw new EnterpriseException(403, "Seu perfil não possui permissão para esta operação.");
  }

  private static void requireStatus(Map<String, Object> record, List<String> statuses, String entity) {
    String current = text(record.get("status"));
    if (!statuses.contains(current)) {
      throw new EnterpriseException(409, "O status atual do " + entity + " não permite esta operação.");
    }
  }

  private long scalar(String sql, Object... parameters) {
    return database.one(sql, parameters).map(row -> ((Number) row.values().iterator().next()).longValue()).orElse(0L);
  }

  private static long count(Connection connection, String sql, Object... parameters) throws SQLException {
    Map<String, Object> row = EnterpriseDatabase.one(connection, sql, parameters).orElse(Map.of("count", 0));
    return ((Number) row.values().iterator().next()).longValue();
  }

  private static String required(Map<String, Object> body, String key) {
    String value = text(body.get(key));
    if (value.isBlank()) throw new EnterpriseException(400, "Campo obrigatório não informado: " + key + ".");
    return value;
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static int integer(Object value) {
    return value instanceof Number number ? number.intValue() : Integer.parseInt(text(value));
  }

  private static int positiveInt(Object value, String label) {
    int parsed = nonNegativeInt(value, label);
    if (parsed <= 0) throw new EnterpriseException(400, "A " + label + " deve ser maior que zero.");
    return parsed;
  }

  private static int nonNegativeInt(Object value, String label) {
    try {
      int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(text(value));
      if (parsed < 0) throw new NumberFormatException();
      return parsed;
    } catch (Exception error) {
      throw new EnterpriseException(400, "Informe uma " + label + " válida.");
    }
  }

  private static boolean bool(Object value, boolean fallback) {
    return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
  }

  private static UUID uuid(String value, String label) {
    try {
      return UUID.fromString(value);
    } catch (Exception error) {
      throw new EnterpriseException(400, "Identificador de " + label + " inválido.");
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> objectList(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
    return result;
  }

  private static List<String> stringList(Object value) {
    if (value instanceof List<?> list) return list.stream().map(EnterpriseService::text).toList();
    String text = text(value);
    return text.isBlank() ? List.of() : List.of(text.split("[,;\\s]+"));
  }

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value == null ? Map.of() : value);
    } catch (JsonProcessingException error) {
      throw new EnterpriseException(500, "Não foi possível registrar os detalhes de auditoria.", error);
    }
  }

  private record Movement(UUID branch, UUID product, String type,
                          int physical, int available, int reserved, int transit, int quarantine,
                          String sourceType, String sourceId, String idempotency, UUID reversalOf,
                          String reason, String createdBy, String device, Instant clientAt,
                          Map<String, Object> metadata) {}

  private record Claim(boolean fresh, String entityId) {}
}
