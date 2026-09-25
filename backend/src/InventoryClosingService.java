package br.com.mncheck;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
public class InventoryClosingService {

  public static final Set<String> CLOSING_TYPES = Set.of("NORMAL", "EXCEPCIONAL");

  private final DatabaseUrlParser.JdbcConfig database;
  private final ObjectMapper objectMapper;

  public InventoryClosingService() {
    this(System.getenv("DATABASE_URL"), new ObjectMapper());
  }

  InventoryClosingService(String databaseUrl) {
    this(databaseUrl, new ObjectMapper());
  }

  InventoryClosingService(String databaseUrl, ObjectMapper objectMapper) {
    this.database = DatabaseUrlParser.parse(databaseUrl);
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
  }

  public ValidationResult validateClosing(long inventoryId, String branchCode) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    try (Connection connection = connect()) {
      Branch branch = requireBranch(connection, normalizedBranch);
      InventoryRecord inventory = requireInventory(connection, inventoryId, branch.id());
      ValidationResult internal = validateClosingInternal(connection, inventoryId, normalizedBranch);

      boolean isProtected = InventorySecurityPolicy.isInventoryProtected(inventory.modo(), inventory.status());
      if (!isProtected) {
        return internal;
      }

      SummaryMetrics safeSummary = new SummaryMetrics(
          internal.resumo().totalItens(),
          null,
          null,
          null,
          null,
          null,
          null
      );

      List<Pendency> safePendencias = new ArrayList<>();
      for (Pendency p : internal.pendencias()) {
        String safeMsg = switch (p.codigo()) {
          case "DIVERGENCIA_SEM_INVESTIGACAO" -> "Existem divergências confirmadas sem investigação associada.";
          case "INVESTIGACOES_PENDENTES" -> "Existem investigações não concluídas. Todas devem ser resolvidas ou encerradas sem causa.";
          case "ITENS_NAO_CONTADOS" -> "Existem itens não contados no inventário.";
          default -> p.mensagem();
        };
        safePendencias.add(new Pendency(p.codigo(), safeMsg, p.bloqueante()));
      }

      return new ValidationResult(internal.podeFechar(), safePendencias, safeSummary);
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível validar o fechamento do inventário.", error);
    }
  }

  private ValidationResult validateClosingInternal(Connection connection, long inventoryId, String branchCode) throws SQLException {
    Branch branch = requireBranch(connection, branchCode);
    InventoryRecord inventory = requireInventory(connection, inventoryId, branch.id());

    List<Pendency> pendencias = new ArrayList<>();

    // 1. Status do inventário
    if ("ENCERRADO".equalsIgnoreCase(inventory.status()) || "FINALIZADO".equalsIgnoreCase(inventory.status())) {
      pendencias.add(new Pendency("INVENTARIO_JA_ENCERRADO", "O inventário já foi encerrado e é puramente histórico.", true));
      return new ValidationResult(false, pendencias, buildEmptySummary());
    }
    if ("CANCELADO".equalsIgnoreCase(inventory.status())) {
      pendencias.add(new Pendency("INVENTARIO_CANCELADO", "O inventário está cancelado e não pode ser encerrado.", true));
      return new ValidationResult(false, pendencias, buildEmptySummary());
    }
    if ("RASCUNHO".equalsIgnoreCase(inventory.status()) || "ABERTO".equalsIgnoreCase(inventory.status())) {
      pendencias.add(new Pendency("STATUS_INVALIDO", "O inventário ainda não iniciou a contagem.", true));
      return new ValidationResult(false, pendencias, buildEmptySummary());
    }

    // 2. Rodadas ativas
    List<RoundRecord> rounds = loadRounds(connection, inventoryId);
    for (RoundRecord round : rounds) {
      if ("EM_ANDAMENTO".equalsIgnoreCase(round.status())) {
        pendencias.add(new Pendency(
            "RODADA_ATIVA",
            "A Rodada " + round.numero() + " (" + round.tipo() + ") ainda está em andamento. Encerre-a antes do fechamento.",
            true
        ));
      }
    }

    // 3. Rodada 1 finalizada e apurada
    RoundRecord r1 = findRoundByNumber(rounds, 1);
    if (r1 == null || !"FINALIZADA".equalsIgnoreCase(r1.status())) {
      pendencias.add(new Pendency("SEM_APURACAO_R1", "A Rodada 1 não foi finalizada e apurada.", true));
    }

    // 4. Rodada 2 (se existir, deve estar finalizada)
    RoundRecord r2 = findRoundByNumber(rounds, 2);
    if (r2 != null && !"FINALIZADA".equalsIgnoreCase(r2.status())) {
      pendencias.add(new Pendency("RODADA_2_PENDENTE", "A Rodada 2 foi iniciada mas não está finalizada.", true));
    }

    // 5. Itens e Apurações
    List<ItemRecord> items = loadItems(connection, inventoryId);
    Map<Long, ApuracaoRecord> r1Apuracoes = r1 != null ? loadApuracoes(connection, r1.id()) : Map.of();
    Map<Long, ApuracaoRecord> r2Apuracoes = r2 != null ? loadApuracoes(connection, r2.id()) : Map.of();
    Map<Long, InvestigationSummaryRecord> investigations = loadInvestigations(connection, inventoryId);

    int conformes = 0;
    int divergenciasConfirmadas = 0;
    int naoContados = 0;
    int invResolvidas = 0;
    int invSemCausa = 0;
    int invPendentes = 0;

    for (InvestigationSummaryRecord inv : investigations.values()) {
      if ("RESOLVIDA".equalsIgnoreCase(inv.status())) {
        invResolvidas++;
      } else if ("SEM_CAUSA_IDENTIFICADA".equalsIgnoreCase(inv.status())) {
        invSemCausa++;
      } else {
        invPendentes++;
      }
    }

    List<String> skusDivergentesSemInvestigacao = new ArrayList<>();

    for (ItemRecord item : items) {
      ApuracaoRecord r1Ap = r1Apuracoes.get(item.id());
      ApuracaoRecord r2Ap = r2Apuracoes.get(item.id());

      Integer qFisicaFinal;
      if (r2Ap != null) {
        qFisicaFinal = r2Ap.contado() ? r2Ap.quantidadeFisica() : null;
      } else if (r1Ap != null) {
        qFisicaFinal = r1Ap.contado() ? r1Ap.quantidadeFisica() : null;
      } else {
        qFisicaFinal = null;
      }

      if (qFisicaFinal == null) {
        naoContados++;
      } else {
        int diff = qFisicaFinal - item.saldoSnapshot();
        if (diff == 0) {
          conformes++;
        } else {
          divergenciasConfirmadas++;
          // Verifica se possui investigação
          if (!investigations.containsKey(item.id())) {
            skusDivergentesSemInvestigacao.add(item.sku());
          }
        }
      }
    }

    // Validação de divergências sem investigação
    if (!skusDivergentesSemInvestigacao.isEmpty()) {
      pendencias.add(new Pendency(
          "DIVERGENCIA_SEM_INVESTIGACAO",
          "Existem " + skusDivergentesSemInvestigacao.size() + " divergência(s) confirmada(s) sem investigação associada (ex: SKU "
              + skusDivergentesSemInvestigacao.get(0) + ").",
          true
      ));
    }

    // Validação de investigações pendentes
    if (invPendentes > 0) {
      pendencias.add(new Pendency(
          "INVESTIGACOES_PENDENTES",
          "Existem " + invPendentes + " investigação(ões) não concluída(s). Todas devem ser resolvidas ou encerradas sem causa.",
          true
      ));
    }

    // Validação de itens não contados
    if (naoContados > 0) {
      pendencias.add(new Pendency(
          "ITENS_NAO_CONTADOS",
          "Existem " + naoContados + " item(ns) não contado(s) no inventário.",
          true
      ));
    }

    SummaryMetrics summary = new SummaryMetrics(
        items.size(),
        conformes,
        divergenciasConfirmadas,
        invResolvidas,
        invSemCausa,
        invPendentes,
        naoContados
    );

    boolean podeFechar = pendencias.isEmpty();
    return new ValidationResult(podeFechar, pendencias, summary);
  }

  public ClosingResult closeInventory(
      long inventoryId,
      String branchCode,
      CloseCommand command,
      LegacyAuthenticationClient.AuthenticatedUser user
  ) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    if (user == null || user.name() == null || user.name().isBlank()) {
      throw new ValidationException("Usuário autenticado não identificado.");
    }

    String tipo = enumValue(command.tipo(), CLOSING_TYPES, "Tipo de fechamento inválido. Deve ser NORMAL ou EXCEPCIONAL.");
    String justificativa = command.justificativa() == null ? "" : command.justificativa().trim();

    if ("EXCEPCIONAL".equals(tipo)) {
      if (!"admin".equalsIgnoreCase(user.role())) {
        throw new ForbiddenException("Apenas administradores podem realizar fechamento excepcional.");
      }
      if (justificativa.length() < 15) {
        throw new ValidationException("Justificativa obrigatória (mínimo 15 caracteres) para fechamento excepcional.");
      }
    }

    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        Branch branch = requireBranch(connection, normalizedBranch);
        InventoryRecord inventory = lockInventoryForUpdate(connection, inventoryId, branch.id());

        // Idempotência: se já encerrado, retorna resultado consolidado
        if ("ENCERRADO".equalsIgnoreCase(inventory.status()) || "FINALIZADO".equalsIgnoreCase(inventory.status())) {
          ClosingResult existing = loadClosingResult(connection, inventoryId, branch.id());
          if (existing != null) {
            connection.rollback();
            return existing;
          }
          throw new ConflictException("Inventário já está " + inventory.status() + ".");
        }

        if (!Set.of("EM_CONTAGEM", "EM_RECONTAGEM", "EM_INVESTIGACAO").contains(inventory.status().toUpperCase(Locale.ROOT))) {
          throw new ConflictException("Inventário não está em estado que permita fechamento (status atual: " + inventory.status() + ").");
        }

        // Validação de pré-requisitos para fechamento NORMAL
        ValidationResult validation = validateClosingInternal(connection, inventoryId, normalizedBranch);
        if ("NORMAL".equals(tipo) && !validation.podeFechar()) {
          StringBuilder msg = new StringBuilder("Fechamento normal bloqueado devido a pendências:");
          for (Pendency p : validation.pendencias()) {
            msg.append(" [").append(p.codigo()).append(": ").append(p.mensagem()).append("]");
          }
          throw new ConflictException(msg.toString());
        }

        // Carregar dados para cálculo consolidado
        List<ItemRecord> items = loadItems(connection, inventoryId);
        List<RoundRecord> rounds = loadRounds(connection, inventoryId);
        RoundRecord r1 = findRoundByNumber(rounds, 1);
        RoundRecord r2 = findRoundByNumber(rounds, 2);

        Map<Long, ApuracaoRecord> r1Apuracoes = r1 != null ? loadApuracoes(connection, r1.id()) : Map.of();
        Map<Long, ApuracaoRecord> r2Apuracoes = r2 != null ? loadApuracoes(connection, r2.id()) : Map.of();
        Map<Long, InvestigationSummaryRecord> investigations = loadInvestigations(connection, inventoryId);

        int totalItens = items.size();
        int totalUnidadesSnapshot = 0;
        int itensConformesR1 = 0;
        int itensEnviadosR2 = r2Apuracoes.size();
        int itensConformesAposR2 = 0;
        int divergenciasConfirmadas = 0;
        int itensNaoContados = 0;
        int itensComFalta = 0;
        int itensComSobra = 0;
        int quantidadeFalta = 0;
        int quantidadeSobra = 0;

        List<ItemSnapshotToInsert> itemsToInsert = new ArrayList<>();

        for (ItemRecord item : items) {
          totalUnidadesSnapshot += item.saldoSnapshot();

          ApuracaoRecord r1Ap = r1Apuracoes.get(item.id());
          ApuracaoRecord r2Ap = r2Apuracoes.get(item.id());

          Integer r1Fisico = (r1Ap != null && r1Ap.contado()) ? r1Ap.quantidadeFisica() : null;
          Integer r1Diferenca = (r1Ap != null && r1Ap.contado()) ? r1Ap.diferenca() : null;
          String r1Estado = r1Ap != null ? r1Ap.estado() : "NAO_CONTADO";
          if ("CONFORME".equalsIgnoreCase(r1Estado)) {
            itensConformesR1++;
          }

          boolean houveR2 = (r2Ap != null);
          Integer r2Fisico = (r2Ap != null && r2Ap.contado()) ? r2Ap.quantidadeFisica() : null;
          Integer r2Diferenca = (r2Ap != null && r2Ap.contado()) ? r2Ap.diferenca() : null;
          String r2Estado = r2Ap != null ? r2Ap.estado() : null;

          // Cálculo da quantidade física final:
          // Se houve R2: prioridade é R2
          // Se não houve R2: prioridade é R1
          // Se não foi contado: NULL (NÃO inventar 0)
          Integer qtdFisicaFinal;
          if (houveR2) {
            qtdFisicaFinal = r2Fisico;
          } else {
            qtdFisicaFinal = r1Fisico;
          }

          Integer diferencaFinal;
          String estadoFinal;
          String tipoDivergencia;

          if (qtdFisicaFinal == null) {
            diferencaFinal = null;
            estadoFinal = "NAO_CONTADO";
            tipoDivergencia = "NAO_CONTADO";
            itensNaoContados++;
          } else {
            diferencaFinal = qtdFisicaFinal - item.saldoSnapshot();
            if (diferencaFinal == 0) {
              if (houveR2) {
                estadoFinal = "CONFORME_APOS_RECONTAGEM";
                itensConformesAposR2++;
              } else {
                estadoFinal = "CONFORME";
              }
              tipoDivergencia = "CONFORME";
            } else {
              estadoFinal = "DIVERGENCIA_CONFIRMADA";
              divergenciasConfirmadas++;
              if (diferencaFinal < 0) {
                tipoDivergencia = "FALTA";
                itensComFalta++;
                quantidadeFalta += Math.abs(diferencaFinal);
              } else {
                tipoDivergencia = "SOBRA";
                itensComSobra++;
                quantidadeSobra += diferencaFinal;
              }
            }
          }

          InvestigationSummaryRecord inv = investigations.get(item.id());
          UUID investigacaoId = inv != null ? inv.id() : null;
          String statusInvestigacao = inv != null ? inv.status() : null;
          String causaConfirmada = inv != null ? inv.causaConfirmada() : null;
          String conclusao = inv != null ? inv.conclusao() : null;

          String detalhesLocCond = null;
          if (r2Ap != null && r2Ap.detalhesJson() != null) {
            detalhesLocCond = r2Ap.detalhesJson();
          } else if (r1Ap != null && r1Ap.detalhesJson() != null) {
            detalhesLocCond = r1Ap.detalhesJson();
          }

          itemsToInsert.add(new ItemSnapshotToInsert(
              item.id(),
              item.sku(),
              item.descricaoSnapshot(),
              item.saldoSnapshot(),
              r1Fisico,
              r1Diferenca,
              r1Estado,
              houveR2,
              r2Fisico,
              r2Diferenca,
              r2Estado,
              qtdFisicaFinal,
              diferencaFinal,
              estadoFinal,
              tipoDivergencia,
              investigacaoId,
              statusInvestigacao,
              causaConfirmada,
              conclusao,
              detalhesLocCond
          ));
        }

        // Métricas de investigação
        int invResolvidas = 0;
        int invSemCausa = 0;
        int invPendentes = 0;
        for (InvestigationSummaryRecord inv : investigations.values()) {
          if ("RESOLVIDA".equalsIgnoreCase(inv.status())) {
            invResolvidas++;
          } else if ("SEM_CAUSA_IDENTIFICADA".equalsIgnoreCase(inv.status())) {
            invSemCausa++;
          } else {
            invPendentes++;
          }
        }

        Instant fechadoEm = Instant.now();
        Instant inicioEm = inventory.abertoEm() != null ? inventory.abertoEm() : inventory.criadoEm();
        Long duracaoSegundos = inicioEm != null ? Math.max(0, Duration.between(inicioEm, fechadoEm).getSeconds()) : null;

        String pendenciasJson = "EXCEPCIONAL".equals(tipo) ? toJson(validation.pendencias()) : null;

        // Inserir resultados_inventario
        String insertResultadoSql = """
            INSERT INTO resultados_inventario (
              inventario_id, filial_id, total_itens, total_unidades_snapshot,
              itens_conformes_r1, itens_enviados_r2, itens_conformes_apos_r2,
              divergencias_confirmadas, investigacoes_resolvidas, investigacoes_sem_causa,
              investigacoes_pendentes, itens_nao_contados, itens_com_falta, itens_com_sobra,
              quantidade_falta, quantidade_sobra, inicio_em, fechado_em, duracao_segundos,
              tipo_fechamento, justificativa_excepcional, pendencias_snapshot,
              fechado_por_id, fechado_por_nome, criado_em
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, now())
            RETURNING id
            """;

        long resultadoId;
        try (PreparedStatement statement = connection.prepareStatement(insertResultadoSql)) {
          int idx = 1;
          statement.setLong(idx++, inventoryId);
          statement.setLong(idx++, branch.id());
          statement.setInt(idx++, totalItens);
          statement.setInt(idx++, totalUnidadesSnapshot);
          statement.setInt(idx++, itensConformesR1);
          statement.setInt(idx++, itensEnviadosR2);
          statement.setInt(idx++, itensConformesAposR2);
          statement.setInt(idx++, divergenciasConfirmadas);
          statement.setInt(idx++, invResolvidas);
          statement.setInt(idx++, invSemCausa);
          statement.setInt(idx++, invPendentes);
          statement.setInt(idx++, itensNaoContados);
          statement.setInt(idx++, itensComFalta);
          statement.setInt(idx++, itensComSobra);
          statement.setInt(idx++, quantidadeFalta);
          statement.setInt(idx++, quantidadeSobra);
          statement.setTimestamp(idx++, inicioEm != null ? Timestamp.from(inicioEm) : null);
          statement.setTimestamp(idx++, Timestamp.from(fechadoEm));
          if (duracaoSegundos != null) statement.setLong(idx++, duracaoSegundos);
          else statement.setNull(idx++, java.sql.Types.BIGINT);
          statement.setString(idx++, tipo);
          statement.setString(idx++, "EXCEPCIONAL".equals(tipo) ? justificativa : null);
          statement.setString(idx++, pendenciasJson);
          statement.setString(idx++, user.id());
          statement.setString(idx++, user.name());

          try (ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) throw new SQLException("Falha ao gerar resultado de inventário.");
            resultadoId = rs.getLong(1);
          }
        }

        // Inserir resultado_inventario_itens em batch
        String insertItemSql = """
            INSERT INTO resultado_inventario_itens (
              resultado_inventario_id, inventario_id, filial_id, inventario_item_id, sku,
              descricao, saldo_snapshot, r1_fisico, r1_diferenca, r1_estado,
              houve_r2, r2_fisico, r2_diferenca, r2_estado, quantidade_fisica_final,
              diferenca_final, estado_final, tipo_divergencia, investigacao_id,
              status_investigacao, causa_confirmada, conclusao, detalhes_localizacao_condicao, criado_em
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
            """;

        try (PreparedStatement statement = connection.prepareStatement(insertItemSql)) {
          for (ItemSnapshotToInsert it : itemsToInsert) {
            int idx = 1;
            statement.setLong(idx++, resultadoId);
            statement.setLong(idx++, inventoryId);
            statement.setLong(idx++, branch.id());
            statement.setLong(idx++, it.itemId());
            statement.setString(idx++, it.sku());
            statement.setString(idx++, it.descricao());
            statement.setInt(idx++, it.saldoSnapshot());

            if (it.r1Fisico() != null) statement.setInt(idx++, it.r1Fisico());
            else statement.setNull(idx++, java.sql.Types.INTEGER);

            if (it.r1Diferenca() != null) statement.setInt(idx++, it.r1Diferenca());
            else statement.setNull(idx++, java.sql.Types.INTEGER);

            statement.setString(idx++, it.r1Estado());
            statement.setBoolean(idx++, it.houveR2());

            if (it.r2Fisico() != null) statement.setInt(idx++, it.r2Fisico());
            else statement.setNull(idx++, java.sql.Types.INTEGER);

            if (it.r2Diferenca() != null) statement.setInt(idx++, it.r2Diferenca());
            else statement.setNull(idx++, java.sql.Types.INTEGER);

            statement.setString(idx++, it.r2Estado());

            if (it.quantidadeFisicaFinal() != null) statement.setInt(idx++, it.quantidadeFisicaFinal());
            else statement.setNull(idx++, java.sql.Types.INTEGER);

            if (it.diferencaFinal() != null) statement.setInt(idx++, it.diferencaFinal());
            else statement.setNull(idx++, java.sql.Types.INTEGER);

            statement.setString(idx++, it.estadoFinal());
            statement.setString(idx++, it.tipoDivergencia());

            if (it.investigacaoId() != null) statement.setObject(idx++, it.investigacaoId());
            else statement.setNull(idx++, java.sql.Types.OTHER);

            statement.setString(idx++, it.statusInvestigacao());
            statement.setString(idx++, it.causaConfirmada());
            statement.setString(idx++, it.conclusao());
            statement.setString(idx++, it.detalhesLocalizacaoCondicao());

            statement.addBatch();
          }
          statement.executeBatch();
        }

        // Registrar evento auditável
        Map<String, Object> eventoDetalhes = Map.of(
            "tipoFechamento", tipo,
            "justificativa", justificativa,
            "totalItens", totalItens,
            "divergenciasConfirmadas", divergenciasConfirmadas,
            "quantidadeFalta", quantidadeFalta,
            "quantidadeSobra", quantidadeSobra
        );
        insertInventoryEvent(connection, inventoryId, branch.id(), "INVENTARIO_ENCERRADO", eventoDetalhes, user.name());

        // Atualizar tabela inventarios
        String updateInvSql = """
            UPDATE inventarios
            SET status = 'ENCERRADO',
                encerrado_por = ?,
                encerrado_em = now(),
                tipo_fechamento = ?,
                justificativa_fechamento = ?,
                version = version + 1
            WHERE id = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(updateInvSql)) {
          statement.setString(1, user.name());
          statement.setString(2, tipo);
          statement.setString(3, "EXCEPCIONAL".equals(tipo) ? justificativa : null);
          statement.setLong(4, inventoryId);
          statement.executeUpdate();
        }

        connection.commit();
        return loadClosingResult(connection, inventoryId, branch.id());
      } catch (RuntimeException | SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível realizar o fechamento do inventário.", error);
    }
  }

  public ClosingResult getResult(long inventoryId, String branchCode) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    try (Connection connection = connect()) {
      Branch branch = requireBranch(connection, normalizedBranch);
      InventoryRecord inventory = requireInventory(connection, inventoryId, branch.id());
      if (!"ENCERRADO".equalsIgnoreCase(inventory.status())) {
        throw new ConflictException("Resultado só pode ser consultado após o encerramento do inventário.");
      }
      ClosingResult result = loadClosingResult(connection, inventoryId, branch.id());
      if (result == null) {
        throw new NotFoundException("Resultado consolidado não encontrado para este inventário.");
      }
      return result;
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível carregar o resultado consolidado.", error);
    }
  }

  public DetailedHistoryResult getDetailedHistory(long inventoryId, String branchCode) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    try (Connection connection = connect()) {
      Branch branch = requireBranch(connection, normalizedBranch);
      InventoryRecord inventory = requireInventory(connection, inventoryId, branch.id());
      if (!"ENCERRADO".equalsIgnoreCase(inventory.status())) {
        throw new ConflictException("Histórico detalhado só pode ser consultado após o encerramento do inventário.");
      }
      ClosingResult result = loadClosingResult(connection, inventoryId, branch.id());
      if (result == null) {
        throw new NotFoundException("Histórico não encontrado para este inventário. O inventário pode não ter sido encerrado.");
      }

      List<ItemAuditRecord> items = loadResultItems(connection, result.id());
      List<EventHistoryRecord> events = loadInventoryEvents(connection, inventoryId);

      return new DetailedHistoryResult(result, items, events);
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível carregar o histórico detalhado.", error);
    }
  }

  private ClosingResult loadClosingResult(Connection connection, long inventoryId, long branchId) throws SQLException {
    String sql = """
        SELECT r.id, r.inventario_id, r.filial_id, f.codigo AS filial_codigo,
               i.nome AS inventario_nome, i.tipo AS inventario_tipo, i.modo AS inventario_modo,
               i.status AS inventario_status,
               r.total_itens, r.total_unidades_snapshot, r.itens_conformes_r1,
               r.itens_enviados_r2, r.itens_conformes_apos_r2, r.divergencias_confirmadas,
               r.investigacoes_resolvidas, r.investigacoes_sem_causa, r.investigacoes_pendentes,
               r.itens_nao_contados, r.itens_com_falta, r.itens_com_sobra,
               r.quantidade_falta, r.quantidade_sobra, r.inicio_em, r.fechado_em,
               r.duracao_segundos, r.tipo_fechamento, r.justificativa_excepcional,
               r.pendencias_snapshot, r.fechado_por_id, r.fechado_por_nome, r.criado_em
        FROM resultados_inventario r
        JOIN inventarios i ON i.id = r.inventario_id
        JOIN filiais f ON f.id = r.filial_id
        WHERE r.inventario_id = ? AND r.filial_id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      statement.setLong(2, branchId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) return null;
        return new ClosingResult(
            rs.getLong("id"),
            rs.getLong("inventario_id"),
            rs.getLong("filial_id"),
            rs.getString("filial_codigo"),
            rs.getString("inventario_nome"),
            rs.getString("inventario_tipo"),
            rs.getString("inventario_modo"),
            rs.getString("inventario_status"),
            rs.getInt("total_itens"),
            rs.getInt("total_unidades_snapshot"),
            rs.getInt("itens_conformes_r1"),
            rs.getInt("itens_enviados_r2"),
            rs.getInt("itens_conformes_apos_r2"),
            rs.getInt("divergencias_confirmadas"),
            rs.getInt("investigacoes_resolvidas"),
            rs.getInt("investigacoes_sem_causa"),
            rs.getInt("investigacoes_pendentes"),
            rs.getInt("itens_nao_contados"),
            rs.getInt("itens_com_falta"),
            rs.getInt("itens_com_sobra"),
            rs.getInt("quantidade_falta"),
            rs.getInt("quantidade_sobra"),
            nullableInstant(rs, "inicio_em"),
            instant(rs, "fechado_em"),
            rs.getObject("duracao_segundos") != null ? rs.getLong("duracao_segundos") : null,
            rs.getString("tipo_fechamento"),
            rs.getString("justificativa_excepcional"),
            parseJsonList(rs.getString("pendencias_snapshot")),
            rs.getString("fechado_por_id"),
            rs.getString("fechado_por_nome"),
            instant(rs, "criado_em")
        );
      }
    }
  }

  private List<ItemAuditRecord> loadResultItems(Connection connection, long resultadoId) throws SQLException {
    String sql = """
        SELECT id, resultado_inventario_id, inventario_id, filial_id, inventario_item_id,
               sku, descricao, saldo_snapshot, r1_fisico, r1_diferenca, r1_estado,
               houve_r2, r2_fisico, r2_diferenca, r2_estado, quantidade_fisica_final,
               diferenca_final, estado_final, tipo_divergencia, investigacao_id,
               status_investigacao, causa_confirmada, conclusao, detalhes_localizacao_condicao, criado_em
        FROM resultado_inventario_itens
        WHERE resultado_inventario_id = ?
        ORDER BY sku ASC
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, resultadoId);
      try (ResultSet rs = statement.executeQuery()) {
        List<ItemAuditRecord> list = new ArrayList<>();
        while (rs.next()) {
          int r1Fis = rs.getInt("r1_fisico");
          Integer r1Fisico = rs.wasNull() ? null : r1Fis;

          int r1Diff = rs.getInt("r1_diferenca");
          Integer r1Diferenca = rs.wasNull() ? null : r1Diff;

          int r2Fis = rs.getInt("r2_fisico");
          Integer r2Fisico = rs.wasNull() ? null : r2Fis;

          int r2Diff = rs.getInt("r2_diferenca");
          Integer r2Diferenca = rs.wasNull() ? null : r2Diff;

          int qFinal = rs.getInt("quantidade_fisica_final");
          Integer quantidadeFisicaFinal = rs.wasNull() ? null : qFinal;

          int dFinal = rs.getInt("diferenca_final");
          Integer diferencaFinal = rs.wasNull() ? null : dFinal;

          UUID invId = (UUID) rs.getObject("investigacao_id");

          list.add(new ItemAuditRecord(
              rs.getLong("id"),
              rs.getLong("resultado_inventario_id"),
              rs.getLong("inventario_id"),
              rs.getLong("filial_id"),
              rs.getLong("inventario_item_id"),
              rs.getString("sku"),
              rs.getString("descricao"),
              rs.getInt("saldo_snapshot"),
              r1Fisico,
              r1Diferenca,
              rs.getString("r1_estado"),
              rs.getBoolean("houve_r2"),
              r2Fisico,
              r2Diferenca,
              rs.getString("r2_estado"),
              quantidadeFisicaFinal,
              diferencaFinal,
              rs.getString("estado_final"),
              rs.getString("tipo_divergencia"),
              invId,
              rs.getString("status_investigacao"),
              rs.getString("causa_confirmada"),
              rs.getString("conclusao"),
              parseJsonMap(rs.getString("detalhes_localizacao_condicao")),
              instant(rs, "criado_em")
          ));
        }
        return list;
      }
    }
  }

  private List<EventHistoryRecord> loadInventoryEvents(Connection connection, long inventoryId) throws SQLException {
    String sql = """
        SELECT id, inventario_id, filial_id, tipo_evento, detalhes, criado_em, criado_por
        FROM eventos_inventario
        WHERE inventario_id = ?
        ORDER BY criado_em ASC, id ASC
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      try (ResultSet rs = statement.executeQuery()) {
        List<EventHistoryRecord> list = new ArrayList<>();
        while (rs.next()) {
          list.add(new EventHistoryRecord(
              rs.getLong("id"),
              rs.getLong("inventario_id"),
              rs.getLong("filial_id"),
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

  private void insertInventoryEvent(
      Connection connection,
      long inventoryId,
      long branchId,
      String tipoEvento,
      Map<String, Object> detalhes,
      String criadoPor
  ) throws SQLException {
    String sql = """
        INSERT INTO eventos_inventario (inventario_id, filial_id, tipo_evento, detalhes, criado_em, criado_por)
        VALUES (?, ?, ?, ?::jsonb, now(), ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      statement.setLong(2, branchId);
      statement.setString(3, tipoEvento);
      statement.setString(4, toJson(detalhes));
      statement.setString(5, criadoPor);
      statement.executeUpdate();
    }
  }

  private InventoryRecord lockInventoryForUpdate(Connection connection, long inventoryId, long branchId) throws SQLException {
    String sql = """
        SELECT id, filial_id, nome, tipo, modo, status, version, criado_em, aberto_em
        FROM inventarios
        WHERE id = ? AND filial_id = ?
        FOR UPDATE
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      statement.setLong(2, branchId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          throw new NotFoundException("Inventário não encontrado.");
        }
        return new InventoryRecord(
            rs.getLong("id"),
            rs.getLong("filial_id"),
            rs.getString("nome"),
            rs.getString("tipo"),
            rs.getString("modo"),
            rs.getString("status"),
            rs.getLong("version"),
            instant(rs, "criado_em"),
            nullableInstant(rs, "aberto_em")
        );
      }
    }
  }

  private InventoryRecord requireInventory(Connection connection, long inventoryId, long branchId) throws SQLException {
    String sql = """
        SELECT id, filial_id, nome, tipo, modo, status, version, criado_em, aberto_em
        FROM inventarios
        WHERE id = ? AND filial_id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      statement.setLong(2, branchId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          throw new NotFoundException("Inventário não encontrado.");
        }
        return new InventoryRecord(
            rs.getLong("id"),
            rs.getLong("filial_id"),
            rs.getString("nome"),
            rs.getString("tipo"),
            rs.getString("modo"),
            rs.getString("status"),
            rs.getLong("version"),
            instant(rs, "criado_em"),
            nullableInstant(rs, "aberto_em")
        );
      }
    }
  }

  private List<ItemRecord> loadItems(Connection connection, long inventoryId) throws SQLException {
    String sql = "SELECT id, sku, descricao_snapshot, saldo_snapshot FROM inventario_itens WHERE inventario_id = ? ORDER BY sku ASC";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      try (ResultSet rs = statement.executeQuery()) {
        List<ItemRecord> items = new ArrayList<>();
        while (rs.next()) {
          items.add(new ItemRecord(
              rs.getLong("id"),
              rs.getString("sku"),
              rs.getString("descricao_snapshot"),
              rs.getInt("saldo_snapshot")
          ));
        }
        return items;
      }
    }
  }

  private List<RoundRecord> loadRounds(Connection connection, long inventoryId) throws SQLException {
    String sql = "SELECT id, numero, tipo, status FROM rodadas_contagem WHERE inventario_id = ? ORDER BY numero ASC";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      try (ResultSet rs = statement.executeQuery()) {
        List<RoundRecord> list = new ArrayList<>();
        while (rs.next()) {
          list.add(new RoundRecord(
              rs.getLong("id"),
              rs.getInt("numero"),
              rs.getString("tipo"),
              rs.getString("status")
          ));
        }
        return list;
      }
    }
  }

  private RoundRecord findRoundByNumber(List<RoundRecord> rounds, int numero) {
    for (RoundRecord r : rounds) {
      if (r.numero() == numero) return r;
    }
    return null;
  }

  private Map<Long, ApuracaoRecord> loadApuracoes(Connection connection, long roundId) throws SQLException {
    String sql = """
        SELECT id, inventario_item_id, sku, saldo_snapshot, contado,
               quantidade_fisica, diferenca, estado, detalhes_localizacao_condicao
        FROM apuracoes_rodada
        WHERE rodada_id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, roundId);
      try (ResultSet rs = statement.executeQuery()) {
        Map<Long, ApuracaoRecord> map = new HashMap<>();
        while (rs.next()) {
          int qFis = rs.getInt("quantidade_fisica");
          Integer qFisica = rs.wasNull() ? null : qFis;

          int diff = rs.getInt("diferenca");
          Integer diferenca = rs.wasNull() ? null : diff;

          map.put(rs.getLong("inventario_item_id"), new ApuracaoRecord(
              rs.getLong("id"),
              rs.getLong("inventario_item_id"),
              rs.getString("sku"),
              rs.getInt("saldo_snapshot"),
              rs.getBoolean("contado"),
              qFisica,
              diferenca,
              rs.getString("estado"),
              rs.getString("detalhes_localizacao_condicao")
          ));
        }
        return map;
      }
    }
  }

  private Map<Long, InvestigationSummaryRecord> loadInvestigations(Connection connection, long inventoryId) throws SQLException {
    String sql = """
        SELECT id, inventario_item_id, sku, status, causa_confirmada, conclusao
        FROM investigacoes_divergencia
        WHERE inventario_id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      try (ResultSet rs = statement.executeQuery()) {
        Map<Long, InvestigationSummaryRecord> map = new HashMap<>();
        while (rs.next()) {
          map.put(rs.getLong("inventario_item_id"), new InvestigationSummaryRecord(
              (UUID) rs.getObject("id"),
              rs.getLong("inventario_item_id"),
              rs.getString("sku"),
              rs.getString("status"),
              rs.getString("causa_confirmada"),
              rs.getString("conclusao")
          ));
        }
        return map;
      }
    }
  }

  private Branch requireBranch(Connection connection, String branchCode) throws SQLException {
    String sql = "SELECT id, codigo FROM filiais WHERE codigo = ? AND ativa = true";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, branchCode);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          throw new NotFoundException("Filial " + branchCode + " não encontrada ou inativa.");
        }
        return new Branch(rs.getLong("id"), rs.getString("codigo"));
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

  private List<Object> parseJsonList(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      return objectMapper.readValue(json, new TypeReference<List<Object>>() {});
    } catch (Exception ex) {
      return List.of();
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

  private static String enumValue(String value, Set<String> allowed, String message) {
    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (!allowed.contains(normalized)) throw new ValidationException(message);
    return normalized;
  }

  private static SummaryMetrics buildEmptySummary() {
    return new SummaryMetrics(0, 0, 0, 0, 0, 0, 0);
  }

  // DTOs & Records
  public record CloseCommand(String tipo, String justificativa) {}

  public record Pendency(String codigo, String mensagem, boolean bloqueante) {}

  public record SummaryMetrics(
      int totalItens,
      Integer itensConformes,
      Integer divergenciasConfirmadas,
      Integer investigacoesResolvidas,
      Integer investigacoesSemCausa,
      Integer investigacoesPendentes,
      Integer itensNaoContados
  ) {}

  public record CloseResponse(
      long id,
      long inventarioId,
      String status,
      Instant timestamp,
      String modoFechamento,
      boolean confirmacao
  ) {}

  public record ValidationResult(
      boolean podeFechar,
      List<Pendency> pendencias,
      SummaryMetrics resumo
  ) {}

  public record ClosingResult(
      long id,
      long inventarioId,
      long filialId,
      String filialCodigo,
      String inventarioNome,
      String inventarioTipo,
      String inventarioModo,
      String inventarioStatus,
      int totalItens,
      int totalUnidadesSnapshot,
      int itensConformesR1,
      int itensEnviadosR2,
      int itensConformesAposR2,
      int divergenciasConfirmadas,
      int investigacoesResolvidas,
      int investigacoesSemCausa,
      int investigacoesPendentes,
      int itensNaoContados,
      int itensComFalta,
      int itensComSobra,
      int quantidadeFalta,
      int quantidadeSobra,
      Instant inicioEm,
      Instant fechadoEm,
      Long duracaoSegundos,
      String tipoFechamento,
      String justificativaExcepcional,
      List<Object> pendenciasSnapshot,
      String fechadoPorId,
      String fechadoPorNome,
      Instant criadoEm
  ) {}

  public record ItemAuditRecord(
      long id,
      long resultadoInventarioId,
      long inventarioId,
      long filialId,
      long inventarioItemId,
      String sku,
      String descricao,
      int saldoSnapshot,
      Integer r1Fisico,
      Integer r1Diferenca,
      String r1Estado,
      boolean houveR2,
      Integer r2Fisico,
      Integer r2Diferenca,
      String r2Estado,
      Integer quantidadeFisicaFinal,
      Integer diferencaFinal,
      String estadoFinal,
      String tipoDivergencia,
      UUID investigacaoId,
      String statusInvestigacao,
      String causaConfirmada,
      String conclusao,
      Map<String, Object> detalhesLocalizacaoCondicao,
      Instant criadoEm
  ) {}

  public record EventHistoryRecord(
      long id,
      long inventarioId,
      long filialId,
      String tipoEvento,
      Map<String, Object> detalhes,
      Instant criadoEm,
      String criadoPor
  ) {}

  public record DetailedHistoryResult(
      ClosingResult resultado,
      List<ItemAuditRecord> itens,
      List<EventHistoryRecord> eventos
  ) {}

  private record Branch(long id, String code) {}
  private record InventoryRecord(long id, long filialId, String nome, String tipo, String modo,
                                 String status, long version, Instant criadoEm, Instant abertoEm) {}
  private record ItemRecord(long id, String sku, String descricaoSnapshot, int saldoSnapshot) {}
  private record RoundRecord(long id, int numero, String tipo, String status) {}
  private record ApuracaoRecord(long id, long inventarioItemId, String sku, int saldoSnapshot,
                                boolean contado, Integer quantidadeFisica, Integer diferenca,
                                String estado, String detalhesJson) {}
  private record InvestigationSummaryRecord(UUID id, long inventarioItemId, String sku,
                                            String status, String causaConfirmada, String conclusao) {}

  private record ItemSnapshotToInsert(
      long itemId,
      String sku,
      String descricao,
      int saldoSnapshot,
      Integer r1Fisico,
      Integer r1Diferenca,
      String r1Estado,
      boolean houveR2,
      Integer r2Fisico,
      Integer r2Diferenca,
      String r2Estado,
      Integer quantidadeFisicaFinal,
      Integer diferencaFinal,
      String estadoFinal,
      String tipoDivergencia,
      UUID investigacaoId,
      String statusInvestigacao,
      String causaConfirmada,
      String conclusao,
      String detalhesLocalizacaoCondicao
  ) {}

  // Exceções
  public static class ClosingException extends RuntimeException {
    private final int status;
    ClosingException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
  }

  public static final class ValidationException extends ClosingException {
    ValidationException(String message) { super(400, message); }
  }

  public static final class ForbiddenException extends ClosingException {
    ForbiddenException(String message) { super(403, message); }
  }

  public static final class NotFoundException extends ClosingException {
    NotFoundException(String message) { super(404, message); }
  }

  public static final class ConflictException extends ClosingException {
    ConflictException(String message) { super(409, message); }
  }

  public static final class DatabaseException extends ClosingException {
    DatabaseException(String message, Throwable cause) { super(503, message); initCause(cause); }
  }
}
