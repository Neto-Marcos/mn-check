package br.com.mncheck;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class InventoryInvestigationService {

  public static final Set<String> STATUSES = Set.of(
      "PENDENTE", "EM_INVESTIGACAO", "AGUARDANDO_EVIDENCIA", "RESOLVIDA", "SEM_CAUSA_IDENTIFICADA"
  );

  public static final Set<String> CAUSES = Set.of(
      "ERRO_CONTAGEM", "INVERSAO_PRODUTO", "INVERSAO_VOLTAGEM", "AVARIA", "ASSISTENCIA",
      "ERRO_SEPARACAO", "MERCADORIA_CLIENTE", "MOVIMENTACAO", "FISCAL_NF", "NAO_LOCALIZADO",
      "OUTRO", "NAO_IDENTIFICADA"
  );

  public static final Set<String> EVIDENCE_TYPES = Set.of(
      "OBSERVACAO", "FOTO", "DOCUMENTO", "NOTA_FISCAL", "CONTAGEM", "PRODUTO_RELACIONADO", "OUTRO"
  );

  public static final Set<String> LINK_TYPES = Set.of(
      "POSSIVEL_INVERSAO", "POSSIVEL_VOLTAGEM", "MESMO_PRODUTO", "MOVIMENTACAO_RELACIONADA", "OUTRO"
  );

  public static final Set<String> EVENT_TYPES = Set.of(
      "CRIADA", "INICIADA", "STATUS_ALTERADO", "CAUSA_SUSPEITA_ALTERADA",
      "EVIDENCIA_ADICIONADA", "PRODUTO_RELACIONADO", "RESOLVIDA", "ENCERRADA_SEM_CAUSA", "REABERTA"
  );

  private final DatabaseUrlParser.JdbcConfig database;
  private final ObjectMapper objectMapper;

  public InventoryInvestigationService() {
    this(System.getenv("DATABASE_URL"), new ObjectMapper());
  }

  InventoryInvestigationService(String databaseUrl) {
    this(databaseUrl, new ObjectMapper());
  }

  InventoryInvestigationService(String databaseUrl, ObjectMapper objectMapper) {
    this.database = DatabaseUrlParser.parse(databaseUrl);
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
  }

  public List<InvestigationSummary> list(long inventoryId, String branchCode, String statusFilter) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    String status = (statusFilter == null || statusFilter.isBlank() || "TODAS".equalsIgnoreCase(statusFilter))
        ? null : enumValue(statusFilter, STATUSES, "Filtro de status inválido.");

    try (Connection connection = connect()) {
      requireBranchAndInventory(connection, inventoryId, normalizedBranch);

      StringBuilder sql = new StringBuilder("""
          SELECT inv.id, inv.filial_id, inv.inventario_id, inv.inventario_item_id, inv.apuracao_id,
                 inv.sku, inv.status, inv.causa_suspeita, inv.causa_confirmada,
                 inv.justificativa, inv.conclusao, inv.responsavel_id, inv.responsavel_nome,
                 inv.criado_em, inv.criado_por, inv.atualizado_em, inv.atualizado_por,
                 inv.resolvido_em, inv.resolvido_por, inv.version,
                 it.descricao_snapshot, ap.saldo_snapshot, ap.quantidade_fisica, ap.diferenca, ap.estado AS apuracao_estado,
                 (SELECT COUNT(*) FROM evidencias_investigacao ev WHERE ev.investigacao_id = inv.id) AS total_evidencias,
                 (SELECT COUNT(*) FROM investigacao_vinculos v WHERE v.investigacao_id = inv.id) AS total_vinculos
          FROM investigacoes_divergencia inv
          JOIN inventario_itens it ON it.id = inv.inventario_item_id
          JOIN apuracoes_rodada ap ON ap.id = inv.apuracao_id
          WHERE inv.inventario_id = ?
          """);

      if (status != null) {
        sql.append(" AND inv.status = ?");
      }
      sql.append(" ORDER BY inv.criado_em ASC, inv.sku ASC");

      try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
        statement.setLong(1, inventoryId);
        if (status != null) {
          statement.setString(2, status);
        }
        try (ResultSet rs = statement.executeQuery()) {
          List<InvestigationSummary> list = new ArrayList<>();
          while (rs.next()) {
            list.add(mapSummary(rs));
          }
          return list;
        }
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível listar as investigações.", error);
    }
  }

  public InvestigationDetail getDetail(long inventoryId, UUID investigationId, String branchCode) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    try (Connection connection = connect()) {
      requireBranchAndInventory(connection, inventoryId, normalizedBranch);
      return loadDetailInternal(connection, inventoryId, investigationId);
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível carregar os detalhes da investigação.", error);
    }
  }

  public InvestigationDetail create(long inventoryId, String branchCode, CreateCommand command, String actor) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    String safeActor = required(actor, "Usuário autenticado não informado.");

    String causaSuspeita = (command.causaSuspeita() == null || command.causaSuspeita().isBlank())
        ? null : enumValue(command.causaSuspeita(), CAUSES, "Causa suspeita inválida.");

    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        Branch branch = requireBranchAndInventory(connection, inventoryId, normalizedBranch);

        // Carrega e valida apuração
        ApuracaoRecord apuracao = findApuracao(connection, command.apuracaoId(), inventoryId);
        if (apuracao == null) {
          throw new NotFoundException("Apuração não encontrada para este inventário.");
        }

        if ("CONFORME".equalsIgnoreCase(apuracao.estado()) || "CONFORME_APOS_RECONTAGEM".equalsIgnoreCase(apuracao.estado())) {
          throw new ValidationException("Itens conformes não possuem divergência e não podem ser investigados.");
        }

        // Verifica se já existe investigação para a apuração
        if (existsInvestigationForApuracao(connection, apuracao.id())) {
          throw new ConflictException("Já existe uma investigação aberta para esta apuração.");
        }

        UUID id = UUID.randomUUID();
        String insertSql = """
            INSERT INTO investigacoes_divergencia
              (id, filial_id, inventario_id, inventario_item_id, apuracao_id, sku,
               status, causa_suspeita, justificativa, criado_em, criado_por, atualizado_em, atualizado_por, version)
            VALUES (?, ?, ?, ?, ?, ?, 'PENDENTE', ?, ?, now(), ?, now(), ?, 0)
            """;
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
          statement.setObject(1, id);
          statement.setLong(2, branch.id());
          statement.setLong(3, inventoryId);
          statement.setLong(4, apuracao.inventarioItemId());
          statement.setLong(5, apuracao.id());
          statement.setString(6, apuracao.sku());
          statement.setString(7, causaSuspeita);
          statement.setString(8, command.justificativa());
          statement.setString(9, safeActor);
          statement.setString(10, safeActor);
          statement.executeUpdate();
        }

        // Registra evento na timeline
        insertEvent(connection, id, "CRIADA", Map.of(
            "sku", apuracao.sku(),
            "apuracaoId", apuracao.id(),
            "estadoOriginal", apuracao.estado(),
            "diferenca", apuracao.diferenca(),
            "causaSuspeita", causaSuspeita != null ? causaSuspeita : ""
        ), safeActor);

        connection.commit();
        return loadDetailInternal(connection, inventoryId, id);
      } catch (RuntimeException | SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível criar a investigação.", error);
    }
  }

  public InvestigationDetail start(long inventoryId, UUID investigationId, String branchCode, StartCommand command, String actor) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    String safeActor = required(actor, "Usuário autenticado não informado.");

    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        requireBranchAndInventory(connection, inventoryId, normalizedBranch);
        InvestigationRecord record = lockInvestigation(connection, investigationId, inventoryId);

        if (!"PENDENTE".equals(record.status())) {
          throw new ConflictException("Apenas investigações em estado PENDENTE podem ser iniciadas (status atual: " + record.status() + ").");
        }

        String responsavelNome = (command != null && command.responsavelNome() != null && !command.responsavelNome().isBlank())
            ? command.responsavelNome().trim() : safeActor;
        String responsavelId = (command != null && command.responsavelId() != null && !command.responsavelId().isBlank())
            ? command.responsavelId().trim() : safeActor;

        String novaSuspeita = (command != null && command.causaSuspeita() != null && !command.causaSuspeita().isBlank())
            ? enumValue(command.causaSuspeita(), CAUSES, "Causa suspeita inválida.") : record.causaSuspeita();

        String novaJustificativa = (command != null && command.justificativa() != null && !command.justificativa().isBlank())
            ? command.justificativa().trim() : record.justificativa();

        String updateSql = """
            UPDATE investigacoes_divergencia
            SET status = 'EM_INVESTIGACAO',
                responsavel_id = ?,
                responsavel_nome = ?,
                causa_suspeita = ?,
                justificativa = ?,
                atualizado_em = now(),
                atualizado_por = ?,
                version = version + 1
            WHERE id = ? AND version = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
          statement.setString(1, responsavelId);
          statement.setString(2, responsavelNome);
          statement.setString(3, novaSuspeita);
          statement.setString(4, novaJustificativa);
          statement.setString(5, safeActor);
          statement.setObject(6, investigationId);
          statement.setLong(7, record.version());
          if (statement.executeUpdate() != 1) {
            throw new ConflictException("Concorrência detectada: a investigação foi alterada por outro usuário.");
          }
        }

        insertEvent(connection, investigationId, "INICIADA", Map.of(
            "responsavelNome", responsavelNome,
            "causaSuspeita", novaSuspeita != null ? novaSuspeita : ""
        ), safeActor);

        connection.commit();
        return loadDetailInternal(connection, inventoryId, investigationId);
      } catch (RuntimeException | SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível iniciar a investigação.", error);
    }
  }

  public InvestigationDetail updateSuspectCause(
      long inventoryId, UUID investigationId, String branchCode, SuspectCauseCommand command, String actor
  ) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    String safeActor = required(actor, "Usuário autenticado não informado.");
    String causa = enumValue(command.causaSuspeita(), CAUSES, "Causa suspeita inválida.");

    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        requireBranchAndInventory(connection, inventoryId, normalizedBranch);
        InvestigationRecord record = lockInvestigation(connection, investigationId, inventoryId);
        assertNotFinalized(record);

        String updateSql = """
            UPDATE investigacoes_divergencia
            SET causa_suspeita = ?,
                justificativa = COALESCE(?, justificativa),
                atualizado_em = now(),
                atualizado_por = ?,
                version = version + 1
            WHERE id = ? AND version = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
          statement.setString(1, causa);
          statement.setString(2, command.justificativa());
          statement.setString(3, safeActor);
          statement.setObject(4, investigationId);
          statement.setLong(5, record.version());
          if (statement.executeUpdate() != 1) {
            throw new ConflictException("Concorrência detectada: a investigação foi alterada por outro usuário.");
          }
        }

        insertEvent(connection, investigationId, "CAUSA_SUSPEITA_ALTERADA", Map.of(
            "causaSuspeita", causa,
            "justificativa", command.justificativa() != null ? command.justificativa() : ""
        ), safeActor);

        connection.commit();
        return loadDetailInternal(connection, inventoryId, investigationId);
      } catch (RuntimeException | SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível atualizar a causa suspeita.", error);
    }
  }

  public InvestigationDetail changeStatus(
      long inventoryId, UUID investigationId, String branchCode, String targetStatusRaw, String reason, String actor
  ) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    String safeActor = required(actor, "Usuário autenticado não informado.");
    String targetStatus = enumValue(targetStatusRaw, STATUSES, "Status de investigação inválido.");

    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        requireBranchAndInventory(connection, inventoryId, normalizedBranch);
        InvestigationRecord record = lockInvestigation(connection, investigationId, inventoryId);
        assertNotFinalized(record);

        if (!canTransition(record.status(), targetStatus)) {
          throw new ConflictException("Transição de " + record.status() + " para " + targetStatus + " não é permitida.");
        }

        String updateSql = """
            UPDATE investigacoes_divergencia
            SET status = ?,
                atualizado_em = now(),
                atualizado_por = ?,
                version = version + 1
            WHERE id = ? AND version = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
          statement.setString(1, targetStatus);
          statement.setString(2, safeActor);
          statement.setObject(3, investigationId);
          statement.setLong(4, record.version());
          if (statement.executeUpdate() != 1) {
            throw new ConflictException("Concorrência detectada: a investigação foi alterada por outro usuário.");
          }
        }

        insertEvent(connection, investigationId, "STATUS_ALTERADO", Map.of(
            "statusAnterior", record.status(),
            "statusNovo", targetStatus,
            "motivo", reason != null ? reason : ""
        ), safeActor);

        connection.commit();
        return loadDetailInternal(connection, inventoryId, investigationId);
      } catch (RuntimeException | SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível alterar o status da investigação.", error);
    }
  }

  public EvidenceRecord addEvidence(
      long inventoryId, UUID investigationId, String branchCode, AddEvidenceCommand command, String actor
  ) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    String safeActor = required(actor, "Usuário autenticado não informado.");
    String tipo = enumValue(command.tipo(), EVIDENCE_TYPES, "Tipo de evidência inválido.");
    String descricao = required(command.descricao(), "Descrição da evidência não informada.");

    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        requireBranchAndInventory(connection, inventoryId, normalizedBranch);
        InvestigationRecord record = lockInvestigation(connection, investigationId, inventoryId);
        assertNotFinalized(record);

        long evidenceId;
        Instant now = Instant.now();
        String insertSql = """
            INSERT INTO evidencias_investigacao
              (investigacao_id, tipo, descricao, referencia, criado_em, criado_por)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id
            """;
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
          statement.setObject(1, investigationId);
          statement.setString(2, tipo);
          statement.setString(3, descricao);
          statement.setString(4, command.referencia());
          statement.setTimestamp(5, Timestamp.from(now));
          statement.setString(6, safeActor);
          try (ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) throw new SQLException("Falha ao salvar evidência.");
            evidenceId = rs.getLong(1);
          }
        }

        // Se estava AGUARDANDO_EVIDENCIA, transita automaticamente para EM_INVESTIGACAO
        if ("AGUARDANDO_EVIDENCIA".equals(record.status())) {
          try (PreparedStatement update = connection.prepareStatement(
              "UPDATE investigacoes_divergencia SET status = 'EM_INVESTIGACAO', atualizado_em = now(), atualizado_por = ?, version = version + 1 WHERE id = ?")) {
            update.setString(1, safeActor);
            update.setObject(2, investigationId);
            update.executeUpdate();
          }
        }

        insertEvent(connection, investigationId, "EVIDENCIA_ADICIONADA", Map.of(
            "evidenceId", evidenceId,
            "tipo", tipo,
            "descricao", descricao
        ), safeActor);

        connection.commit();
        return new EvidenceRecord(evidenceId, investigationId, tipo, descricao, command.referencia(), now, safeActor);
      } catch (RuntimeException | SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível adicionar a evidência.", error);
    }
  }

  public LinkRecord addLink(
      long inventoryId, UUID investigationId, String branchCode, AddLinkCommand command, String actor
  ) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    String safeActor = required(actor, "Usuário autenticado não informado.");
    String tipoVinculo = enumValue(command.tipoVinculo(), LINK_TYPES, "Tipo de vínculo inválido.");

    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        requireBranchAndInventory(connection, inventoryId, normalizedBranch);
        InvestigationRecord record = lockInvestigation(connection, investigationId, inventoryId);
        assertNotFinalized(record);

        // Valida item relacionado: deve pertencer ao mesmo inventário e filial
        ItemRecord relatedItem = findInventoryItem(connection, command.inventarioItemRelacionadoId(), inventoryId);
        if (relatedItem == null) {
          throw new ValidationException("O item relacionado não pertence a este inventário.");
        }

        if (relatedItem.id() == record.inventarioItemId()) {
          throw new ValidationException("Não é permitido vincular um item a si mesmo.");
        }

        // Se informou apuracaoRelacionadaId, valida
        if (command.apuracaoRelacionadaId() != null) {
          ApuracaoRecord relatedAp = findApuracao(connection, command.apuracaoRelacionadaId(), inventoryId);
          if (relatedAp == null || relatedAp.inventarioItemId() != relatedItem.id()) {
            throw new ValidationException("A apuração relacionada não corresponde ao item informado.");
          }
        }

        long linkId;
        Instant now = Instant.now();
        String insertSql = """
            INSERT INTO investigacao_vinculos
              (investigacao_id, inventario_item_relacionado_id, apuracao_relacionada_id, tipo_vinculo, observacao, criado_em, criado_por)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (investigacao_id, inventario_item_relacionado_id) DO NOTHING
            RETURNING id
            """;
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
          statement.setObject(1, investigationId);
          statement.setLong(2, relatedItem.id());
          if (command.apuracaoRelacionadaId() != null) {
            statement.setLong(3, command.apuracaoRelacionadaId());
          } else {
            statement.setNull(3, java.sql.Types.BIGINT);
          }
          statement.setString(4, tipoVinculo);
          statement.setString(5, command.observacao());
          statement.setTimestamp(6, Timestamp.from(now));
          statement.setString(7, safeActor);
          try (ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
              throw new ConflictException("Este item já foi vinculado à investigação.");
            }
            linkId = rs.getLong(1);
          }
        }

        insertEvent(connection, investigationId, "PRODUTO_RELACIONADO", Map.of(
            "linkId", linkId,
            "skuRelacionado", relatedItem.sku(),
            "tipoVinculo", tipoVinculo,
            "observacao", command.observacao() != null ? command.observacao() : ""
        ), safeActor);

        connection.commit();
        return new LinkRecord(linkId, investigationId, relatedItem.id(), relatedItem.sku(),
            command.apuracaoRelacionadaId(), tipoVinculo, command.observacao(), now, safeActor);
      } catch (RuntimeException | SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível vincular o produto.", error);
    }
  }

  public InvestigationDetail resolve(
      long inventoryId, UUID investigationId, String branchCode, ResolveCommand command, String actor
  ) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    String safeActor = required(actor, "Usuário autenticado não informado.");

    String causa = required(command.causaConfirmada(), "Causa confirmada obrigatória para resolução.");
    String causaConfirmada = enumValue(causa, CAUSES, "Causa confirmada inválida.");
    if ("NAO_IDENTIFICADA".equalsIgnoreCase(causaConfirmada)) {
      throw new ValidationException("Causa confirmada não pode ser NAO_IDENTIFICADA. Para encerramento sem causa, utilize o desfecho apropriado.");
    }
    String conclusao = required(command.conclusao(), "Conclusão descritiva obrigatória para resolução.");
    if (conclusao.length() < 5) {
      throw new ValidationException("Conclusão descritiva deve ter pelo menos 5 caracteres.");
    }

    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        requireBranchAndInventory(connection, inventoryId, normalizedBranch);
        InvestigationRecord record = lockInvestigation(connection, investigationId, inventoryId);
        assertNotFinalized(record);

        String updateSql = """
            UPDATE investigacoes_divergencia
            SET status = 'RESOLVIDA',
                causa_confirmada = ?,
                conclusao = ?,
                resolvido_em = now(),
                resolvido_por = ?,
                atualizado_em = now(),
                atualizado_por = ?,
                version = version + 1
            WHERE id = ? AND version = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
          statement.setString(1, causaConfirmada);
          statement.setString(2, conclusao);
          statement.setString(3, safeActor);
          statement.setString(4, safeActor);
          statement.setObject(5, investigationId);
          statement.setLong(6, record.version());
          if (statement.executeUpdate() != 1) {
            throw new ConflictException("Concorrência detectada: a investigação foi alterada por outro usuário.");
          }
        }

        insertEvent(connection, investigationId, "RESOLVIDA", Map.of(
            "causaConfirmada", causaConfirmada,
            "conclusao", conclusao
        ), safeActor);

        connection.commit();
        return loadDetailInternal(connection, inventoryId, investigationId);
      } catch (RuntimeException | SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível resolver a investigação.", error);
    }
  }

  public InvestigationDetail closeUnresolved(
      long inventoryId, UUID investigationId, String branchCode, UnresolvedCommand command, String actor
  ) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    String safeActor = required(actor, "Usuário autenticado não informado.");
    String conclusao = required(command.conclusao(), "Justificativa obrigatória para encerrar sem causa identificada.");
    if (conclusao.length() < 10) {
      throw new ValidationException("Justificativa para encerramento sem causa deve ter pelo menos 10 caracteres.");
    }

    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        requireBranchAndInventory(connection, inventoryId, normalizedBranch);
        InvestigationRecord record = lockInvestigation(connection, investigationId, inventoryId);
        assertNotFinalized(record);

        String updateSql = """
            UPDATE investigacoes_divergencia
            SET status = 'SEM_CAUSA_IDENTIFICADA',
                causa_confirmada = 'NAO_IDENTIFICADA',
                conclusao = ?,
                resolvido_em = now(),
                resolvido_por = ?,
                atualizado_em = now(),
                atualizado_por = ?,
                version = version + 1
            WHERE id = ? AND version = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
          statement.setString(1, conclusao);
          statement.setString(2, safeActor);
          statement.setString(3, safeActor);
          statement.setObject(4, investigationId);
          statement.setLong(5, record.version());
          if (statement.executeUpdate() != 1) {
            throw new ConflictException("Concorrência detectada: a investigação foi alterada por outro usuário.");
          }
        }

        insertEvent(connection, investigationId, "ENCERRADA_SEM_CAUSA", Map.of(
            "conclusao", conclusao
        ), safeActor);

        connection.commit();
        return loadDetailInternal(connection, inventoryId, investigationId);
      } catch (RuntimeException | SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível encerrar a investigação.", error);
    }
  }

  public InvestigationDetail reopen(
      long inventoryId, UUID investigationId, String branchCode, ReopenCommand command, String actor
  ) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    String safeActor = required(actor, "Usuário autenticado não informado.");
    String justificativa = required(command.justificativa(), "Justificativa obrigatória para reabertura de investigação.");
    if (justificativa.length() < 10) {
      throw new ValidationException("Justificativa para reabertura de investigação deve ter pelo menos 10 caracteres.");
    }

    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        requireBranchAndInventory(connection, inventoryId, normalizedBranch);
        InvestigationRecord record = lockInvestigation(connection, investigationId, inventoryId);

        if (!"RESOLVIDA".equals(record.status()) && !"SEM_CAUSA_IDENTIFICADA".equals(record.status())) {
          throw new ConflictException("Apenas investigações finalizadas podem ser reabertas.");
        }

        String updateSql = """
            UPDATE investigacoes_divergencia
            SET status = 'EM_INVESTIGACAO',
                resolvido_em = NULL,
                resolvido_por = NULL,
                atualizado_em = now(),
                atualizado_por = ?,
                version = version + 1
            WHERE id = ? AND version = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
          statement.setString(1, safeActor);
          statement.setObject(2, investigationId);
          statement.setLong(3, record.version());
          if (statement.executeUpdate() != 1) {
            throw new ConflictException("Concorrência detectada: a investigação foi alterada por outro usuário.");
          }
        }

        insertEvent(connection, investigationId, "REABERTA", Map.of(
            "statusAnterior", record.status(),
            "justificativa", justificativa
        ), safeActor);

        connection.commit();
        return loadDetailInternal(connection, inventoryId, investigationId);
      } catch (RuntimeException | SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível reabrir a investigação.", error);
    }
  }

  private void assertNotFinalized(InvestigationRecord record) {
    if ("RESOLVIDA".equals(record.status()) || "SEM_CAUSA_IDENTIFICADA".equals(record.status())) {
      throw new ConflictException("Investigações finalizadas (" + record.status() + ") não podem sofrer alterações silenciosas. Solicite reabertura explícita.");
    }
  }

  private static boolean canTransition(String current, String target) {
    if (current.equals(target)) return true;
    return switch (current) {
      case "PENDENTE" -> "EM_INVESTIGACAO".equals(target);
      case "EM_INVESTIGACAO" -> "AGUARDANDO_EVIDENCIA".equals(target) || "RESOLVIDA".equals(target) || "SEM_CAUSA_IDENTIFICADA".equals(target);
      case "AGUARDANDO_EVIDENCIA" -> "EM_INVESTIGACAO".equals(target) || "RESOLVIDA".equals(target) || "SEM_CAUSA_IDENTIFICADA".equals(target);
      default -> false;
    };
  }

  private void insertEvent(Connection connection, UUID investigationId, String tipoEvento, Map<String, Object> detalhes, String actor) throws SQLException {
    String sql = """
        INSERT INTO eventos_investigacao (investigacao_id, tipo_evento, detalhes, criado_em, criado_por)
        VALUES (?, ?, ?::jsonb, now(), ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, investigationId);
      statement.setString(2, tipoEvento);
      statement.setString(3, toJson(detalhes));
      statement.setString(4, actor);
      statement.executeUpdate();
    }
  }

  private InvestigationDetail loadDetailInternal(Connection connection, long inventoryId, UUID investigationId) throws SQLException {
    String sql = """
        SELECT inv.id, inv.filial_id, inv.inventario_id, inv.inventario_item_id, inv.apuracao_id,
               inv.sku, inv.status, inv.causa_suspeita, inv.causa_confirmada,
               inv.justificativa, inv.conclusao, inv.responsavel_id, inv.responsavel_nome,
               inv.criado_em, inv.criado_por, inv.atualizado_em, inv.atualizado_por,
               inv.resolvido_em, inv.resolvido_por, inv.version,
               it.descricao_snapshot,
               ap.rodada_id, ap.saldo_snapshot, ap.contado, ap.quantidade_fisica, ap.diferenca, ap.estado AS apuracao_estado,
               ap.detalhes_localizacao_condicao,
               r.numero AS rodada_numero, r.tipo AS rodada_tipo
        FROM investigacoes_divergencia inv
        JOIN inventario_itens it ON it.id = inv.inventario_item_id
        JOIN apuracoes_rodada ap ON ap.id = inv.apuracao_id
        JOIN rodadas_contagem r ON r.id = ap.rodada_id
        WHERE inv.id = ? AND inv.inventario_id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, investigationId);
      statement.setLong(2, inventoryId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          throw new NotFoundException("Investigação não encontrada neste inventário.");
        }

        UUID id = (UUID) rs.getObject("id");
        long filialId = rs.getLong("filial_id");
        long invId = rs.getLong("inventario_id");
        long itemId = rs.getLong("inventario_item_id");
        long apuracaoId = rs.getLong("apuracao_id");
        String sku = rs.getString("sku");
        String status = rs.getString("status");
        String causaSuspeita = rs.getString("causa_suspeita");
        String causaConfirmada = rs.getString("causa_confirmada");
        String justificativa = rs.getString("justificativa");
        String conclusao = rs.getString("conclusao");
        String responsavelId = rs.getString("responsavel_id");
        String responsavelNome = rs.getString("responsavel_nome");
        Instant criadoEm = instant(rs, "criado_em");
        String criadoPor = rs.getString("criado_por");
        Instant atualizadoEm = instant(rs, "atualizado_em");
        String atualizadoPor = rs.getString("atualizado_por");
        Instant resolvidoEm = nullableInstant(rs, "resolvido_em");
        String resolvidoPor = rs.getString("resolvido_por");
        long version = rs.getLong("version");

        String descricao = rs.getString("descricao_snapshot");
        long rodadaId = rs.getLong("rodada_id");
        int rodadaNumero = rs.getInt("rodada_numero");
        String rodadaTipo = rs.getString("rodada_tipo");
        int saldoSnapshot = rs.getInt("saldo_snapshot");
        boolean contado = rs.getBoolean("contado");
        int qFisicaRaw = rs.getInt("quantidade_fisica");
        Integer quantidadeFisica = rs.wasNull() ? null : qFisicaRaw;
        int diffRaw = rs.getInt("diferenca");
        Integer diferenca = rs.wasNull() ? null : diffRaw;
        String apuracaoEstado = rs.getString("apuracao_estado");
        String jsonLoc = rs.getString("detalhes_localizacao_condicao");
        Map<String, Object> detalhesLoc = parseJsonMap(jsonLoc);

        ApuracaoContext apuracaoContext = new ApuracaoContext(
            apuracaoId, rodadaId, rodadaNumero, rodadaTipo, saldoSnapshot,
            contado, quantidadeFisica, diferenca, apuracaoEstado, detalhesLoc
        );

        List<EvidenceRecord> evidencias = loadEvidences(connection, id);
        List<LinkRecord> vinculos = loadLinks(connection, id);
        List<EventRecord> eventos = loadEvents(connection, id);

        return new InvestigationDetail(
            id, filialId, invId, itemId, sku, descricao, status,
            causaSuspeita, causaConfirmada, justificativa, conclusao,
            responsavelId, responsavelNome, criadoEm, criadoPor,
            atualizadoEm, atualizadoPor, resolvidoEm, resolvidoPor, version,
            apuracaoContext, evidencias, vinculos, eventos
        );
      }
    }
  }

  private List<EvidenceRecord> loadEvidences(Connection connection, UUID investigationId) throws SQLException {
    String sql = """
        SELECT id, investigacao_id, tipo, descricao, referencia, criado_em, criado_por
        FROM evidencias_investigacao WHERE investigacao_id = ?
        ORDER BY criado_em ASC, id ASC
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, investigationId);
      try (ResultSet rs = statement.executeQuery()) {
        List<EvidenceRecord> list = new ArrayList<>();
        while (rs.next()) {
          list.add(new EvidenceRecord(
              rs.getLong("id"),
              (UUID) rs.getObject("investigacao_id"),
              rs.getString("tipo"),
              rs.getString("descricao"),
              rs.getString("referencia"),
              instant(rs, "criado_em"),
              rs.getString("criado_por")
          ));
        }
        return list;
      }
    }
  }

  private List<LinkRecord> loadLinks(Connection connection, UUID investigationId) throws SQLException {
    String sql = """
        SELECT v.id, v.investigacao_id, v.inventario_item_relacionado_id, it.sku AS relacionado_sku,
               v.apuracao_relacionada_id, v.tipo_vinculo, v.observacao, v.criado_em, v.criado_por
        FROM investigacao_vinculos v
        JOIN inventario_itens it ON it.id = v.inventario_item_relacionado_id
        WHERE v.investigacao_id = ?
        ORDER BY v.criado_em ASC, v.id ASC
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, investigationId);
      try (ResultSet rs = statement.executeQuery()) {
        List<LinkRecord> list = new ArrayList<>();
        while (rs.next()) {
          long apRaw = rs.getLong("apuracao_relacionada_id");
          Long apuracaoRelId = rs.wasNull() ? null : apRaw;
          list.add(new LinkRecord(
              rs.getLong("id"),
              (UUID) rs.getObject("investigacao_id"),
              rs.getLong("inventario_item_relacionado_id"),
              rs.getString("relacionado_sku"),
              apuracaoRelId,
              rs.getString("tipo_vinculo"),
              rs.getString("observacao"),
              instant(rs, "criado_em"),
              rs.getString("criado_por")
          ));
        }
        return list;
      }
    }
  }

  private List<EventRecord> loadEvents(Connection connection, UUID investigationId) throws SQLException {
    String sql = """
        SELECT id, investigacao_id, tipo_evento, detalhes, criado_em, criado_por
        FROM eventos_investigacao WHERE investigacao_id = ?
        ORDER BY criado_em ASC, id ASC
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, investigationId);
      try (ResultSet rs = statement.executeQuery()) {
        List<EventRecord> list = new ArrayList<>();
        while (rs.next()) {
          list.add(new EventRecord(
              rs.getLong("id"),
              (UUID) rs.getObject("investigacao_id"),
              rs.getString("tipo_evento"),
              parseJsonMap(rs.getString("detalhes")),
              instant(rs, "criado_em"),
              rs.getString("criado_por")
          ));
        }
        return list;
      }
    }
  }

  private InvestigationSummary mapSummary(ResultSet rs) throws SQLException {
    int qFisicaRaw = rs.getInt("quantidade_fisica");
    Integer quantidadeFisica = rs.wasNull() ? null : qFisicaRaw;
    int diffRaw = rs.getInt("diferenca");
    Integer diferenca = rs.wasNull() ? null : diffRaw;

    return new InvestigationSummary(
        (UUID) rs.getObject("id"),
        rs.getLong("filial_id"),
        rs.getLong("inventario_id"),
        rs.getLong("inventario_item_id"),
        rs.getLong("apuracao_id"),
        rs.getString("sku"),
        rs.getString("descricao_snapshot"),
        rs.getString("status"),
        rs.getString("causa_suspeita"),
        rs.getString("causa_confirmada"),
        rs.getString("justificativa"),
        rs.getString("conclusao"),
        rs.getString("responsavel_id"),
        rs.getString("responsavel_nome"),
        rs.getInt("saldo_snapshot"),
        quantidadeFisica,
        diferenca,
        rs.getString("apuracao_estado"),
        rs.getInt("total_evidencias"),
        rs.getInt("total_vinculos"),
        instant(rs, "criado_em"),
        rs.getString("criado_por"),
        instant(rs, "atualizado_em"),
        rs.getString("atualizado_por"),
        nullableInstant(rs, "resolvido_em"),
        rs.getString("resolvido_por"),
        rs.getLong("version")
    );
  }

  private InvestigationRecord lockInvestigation(Connection connection, UUID investigationId, long inventoryId) throws SQLException {
    String sql = """
        SELECT id, filial_id, inventario_id, inventario_item_id, apuracao_id, sku,
               status, causa_suspeita, causa_confirmada, justificativa, conclusao,
               responsavel_id, responsavel_nome, criado_em, criado_por,
               atualizado_em, atualizado_por, resolvido_em, resolvido_por, version
        FROM investigacoes_divergencia
        WHERE id = ? AND inventario_id = ?
        FOR UPDATE
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, investigationId);
      statement.setLong(2, inventoryId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          throw new NotFoundException("Investigação não encontrada neste inventário.");
        }
        return new InvestigationRecord(
            (UUID) rs.getObject("id"),
            rs.getLong("filial_id"),
            rs.getLong("inventario_id"),
            rs.getLong("inventario_item_id"),
            rs.getLong("apuracao_id"),
            rs.getString("sku"),
            rs.getString("status"),
            rs.getString("causa_suspeita"),
            rs.getString("causa_confirmada"),
            rs.getString("justificativa"),
            rs.getString("conclusao"),
            rs.getString("responsavel_id"),
            rs.getString("responsavel_nome"),
            instant(rs, "criado_em"),
            rs.getString("criado_por"),
            instant(rs, "atualizado_em"),
            rs.getString("atualizado_por"),
            nullableInstant(rs, "resolvido_em"),
            rs.getString("resolvido_por"),
            rs.getLong("version")
        );
      }
    }
  }

  private Branch requireBranchAndInventory(Connection connection, long inventoryId, String branchCode) throws SQLException {
    String sql = """
        SELECT i.id, i.filial_id, f.codigo AS filial_codigo
        FROM inventarios i
        JOIN filiais f ON f.id = i.filial_id
        WHERE i.id = ? AND f.codigo = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      statement.setString(2, branchCode);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          throw new NotFoundException("Inventário não encontrado na filial " + branchCode + ".");
        }
        return new Branch(rs.getLong("filial_id"), rs.getString("filial_codigo"));
      }
    }
  }

  private ApuracaoRecord findApuracao(Connection connection, long apuracaoId, long inventoryId) throws SQLException {
    String sql = """
        SELECT ap.id, ap.rodada_id, ap.inventario_item_id, ap.sku, ap.saldo_snapshot,
               ap.contado, ap.quantidade_fisica, ap.diferenca, ap.estado
        FROM apuracoes_rodada ap
        JOIN rodadas_contagem r ON r.id = ap.rodada_id
        WHERE ap.id = ? AND r.inventario_id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, apuracaoId);
      statement.setLong(2, inventoryId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) return null;
        int qFisicaRaw = rs.getInt("quantidade_fisica");
        Integer qFisica = rs.wasNull() ? null : qFisicaRaw;
        int diffRaw = rs.getInt("diferenca");
        Integer diff = rs.wasNull() ? null : diffRaw;
        return new ApuracaoRecord(
            rs.getLong("id"),
            rs.getLong("rodada_id"),
            rs.getLong("inventario_item_id"),
            rs.getString("sku"),
            rs.getInt("saldo_snapshot"),
            rs.getBoolean("contado"),
            qFisica,
            diff,
            rs.getString("estado")
        );
      }
    }
  }

  private ItemRecord findInventoryItem(Connection connection, long itemId, long inventoryId) throws SQLException {
    String sql = "SELECT id, sku FROM inventario_itens WHERE id = ? AND inventario_id = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, itemId);
      statement.setLong(2, inventoryId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) return null;
        return new ItemRecord(rs.getLong("id"), rs.getString("sku"));
      }
    }
  }

  private boolean existsInvestigationForApuracao(Connection connection, long apuracaoId) throws SQLException {
    String sql = "SELECT 1 FROM investigacoes_divergencia WHERE apuracao_id = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, apuracaoId);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    }
  }

  private Connection connect() throws SQLException {
    return database.username().isBlank()
        ? DriverManager.getConnection(database.url())
        : DriverManager.getConnection(database.url(), database.username(), database.password());
  }

  private String toJson(Object value) {
    if (value == null) return null;
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      return "{}";
    }
  }

  private Map<String, Object> parseJsonMap(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception ex) {
      return Map.of();
    }
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    Timestamp ts = result.getTimestamp(column);
    return ts != null ? ts.toInstant() : null;
  }

  private static Instant nullableInstant(ResultSet result, String column) throws SQLException {
    Timestamp value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String normalizeBranchCode(String value) {
    String normalized = value == null ? "" : value.trim();
    if (!normalized.matches("\\d{1,20}")) throw new ValidationException("Código de filial inválido.");
    return normalized;
  }

  private static String required(String value, String message) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isBlank()) throw new ValidationException(message);
    return normalized;
  }

  private static String enumValue(String value, Set<String> allowed, String message) {
    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (!allowed.contains(normalized)) throw new ValidationException(message);
    return normalized;
  }

  // DTOs & Records
  public record CreateCommand(long apuracaoId, String causaSuspeita, String justificativa) {}
  public record StartCommand(String responsavelId, String responsavelNome, String causaSuspeita, String justificativa) {}
  public record SuspectCauseCommand(String causaSuspeita, String justificativa) {}
  public record AddEvidenceCommand(String tipo, String descricao, String referencia) {}
  public record AddLinkCommand(long inventarioItemRelacionadoId, Long apuracaoRelacionadaId, String tipoVinculo, String observacao) {}
  public record ResolveCommand(String causaConfirmada, String conclusao) {}
  public record UnresolvedCommand(String conclusao) {}
  public record ReopenCommand(String justificativa) {}

  public record InvestigationSummary(
      UUID id, long filialId, long inventarioId, long inventarioItemId, long apuracaoId,
      String sku, String descricaoSnapshot, String status, String causaSuspeita, String causaConfirmada,
      String justificativa, String conclusao, String responsavelId, String responsavelNome,
      int saldoSnapshot, Integer quantidadeFisica, Integer diferenca, String apuracaoEstado,
      int totalEvidencias, int totalVinculos,
      Instant criadoEm, String criadoPor, Instant atualizadoEm, String atualizadoPor,
      Instant resolvidoEm, String resolvidoPor, long version
  ) {}

  public record ApuracaoContext(
      long apuracaoId, long rodadaId, int rodadaNumero, String rodadaTipo, int saldoSnapshot,
      boolean contado, Integer quantidadeFisica, Integer diferenca, String estado,
      Map<String, Object> detalhesLocalizacaoCondicao
  ) {}

  public record EvidenceRecord(
      long id, UUID investigacaoId, String tipo, String descricao, String referencia,
      Instant criadoEm, String criadoPor
  ) {}

  public record LinkRecord(
      long id, UUID investigacaoId, long inventarioItemRelacionadoId, String relacionadoSku,
      Long apuracaoRelacionadaId, String tipoVinculo, String observacao, Instant criadoEm, String criadoPor
  ) {}

  public record EventRecord(
      long id, UUID investigacaoId, String tipoEvento, Map<String, Object> detalhes,
      Instant criadoEm, String criadoPor
  ) {}

  public record InvestigationDetail(
      UUID id, long filialId, long inventarioId, long inventarioItemId,
      String sku, String descricaoSnapshot, String status,
      String causaSuspeita, String causaConfirmada, String justificativa, String conclusao,
      String responsavelId, String responsavelNome, Instant criadoEm, String criadoPor,
      Instant atualizadoEm, String atualizadoPor, Instant resolvidoEm, String resolvidoPor, long version,
      ApuracaoContext apuracao, List<EvidenceRecord> evidencias, List<LinkRecord> vinculos, List<EventRecord> eventos
  ) {}

  private record Branch(long id, String code) {}
  private record ItemRecord(long id, String sku) {}
  private record ApuracaoRecord(long id, long rodadaId, long inventarioItemId, String sku,
                                int saldoSnapshot, boolean contado, Integer quantidadeFisica,
                                Integer diferenca, String estado) {}
  private record InvestigationRecord(
      UUID id, long filialId, long inventarioId, long inventarioItemId, long apuracaoId,
      String sku, String status, String causaSuspeita, String causaConfirmada,
      String justificativa, String conclusao, String responsavelId, String responsavelNome,
      Instant criadoEm, String criadoPor, Instant atualizadoEm, String atualizadoPor,
      Instant resolvidoEm, String resolvidoPor, long version
  ) {}

  // Exceções
  public static class InvestigationException extends RuntimeException {
    private final int status;
    InvestigationException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
  }

  public static final class ValidationException extends InvestigationException {
    ValidationException(String message) { super(400, message); }
  }

  public static final class NotFoundException extends InvestigationException {
    NotFoundException(String message) { super(404, message); }
  }

  public static final class ConflictException extends InvestigationException {
    ConflictException(String message) { super(409, message); }
  }

  public static final class DatabaseException extends InvestigationException {
    DatabaseException(String message, Throwable cause) { super(503, message); initCause(cause); }
  }
}
