package br.com.mncheck;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class InventoryReportService {
  public static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  private static final Set<String> CONTEXTS = Set.of("R1", "R2", "RESULTADO_FINAL");
  private static final Set<String> STATUSES = Set.of("TODOS", "CONTADOS", "NAO_CONTADOS", "CONFORMES", "DIVERGENTES");
  private static final Set<String> LOCATIONS = Set.of("TODAS", "GERAL", "VENDAS", "DEPOSITO", "TROCAS", "OUTRO");
  private static final Set<String> CONDITIONS = Set.of("TODAS", "BOA", "AVARIA", "ASSISTENCIA", "OUTROS");
  private static final Set<String> INVESTIGATIONS = Set.of("TODAS", "SEM_INVESTIGACAO", "PENDENTE", "EM_INVESTIGACAO", "AGUARDANDO_EVIDENCIA", "RESOLVIDA", "SEM_CAUSA_IDENTIFICADA");
  private static final Set<String> ORDERS = Set.of("SKU", "DESCRICAO", "LOCALIZACAO", "STATUS", "DIFERENCA");

  private final DatabaseUrlParser.JdbcConfig database;
  private final ObjectMapper mapper;

  public InventoryReportService() { this(System.getenv("DATABASE_URL"), new ObjectMapper()); }
  InventoryReportService(String databaseUrl, ObjectMapper mapper) {
    this.database = DatabaseUrlParser.parse(databaseUrl);
    this.mapper = mapper == null ? new ObjectMapper() : mapper;
  }

  public ReportProjection project(long inventoryId, String branchCode, ReportFilter rawFilter) {
    ReportFilter filter = normalize(rawFilter);
    String branch = normalizeBranch(branchCode);
    try (Connection connection = connect()) {
      Header header = loadHeader(connection, inventoryId, branch);
      boolean finalContext = "RESULTADO_FINAL".equals(filter.contexto());
      List<ReportItem> source;
      boolean protectedFields;
      if (finalContext) {
        source = loadFinalItems(connection, inventoryId, header.branchId());
        protectedFields = false;
      } else {
        int roundNumber = "R2".equals(filter.contexto()) ? 2 : 1;
        Round round = loadRound(connection, inventoryId, roundNumber);
        protectedFields = (roundNumber == 2 && "EM_ANDAMENTO".equals(round.status()))
            || (roundNumber == 1 && "CEGO".equals(header.mode()) && "EM_ANDAMENTO".equals(round.status()));
        source = loadRoundItems(connection, inventoryId, round, roundNumber == 2);
      }
      if (protectedFields && (Set.of("CONFORMES", "DIVERGENTES").contains(filter.status())
          || "DIFERENCA".equals(filter.ordenarPor()))) {
        throw new ReportException(403, "Este filtro revelaria informações protegidas pelo modo cego.");
      }
      List<ReportItem> filtered = applyFilters(source, filter, protectedFields);
      return new ReportProjection(
          inventoryId, header.name(), branch, header.mode(), header.status(), filter.contexto(),
          protectedFields, Instant.now(), filter, summarize(filtered, protectedFields), filtered);
    } catch (SQLException error) {
      throw new ReportException(500, "Não foi possível gerar o relatório de inventário.", error);
    }
  }

  public byte[] exportXlsx(ReportProjection report) {
    try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("Relatório Inventário");
      CellStyle title = workbook.createCellStyle();
      Font titleFont = workbook.createFont(); titleFont.setBold(true); titleFont.setFontHeightInPoints((short) 16); title.setFont(titleFont);
      CellStyle header = workbook.createCellStyle();
      Font headerFont = workbook.createFont(); headerFont.setBold(true); headerFont.setColor(IndexedColors.WHITE.getIndex());
      header.setFont(headerFont); header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex()); header.setFillPattern(FillPatternType.SOLID_FOREGROUND); header.setAlignment(HorizontalAlignment.CENTER);

      int r = 0;
      Row row = sheet.createRow(r++); row.createCell(0).setCellValue("MN-Check — Relatório de Inventário 3.0"); row.getCell(0).setCellStyle(title);
      meta(sheet.createRow(r++), "Filial", report.filial());
      meta(sheet.createRow(r++), "Sessão", report.inventarioNome() + " (#" + report.inventarioId() + ")");
      meta(sheet.createRow(r++), "Contexto", report.contexto());
      meta(sheet.createRow(r++), "Filtros", filterDescription(report.filtros()));
      meta(sheet.createRow(r++), "Emitido em", report.emitidoEm().toString());
      meta(sheet.createRow(r++), "Modo cego", report.camposProtegidos() ? "ATIVO — saldo e diferença protegidos" : "Não");
      r++;
      String[] columns = {"SKU", "Descrição", "Localização", "Condição", "Quantidade", "Estado", "Saldo", "Diferença", "Investigação", "Conclusão"};
      row = sheet.createRow(r++);
      for (int c = 0; c < columns.length; c++) { row.createCell(c).setCellValue(columns[c]); row.getCell(c).setCellStyle(header); }
      for (ReportItem item : report.itens()) {
        row = sheet.createRow(r++);
        row.createCell(0).setCellValue(item.sku()); row.createCell(1).setCellValue(item.descricao());
        row.createCell(2).setCellValue(item.localizacao()); row.createCell(3).setCellValue(item.condicao());
        if (item.quantidade() != null) row.createCell(4).setCellValue(item.quantidade());
        row.createCell(5).setCellValue(item.estado());
        if (item.saldo() != null) row.createCell(6).setCellValue(item.saldo());
        if (item.diferenca() != null) row.createCell(7).setCellValue(item.diferenca());
        row.createCell(8).setCellValue(item.statusInvestigacao() == null ? "" : item.statusInvestigacao());
        row.createCell(9).setCellValue(item.conclusao() == null ? "" : item.conclusao());
      }
      for (int c = 0; c < columns.length; c++) sheet.autoSizeColumn(c);
      sheet.createFreezePane(0, 9);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException error) {
      throw new ReportException(500, "Não foi possível criar o arquivo XLSX.", error);
    }
  }

  static List<ReportItem> applyFilters(List<ReportItem> items, ReportFilter filter, boolean protectedFields) {
    String search = filter.busca().toLowerCase(Locale.ROOT);
    Comparator<ReportItem> comparator = switch (filter.ordenarPor()) {
      case "DESCRICAO" -> Comparator.comparing(ReportItem::descricao, String.CASE_INSENSITIVE_ORDER).thenComparing(ReportItem::sku);
      case "LOCALIZACAO" -> Comparator.comparing(ReportItem::localizacao).thenComparing(ReportItem::sku);
      case "STATUS" -> Comparator.comparing(ReportItem::estado).thenComparing(ReportItem::sku);
      case "DIFERENCA" -> Comparator.comparing((ReportItem i) -> i.diferenca() == null ? Integer.MIN_VALUE : i.diferenca()).reversed().thenComparing(ReportItem::sku);
      default -> Comparator.comparing(ReportItem::sku, InventoryReportService::compareSku);
    };
    return items.stream().filter(item -> statusMatches(item, filter.status()))
        .filter(item -> "TODAS".equals(filter.localizacao()) || item.matchesLocation(filter.localizacao()))
        .filter(item -> "TODAS".equals(filter.condicao()) || item.matchesCondition(filter.condicao()))
        .filter(item -> investigationMatches(item, filter.investigacao()))
        .filter(item -> search.isBlank() || item.sku().toLowerCase(Locale.ROOT).contains(search) || item.descricao().toLowerCase(Locale.ROOT).contains(search))
        .map(item -> protectedFields ? item.protect() : item).sorted(comparator).toList();
  }

  private List<ReportItem> loadRoundItems(Connection c, long inventoryId, Round round, boolean scoped) throws SQLException {
    String sql = "SELECT ii.id, ii.sku, ii.descricao_snapshot, ii.saldo_snapshot FROM inventario_itens ii "
        + (scoped ? "JOIN rodada_itens ri ON ri.inventario_item_id=ii.id AND ri.rodada_id=? " : "")
        + "WHERE ii.inventario_id=? ORDER BY ii.sku";
    List<ReportItem> result = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      int p = 1; if (scoped) ps.setLong(p++, round.id()); ps.setLong(p, inventoryId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) result.add(projectRoundItem(c, round, rs.getLong("id"), rs.getString("sku"), rs.getString("descricao_snapshot"), rs.getInt("saldo_snapshot")));
      }
    }
    return result;
  }

  private ReportItem projectRoundItem(Connection c, Round round, long itemId, String sku, String description, int balance) throws SQLException {
    String auditSql = "SELECT contado, quantidade_fisica, diferenca, estado, detalhes_localizacao_condicao FROM apuracoes_rodada WHERE rodada_id=? AND inventario_item_id=?";
    try (PreparedStatement ps = c.prepareStatement(auditSql)) {
      ps.setLong(1, round.id()); ps.setLong(2, itemId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return new ReportItem(sku, description, locations(rs.getString("detalhes_localizacao_condicao")), rs.getBoolean("contado") ? (Integer) rs.getObject("quantidade_fisica") : null, rs.getString("estado"), balance, (Integer) rs.getObject("diferenca"), null, null);
      }
    }
    List<InventoryCountingService.OccurrenceRecord> occurrences = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement("SELECT id, rodada_id, inventario_item_id, sku, quantidade, localizacao, categoria, tipo_acao, operador, client_event_id, origem, dispositivo, client_timestamp, server_timestamp, referencia_id FROM ocorrencias_contagem WHERE rodada_id=? AND inventario_item_id=? ORDER BY server_timestamp,id")) {
      ps.setLong(1, round.id()); ps.setLong(2, itemId);
      try (ResultSet rs = ps.executeQuery()) { while (rs.next()) occurrences.add(mapOccurrence(rs)); }
    }
    if (occurrences.isEmpty()) return new ReportItem(sku, description, Map.of(), null, "NAO_CONTADO", balance, null, null, null);
    InventoryCountingService.ItemProjection p = InventoryCountingService.calculateItemProjection(occurrences);
    int difference = p.totalQuantity() - balance;
    return new ReportItem(sku, description, toObjectMap(p.locationDetails()), p.totalQuantity(), difference == 0 ? "CONFORME" : "DIVERGENTE", balance, difference, null, null);
  }

  private List<ReportItem> loadFinalItems(Connection c, long inventoryId, long branchId) throws SQLException {
    String sql = "SELECT sku,descricao,saldo_snapshot,quantidade_fisica_final,diferenca_final,estado_final,status_investigacao,conclusao,detalhes_localizacao_condicao FROM resultado_inventario_itens WHERE inventario_id=? AND filial_id=? ORDER BY sku";
    List<ReportItem> result = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, inventoryId); ps.setLong(2, branchId);
      try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(new ReportItem(rs.getString("sku"), rs.getString("descricao"), locations(rs.getString("detalhes_localizacao_condicao")), (Integer) rs.getObject("quantidade_fisica_final"), rs.getString("estado_final"), rs.getInt("saldo_snapshot"), (Integer) rs.getObject("diferenca_final"), rs.getString("status_investigacao"), rs.getString("conclusao"))); }
    }
    if (result.isEmpty()) throw new ReportException(404, "Resultado final ainda não está disponível para este inventário.");
    return result;
  }

  private Header loadHeader(Connection c, long id, String branch) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT i.id,i.filial_id,i.nome,i.modo,i.status FROM inventarios i JOIN filiais f ON f.id=i.filial_id WHERE i.id=? AND f.codigo=?")) {
      ps.setLong(1, id); ps.setString(2, branch);
      try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return new Header(rs.getLong("filial_id"), rs.getString("nome"), rs.getString("modo"), rs.getString("status")); }
    }
    throw new ReportException(404, "Inventário não encontrado para esta filial.");
  }

  private Round loadRound(Connection c, long inventoryId, int number) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT id,status FROM rodadas_contagem WHERE inventario_id=? AND numero=?")) {
      ps.setLong(1, inventoryId); ps.setInt(2, number);
      try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return new Round(rs.getLong("id"), rs.getString("status")); }
    }
    throw new ReportException(404, "Rodada R" + number + " não encontrada neste inventário.");
  }

  private InventoryCountingService.OccurrenceRecord mapOccurrence(ResultSet rs) throws SQLException {
    Timestamp client = rs.getTimestamp("client_timestamp"), server = rs.getTimestamp("server_timestamp"); long ref = rs.getLong("referencia_id"); Long reference = rs.wasNull() ? null : ref;
    return new InventoryCountingService.OccurrenceRecord(rs.getLong("id"), rs.getLong("rodada_id"), rs.getLong("inventario_item_id"), rs.getString("sku"), rs.getInt("quantidade"), rs.getString("localizacao"), rs.getString("categoria"), rs.getString("tipo_acao"), rs.getString("operador"), (java.util.UUID) rs.getObject("client_event_id"), rs.getString("origem"), rs.getString("dispositivo"), client == null ? null : client.toInstant(), server.toInstant(), reference);
  }

  private Map<String,Object> locations(String json) { try { return json == null ? Map.of() : mapper.readValue(json, new TypeReference<LinkedHashMap<String,Object>>(){}); } catch (Exception ignored) { return Map.of(); } }
  private static Map<String,Object> toObjectMap(Map<String,Map<String,Integer>> value) { return new LinkedHashMap<>(value); }
  private Connection connect() throws SQLException { return database.username().isBlank() ? DriverManager.getConnection(database.url()) : DriverManager.getConnection(database.url(), database.username(), database.password()); }

  static ReportFilter normalize(ReportFilter f) {
    if (f == null) f = new ReportFilter(null,null,null,null,null,null,null);
    return new ReportFilter(enumValue(f.contexto(), CONTEXTS, "R1"), enumValue(f.status(), STATUSES, "TODOS"), enumValue(f.localizacao(), LOCATIONS, "TODAS"), enumValue(f.condicao(), CONDITIONS, "TODAS"), enumValue(f.investigacao(), INVESTIGATIONS, "TODAS"), f.busca() == null ? "" : f.busca().trim(), enumValue(f.ordenarPor(), ORDERS, "SKU"));
  }
  private static String enumValue(String value, Set<String> allowed, String fallback) { String v = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT); if (!allowed.contains(v)) throw new ReportException(400, "Filtro inválido: " + value); return v; }
  private static String normalizeBranch(String value) { String v=value==null?"":value.trim(); if(!v.matches("\\d{1,20}")) throw new ReportException(400,"Código de filial inválido."); return v; }
  private static boolean statusMatches(ReportItem i,String s) { return switch(s){case "CONTADOS"->i.quantidade()!=null;case "NAO_CONTADOS"->i.quantidade()==null||"NAO_CONTADO".equals(i.estado());case "CONFORMES"->i.estado().startsWith("CONFORME");case "DIVERGENTES"->i.estado().contains("DIVERG")||i.diferenca()!=null&&i.diferenca()!=0;default->true;}; }
  private static boolean investigationMatches(ReportItem i,String s){ if("TODAS".equals(s))return true;if("SEM_INVESTIGACAO".equals(s))return i.statusInvestigacao()==null||i.statusInvestigacao().isBlank();return s.equals(i.statusInvestigacao()); }
  private static Summary summarize(List<ReportItem> list,boolean protectedFields){int counted=0,conform=0,div=0,pending=0,balance=0,quantity=0,diff=0;for(ReportItem i:list){if(i.quantidade()==null)pending++;else{counted++;quantity+=i.quantidade();}if(i.estado().startsWith("CONFORME"))conform++;if(i.estado().contains("DIVERG"))div++;if(!protectedFields){balance+=i.saldo()==null?0:i.saldo();diff+=i.diferenca()==null?0:i.diferenca();}}return new Summary(list.size(),counted,pending,conform,div,protectedFields?null:balance,quantity,protectedFields?null:diff);}
  private static int compareSku(String a,String b){String[]x=a.split("\\.",-1),y=b.split("\\.",-1);for(int i=0;i<Math.max(x.length,y.length);i++){String l=i<x.length?x[i]:"",r=i<y.length?y[i]:"";int c;try{c=Long.compare(Long.parseLong(l),Long.parseLong(r));}catch(NumberFormatException e){c=l.compareToIgnoreCase(r);}if(c!=0)return c;}return 0;}
  private static String filterDescription(ReportFilter f){return "Status: "+f.status()+" | Localização: "+f.localizacao()+" | Condição: "+f.condicao()+" | Investigação: "+f.investigacao()+(f.busca().isBlank()?"":" | Busca: "+f.busca());}
  private static void meta(Row row,String key,String value){row.createCell(0).setCellValue(key);row.createCell(1).setCellValue(value);}

  public record ReportFilter(String contexto,String status,String localizacao,String condicao,String investigacao,String busca,String ordenarPor){}
  public record Summary(int total,int contados,int naoContados,int conformes,int divergentes,Integer saldoTotal,int quantidadeTotal,Integer diferencaTotal){}
  public record ReportProjection(long inventarioId,String inventarioNome,String filial,String modo,String inventarioStatus,String contexto,boolean camposProtegidos,Instant emitidoEm,ReportFilter filtros,Summary resumo,List<ReportItem> itens){}
  public record ReportItem(String sku,String descricao,Map<String,Object> detalhes,Integer quantidade,String estado,Integer saldo,Integer diferenca,String statusInvestigacao,String conclusao){
    ReportItem protect(){return new ReportItem(sku,descricao,detalhes,quantidade,quantidade==null?"NAO_CONTADO":"CONTADO",null,null,null,null);}
    String localizacao(){return detalhes==null||detalhes.isEmpty()?"":String.join(", ",detalhes.keySet());}
    String condicao(){if(detalhes==null)return "";List<String> values=new ArrayList<>();for(Object v:detalhes.values())if(v instanceof Map<?,?>m)for(Object k:m.keySet())if(!values.contains(String.valueOf(k)))values.add(String.valueOf(k));return String.join(", ",values);}
    boolean matchesLocation(String value){return detalhes!=null&&detalhes.containsKey(value);}
    boolean matchesCondition(String value){if(detalhes==null)return false;for(Object v:detalhes.values())if(v instanceof Map<?,?>m&&m.containsKey(value))return true;return false;}
  }
  private record Header(long branchId,String name,String mode,String status){}
  private record Round(long id,String status){}
  public static class ReportException extends RuntimeException {private final int status;ReportException(int status,String message){super(message);this.status=status;}ReportException(int status,String message,Throwable cause){super(message,cause);this.status=status;}public int status(){return status;}}
}
