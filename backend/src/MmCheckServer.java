package br.com.mncheck;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MmCheckServer {
  private static final String APP_VERSION = "1.6.0";
  private static final int MAX_BALANCE_PDF_BYTES = 25 * 1024 * 1024;
  private static final int PORT = Integer.parseInt(
      System.getProperty("mmcheck.legacy.port", System.getenv().getOrDefault("PORT", "4173"))
  );
  private static final Path ROOT = Path.of("").toAbsolutePath();
  private static final Path FRONTEND = ROOT.resolve("frontend");
  private static final Map<String, String> SESSIONS = new LinkedHashMap<>();
  private static HttpServer server;
  private static ExecutorService executor;
  private static PersistenceStore persistence;
  private static PostgresDatabase relationalDatabase;
  private static Database db;

  public static void main(String[] args) throws Exception {
    String databaseUrl = requireDatabaseUrl();
    relationalDatabase = new PostgresDatabase(databaseUrl);
    persistence = createPersistenceStore();
    db = Database.load();
    applyRelationalBalanceSnapshot();

    server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
    server.createContext("/", MmCheckServer::handle);
    executor = Executors.newFixedThreadPool(8);
    server.setExecutor(executor);
    server.start();
    String databaseIdentity = relationalDatabase.testConnection();
    System.out.println("MN - Check " + APP_VERSION + " rodando em http://0.0.0.0:" + PORT
        + " com persistência PostgreSQL " + databaseIdentity);
    Thread.currentThread().join();
  }

  private static void handle(HttpExchange exchange) throws IOException {
    try {
      String path = exchange.getRequestURI().getPath();
      if (path.startsWith("/api/")) {
        handleApi(exchange, path);
      } else {
        serveStatic(exchange, path);
      }
    } catch (ApiException error) {
      json(exchange, error.status, Map.of("error", error.getMessage()));
    } catch (PersistenceException error) {
      error.printStackTrace();
      try {
        db = Database.load();
        applyRelationalBalanceSnapshot();
      } catch (Exception recoveryError) {
        recoveryError.printStackTrace();
      }
      json(exchange, 503, Map.of("error", "Banco de dados temporariamente indisponível. A alteração não foi confirmada."));
    } catch (PostgresDatabase.DatabaseException error) {
      error.printStackTrace();
      json(exchange, 503, Map.of(
          "error", "PostgreSQL temporariamente indisponível. Nenhuma alteração foi confirmada."
      ));
    } catch (Exception error) {
      error.printStackTrace();
      json(exchange, 500, Map.of("error", "Erro interno do servidor."));
    }
  }

  private static void handleApi(HttpExchange exchange, String path) throws Exception {
    String method = exchange.getRequestMethod();

    if ("POST".equals(method) && "/api/login".equals(path)) {
      Map<String, Object> body = readJson(exchange);
      String username = string(body.get("username")).trim();
      String password = string(body.get("password")).trim();
      User user = db.findUserByUsername(username)
          .filter(item -> item.passwordHash.equals(hash(password)))
          .orElseThrow(() -> new ApiException(401, "Usuário ou senha inválidos."));
      String token = UUID.randomUUID().toString().replace("-", "");
      SESSIONS.put(token, user.id);
      json(exchange, 200, Map.of("token", token, "user", user.publicMap()));
      return;
    }

    if ("GET".equals(method) && "/api/health".equals(path)) {
      json(exchange, 200, Map.of(
          "status", "ok",
          "app", "MN - Check",
          "version", APP_VERSION,
          "database", persistence.description(),
          "connection", relationalDatabase.testConnection()
      ));
      return;
    }

    if ("GET".equals(method) && "/api/version".equals(path)) {
      json(exchange, 200, Map.of("app", "MN - Check", "version", APP_VERSION));
      return;
    }

    User user = requireUser(exchange);

    if ("GET".equals(method) && "/api/bootstrap".equals(path)) {
      json(exchange, 200, visibleData(user));
      return;
    }

    if ("GET".equals(method) && "/api/saldos".equals(path)) {
      requireRole(user, "admin", "stock");
      applyRelationalBalanceSnapshot();
      json(exchange, 200, countData());
      return;
    }

    if ("GET".equals(method) && "/api/historico".equals(path)) {
      requireAdmin(user);
      json(exchange, 200, historyData());
      return;
    }

    if ("GET".equals(method) && "/api/users".equals(path)) {
      requireAdmin(user);
      json(exchange, 200, Map.of("users", db.users.stream().map(User::publicMap).toList()));
      return;
    }

    if ("POST".equals(method) && "/api/users".equals(path)) {
      requireAdmin(user);
      Map<String, Object> body = readJson(exchange);
      String username = string(body.get("username")).trim();
      String name = string(body.get("name")).trim();
      String role = string(body.get("role")).trim();
      String password = string(body.get("password"));
      if (username.isBlank() || name.isBlank() || password.isBlank()) {
        throw new ApiException(400, "Preencha usuário, nome e senha.");
      }
      if (!User.allowedRoles().contains(role)) throw new ApiException(400, "Função inválida.");
      if (db.findUserByUsername(username).isPresent()) throw new ApiException(409, "Usuário já cadastrado.");
      User created = new User(UUID.randomUUID().toString(), username, name, role, User.label(role), hash(password));
      db.users.add(created);
      db.recordHistory(user, "create_user", "Usuário " + username + " cadastrado");
      db.save();
      json(exchange, 201, Map.of("user", created.publicMap()));
      return;
    }

    if ("DELETE".equals(method) && path.startsWith("/api/users/")) {
      requireAdmin(user);
      String userId = URLDecoder.decode(path.substring("/api/users/".length()), StandardCharsets.UTF_8);
      User target = db.users.stream().filter(item -> item.id.equals(userId)).findFirst()
          .orElseThrow(() -> new ApiException(404, "Usuário não encontrado."));
      if ("Marcos".equalsIgnoreCase(target.username)) {
        throw new ApiException(403, "O administrador principal Marcos não pode ser removido.");
      }
      db.users.remove(target);
      SESSIONS.entrySet().removeIf(entry -> entry.getValue().equals(target.id));
      db.recordHistory(user, "delete_user", "Usuário " + target.username + " removido");
      db.save();
      json(exchange, 200, visibleData(user));
      return;
    }

    if ("PATCH".equals(method) && path.startsWith("/api/users/") && path.endsWith("/password")) {
      String userId = URLDecoder.decode(
          path.substring("/api/users/".length(), path.length() - "/password".length()),
          StandardCharsets.UTF_8
      );
      User target = db.users.stream().filter(item -> item.id.equals(userId)).findFirst()
          .orElseThrow(() -> new ApiException(404, "Usuário não encontrado."));
      boolean ownPassword = target.id.equals(user.id);
      boolean principalAdmin = "Marcos".equalsIgnoreCase(user.username);
      if (!ownPassword && !principalAdmin) {
        throw new ApiException(403, "Somente Marcos pode redefinir a senha de outros usuários.");
      }

      Map<String, Object> body = readJson(exchange);
      String currentPassword = string(body.get("currentPassword"));
      String newPassword = string(body.get("newPassword"));
      if (newPassword.length() < 6) throw new ApiException(400, "A nova senha deve ter pelo menos 6 caracteres.");
      if (ownPassword && !target.passwordHash.equals(hash(currentPassword))) {
        throw new ApiException(401, "A senha atual está incorreta.");
      }

      User updated = new User(target.id, target.username, target.name, target.role, target.label, hash(newPassword));
      int index = db.users.indexOf(target);
      db.users.set(index, updated);
      SESSIONS.entrySet().removeIf(entry -> entry.getValue().equals(target.id));
      db.recordHistory(user, ownPassword ? "change_password" : "reset_password",
          ownPassword ? "Senha própria alterada" : "Senha de " + target.username + " redefinida");
      db.save();
      json(exchange, 200, Map.of("message", ownPassword ? "Senha alterada." : "Senha redefinida."));
      return;
    }

    if ("POST".equals(method) && "/api/maps/upload".equals(path)) {
      if (!List.of("admin", "separation").contains(user.role)) {
        throw new ApiException(403, "Ação permitida apenas para administradores e conferentes de separação.");
      }
      Map<String, Object> body = readJson(exchange);
      String fileName = string(body.get("fileName")).trim();
      String contentType = string(body.get("contentType")).trim();
      String dataUrl = string(body.get("dataUrl")).trim();
      if (fileName.isBlank() || dataUrl.isBlank()) {
        throw new ApiException(400, "Selecione um PDF ou imagem para importar.");
      }
      if (!List.of(
          "application/pdf",
          "image/png",
          "image/jpeg",
          "image/webp",
          "image/heic",
          "image/heif"
      ).contains(contentType)) {
        throw new ApiException(400, "Formato não permitido. Use PDF, PNG, JPG, WebP, HEIC ou HEIF.");
      }

      byte[] documentBytes = decodeDataUrl(dataUrl);
      if (documentBytes.length == 0 || documentBytes.length > 10 * 1024 * 1024) {
        throw new ApiException(400, "O arquivo deve ter entre 1 byte e 10 MB.");
      }
      String next = String.valueOf(db.maps.stream().mapToInt(item -> Integer.parseInt(item.id)).max().orElse(15727) + 1);
      String storedName = next + "-" + safeFileName(fileName);

      CargoMap map = analyzeMapWithGemini(next, user.id, contentType, dataUrl);
      persistence.saveFile(storedName, contentType, documentBytes);
      map.attachmentName = fileName;
      map.attachmentType = contentType;
      map.attachmentPath = "data/uploads/" + storedName;
      db.maps.add(0, map);
      db.recordHistory(user, "upload_map", "Mapa " + next + " criado por upload: " + fileName);
      db.save();
      json(exchange, 201, visibleData(user));
      return;
    }

    if ("POST".equals(method) && List.of("/api/counts/upload", "/api/importar").contains(path)) {
      if (!List.of("admin", "stock").contains(user.role)) throw new ApiException(403, "Ação não permitida.");
      Map<String, Object> body = readJson(exchange);
      String fileName = string(body.get("fileName")).trim();
      String contentType = string(body.get("contentType")).trim();
      String dataUrl = string(body.get("dataUrl")).trim();
      if (fileName.isBlank() || dataUrl.isBlank()) throw new ApiException(400, "Selecione um PDF de saldo.");
      if (contentType.isBlank()) contentType = "application/pdf";
      if (!"application/pdf".equals(contentType)) throw new ApiException(400, "O arquivo de saldo deve ser um PDF.");
      byte[] pdfBytes = decodeDataUrl(dataUrl);
      if (pdfBytes.length == 0 || pdfBytes.length > MAX_BALANCE_PDF_BYTES) {
        throw new ApiException(400, "O PDF deve ter entre 1 byte e 25 MB.");
      }

      CountImportResult importResult = analyzeCountsWithPdfBox(pdfBytes);
      List<CountItem> imported = importResult.items();
      BalancePdfParser.Metrics metrics = importResult.metrics();
      System.out.println("SALDO_PDF"
          + " arquivo=\"" + safeFileName(fileName) + "\""
          + " paginas=" + metrics.pagesProcessed()
          + " skus=" + metrics.skusRead()
          + " linhas_ignoradas=" + metrics.ignoredLines()
          + " duplicados=" + metrics.duplicateSkus()
          + " conflitos=" + metrics.conflictsFound()
          + " duracao_ms=" + metrics.elapsedMs());

      String storedName = "contagem-" + System.currentTimeMillis() + "-" + safeFileName(fileName);
      persistence.saveFile(storedName, contentType, pdfBytes);
      PostgresDatabase.ImportSummary importSummary = relationalDatabase.saveBalanceImport(
          fileName,
          imported.stream()
              .map(item -> new PostgresDatabase.BalanceRow(item.sku(), item.system()))
              .toList(),
          metrics.pagesProcessed(),
          metrics.ignoredLines(),
          metrics.duplicateSkus(),
          metrics.conflictsFound()
      );
      db.counts = imported;
      db.countsUpdatedAt = importSummary.updatedAt().toString();
      db.countsSourceName = importSummary.fileName();
      db.countsImportWarnings = importResult.warnings();
      db.countsImportMetrics = metrics;
      db.recordHistory(user, "count_upload", "Saldo atualizado pelo PDF " + fileName
          + " com " + imported.size() + " SKUs em " + metrics.pagesProcessed() + " folhas");
      db.save();
      System.out.println("SALDO_POSTGRES importacao_id=" + importSummary.id()
          + " arquivo=\"" + safeFileName(fileName) + "\""
          + " skus=" + importSummary.skuCount()
          + " atualizado_em=" + importSummary.updatedAt());
      json(exchange, 200, visibleData(user));
      return;
    }

    if (("PATCH".equals(method) && "/api/counts".equals(path))
        || ("POST".equals(method) && "/api/contagem".equals(path))) {
      if (!List.of("admin", "stock").contains(user.role)) throw new ApiException(403, "Ação não permitida.");
      Map<String, Object> body = readJson(exchange);
      List<Object> rows = list(body.get("counts"));
      if (rows.isEmpty()) throw new ApiException(400, "Não há contagens para atualizar.");
      PostgresDatabase.BalanceSnapshot currentSnapshot = relationalDatabase.loadLatestBalances();
      Map<String, Integer> currentBalances = new LinkedHashMap<>();
      currentSnapshot.rows().forEach(item -> currentBalances.put(item.sku(), item.systemBalance()));
      if (currentBalances.isEmpty()) {
        throw new ApiException(409, "Importe um PDF de saldo antes de registrar a contagem.");
      }
      Map<String, CountItem> updatedBySku = new LinkedHashMap<>();
      for (Object row : rows) {
        Map<String, Object> item = castMap(row);
        String sku = string(item.get("sku")).trim();
        if (!sku.matches("[A-Za-z0-9.-]{1,64}")) throw new ApiException(400, "SKU inválido na contagem.");
        Integer system = currentBalances.get(sku);
        if (system == null) throw new ApiException(400, "SKU não pertence ao saldo atual: " + sku + ".");
        int counted = integerField(item, "counted");
        if (system < 0 || counted < 0) throw new ApiException(400, "As quantidades não podem ser negativas.");
        if (updatedBySku.putIfAbsent(sku, new CountItem(sku, system, counted)) != null) {
          throw new ApiException(409, "SKU duplicado na contagem: " + sku + ".");
        }
      }
      List<CountItem> updated = new ArrayList<>(updatedBySku.values());
      if (updated.isEmpty()) throw new ApiException(400, "Nenhuma contagem válida foi informada.");
      long countId = relationalDatabase.saveCount(
          user.name,
          updated.stream()
              .map(item -> new PostgresDatabase.CountRow(item.sku(), item.system(), item.counted()))
              .toList()
      );
      db.counts = updated;
      db.recordHistory(user, "update_counts", "Contagem " + countId
          + " atualizada em " + updated.size() + " SKUs");
      db.save();
      json(exchange, 200, visibleData(user));
      return;
    }

    if ("GET".equals(method) && "/api/notifications".equals(path)) {
      requireAdmin(user);
      json(exchange, 200, Map.of(
          "notifications", db.notifications.stream().map(item -> item.toMap(user.id)).toList()
      ));
      return;
    }

    if ("POST".equals(method) && path.startsWith("/api/notifications/") && path.endsWith("/read")) {
      requireAdmin(user);
      String notificationId = path.substring("/api/notifications/".length(), path.length() - "/read".length());
      AdminNotification notification = db.notifications.stream()
          .filter(item -> item.id.equals(notificationId))
          .findFirst()
          .orElseThrow(() -> new ApiException(404, "Notificação não encontrada."));
      if (!notification.readBy.contains(user.id)) notification.readBy.add(user.id);
      db.save();
      json(exchange, 200, Map.of(
          "notifications", db.notifications.stream().map(item -> item.toMap(user.id)).toList()
      ));
      return;
    }

    if ("DELETE".equals(method) && path.startsWith("/api/maps/")) {
      if (!List.of("admin", "separation").contains(user.role)) {
        throw new ApiException(403, "Ação permitida apenas para administradores e conferentes de separação.");
      }
      String mapId = URLDecoder.decode(path.substring("/api/maps/".length()), StandardCharsets.UTF_8);
      CargoMap map = db.maps.stream().filter(item -> item.id.equals(mapId)).findFirst()
          .orElseThrow(() -> new ApiException(404, "Mapa não encontrado."));
      if (!"separacao".equals(map.status)) {
        throw new ApiException(409, "Somente mapas ainda em separação podem ser apagados.");
      }

      deleteUpload(map.attachmentPath);
      db.maps.remove(map);
      db.recordHistory(user, "delete_map", "Mapa " + map.id + " apagado durante a separação");
      db.save();
      json(exchange, 200, visibleData(user));
      return;
    }

    String[] parts = path.split("/");
    if (parts.length >= 5 && "api".equals(parts[1]) && "maps".equals(parts[2])) {
      CargoMap map = db.maps.stream().filter(item -> item.id.equals(parts[3])).findFirst()
          .orElseThrow(() -> new ApiException(404, "Mapa não encontrado."));

      if ("PATCH".equals(method) && parts.length == 6 && "items".equals(parts[4])) {
        if (!List.of("admin", "separation").contains(user.role)) throw new ApiException(403, "Ação não permitida.");
        if (!"separacao".equals(map.status)) throw new ApiException(403, "Mapa fora da etapa de separação.");
        Map<String, Object> body = readJson(exchange);
        String sku = URLDecoder.decode(parts[5], StandardCharsets.UTF_8);
        MapItem item = map.items.stream().filter(entry -> entry.sku.equals(sku)).findFirst()
            .orElseThrow(() -> new ApiException(404, "Item não encontrado."));
        item.ok = Boolean.TRUE.equals(body.get("ok"));
        db.recordHistory(user, "update_item", "Mapa " + map.id + ": " + item.sku);
        db.save();
        json(exchange, 200, visibleData(user));
        return;
      }

      if ("POST".equals(method) && parts.length == 5) {
        String action = parts[4];
        if ("scan".equals(action)) {
          if (!List.of("admin", "expedition").contains(user.role)) throw new ApiException(403, "Ação não permitida.");
          if (!List.of("aguardando conferencia", "conferencia", "corrigir problema").contains(map.status)) {
            throw new ApiException(403, "O mapa não está disponível para leitura.");
          }
          Map<String, Object> body = readJson(exchange);
          String code = digits(string(body.get("code")));
          if (code.isBlank()) throw new ApiException(400, "Código de barras inválido.");
          MapItem item = map.items.stream()
              .filter(entry -> digits(entry.barcode).equals(code) || digits(entry.sku).equals(code))
              .findFirst()
              .orElseThrow(() -> new ApiException(422, "Produto não pertence a este mapa."));
          if (item.checkedQuantity >= item.quantity) throw new ApiException(409, "A quantidade deste produto já foi conferida.");
          item.checkedQuantity++;
          db.recordHistory(user, "scan_item", "Mapa " + map.id + ": código " + code + " conferido");
          db.save();
          json(exchange, 200, Map.of(
              "item", item.toMap(),
              "allChecked", map.items.stream().allMatch(entry -> entry.checkedQuantity >= entry.quantity)
          ));
          return;
        } else if ("evidence".equals(action)) {
          if (!List.of("admin", "expedition").contains(user.role)) throw new ApiException(403, "Ação não permitida.");
          if (!List.of("aguardando conferencia", "conferencia", "corrigir problema").contains(map.status)) {
            throw new ApiException(403, "A foto só pode ser registrada durante a conferência.");
          }
          Map<String, Object> body = readJson(exchange);
          String fileName = string(body.get("fileName")).trim();
          String contentType = string(body.get("contentType")).trim();
          String dataUrl = string(body.get("dataUrl")).trim();
          if (fileName.isBlank() || dataUrl.isBlank()) throw new ApiException(400, "Nenhuma foto foi selecionada.");
          if (!List.of("image/png", "image/jpeg", "image/webp").contains(contentType)) {
            throw new ApiException(400, "Formato de imagem não permitido.");
          }
          byte[] image = decodeDataUrl(dataUrl);
          if (image.length > 10 * 1024 * 1024) throw new ApiException(400, "A foto deve ter no máximo 10 MB.");

          String storedName = "evidence-" + map.id + "-" + System.currentTimeMillis() + "-" + safeFileName(fileName);
          persistence.saveFile(storedName, contentType, image);
          map.evidenceName = fileName;
          map.evidencePath = "data/uploads/" + storedName;
          map.evidenceAt = Instant.now().toString();
          map.evidenceBy = user.name;
          db.recordHistory(user, "camera_evidence", "Foto de conferência registrada no mapa " + map.id);
        } else if ("send-conference".equals(action)) {
          if (!List.of("admin", "separation").contains(user.role)) throw new ApiException(403, "Ação não permitida.");
          if (!map.items.stream().allMatch(item -> item.ok)) throw new ApiException(400, "Todos os itens precisam estar ok.");
          map.status = "aguardando conferencia";
          db.recordHistory(user, "send_conference", "Mapa " + map.id + " enviado para conferência");
        } else if ("approve".equals(action)) {
          if (!List.of("admin", "expedition").contains(user.role)) throw new ApiException(403, "Ação não permitida.");
          if (!map.items.stream().allMatch(item -> item.checkedQuantity >= item.quantity)) {
            throw new ApiException(400, "Confira todas as unidades antes de finalizar o mapa.");
          }
          map.status = "conferido";
          db.recordHistory(user, "approve_map", "Mapa " + map.id + " conferido sem divergência");
        } else if ("problem".equals(action)) {
          if (!List.of("admin", "expedition").contains(user.role)) throw new ApiException(403, "Ação não permitida.");
          if ("corrigir problema".equals(map.status)) throw new ApiException(409, "Este mapa já foi marcado com divergência.");
          map.status = "corrigir problema";
          db.errors.add(0, new ErrorRecord(map.id, "Divergência na conferência", user.name));
          db.notifications.add(0, new AdminNotification(
              UUID.randomUUID().toString(),
              map.id,
              "Divergência encontrada no mapa " + map.id,
              user.name + " solicitou correção na conferência.",
              Instant.now().toString(),
              new ArrayList<>()
          ));
          db.recordHistory(user, "problem_map", "Mapa " + map.id + " marcado com divergência");
        } else if ("corrected".equals(action)) {
          if (!List.of("admin", "expedition").contains(user.role)) throw new ApiException(403, "Ação não permitida.");
          if (!"corrigir problema".equals(map.status)) throw new ApiException(403, "Mapa fora da etapa de correção.");
          map.status = "conferido";
          db.recordHistory(user, "corrected_map", "Mapa " + map.id + " corrigido e conferido");
        } else {
          throw new ApiException(404, "Rota não encontrada.");
        }
        db.save();
        json(exchange, 200, visibleData(user));
        return;
      }
    }

    throw new ApiException(404, "Rota não encontrada.");
  }

  private static Map<String, Object> visibleData(User user) {
    List<CargoMap> maps = db.maps.stream().filter(map -> {
      if ("admin".equals(user.role)) return true;
      if ("separation".equals(user.role)) return List.of("separacao", "aguardando conferencia", "conferencia", "perfeito", "conferido", "corrigir problema").contains(map.status);
      if ("expedition".equals(user.role)) return List.of("aguardando conferencia", "conferencia", "perfeito", "conferido", "corrigir problema").contains(map.status);
      return false;
    }).toList();

    long separating = db.maps.stream().filter(map -> "separacao".equals(map.status)).count();
    long waiting = db.maps.stream().filter(map -> "aguardando conferencia".equals(map.status)).count();
    long perfect = db.maps.stream().filter(map -> List.of("perfeito", "conferido").contains(map.status)).count();

    boolean canSeeCounts = List.of("admin", "stock").contains(user.role);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("user", user.publicMap());
    result.put("version", APP_VERSION);
    result.put("database", persistence.description());
    result.put("maps", maps.stream().map(CargoMap::toMap).toList());
    result.put("users", "admin".equals(user.role) ? db.users.stream().map(User::publicMap).toList() : List.of());
    result.put("counts", canSeeCounts ? db.counts.stream().map(CountItem::toMap).toList() : List.of());
    result.put("countsUpdatedAt", canSeeCounts ? db.countsUpdatedAt : "");
    result.put("countsSourceName", canSeeCounts ? db.countsSourceName : "");
    result.put("countsImportWarnings", canSeeCounts ? db.countsImportWarnings : List.of());
    result.put("countsImportMetrics", canSeeCounts ? db.countsImportMetrics.toMap() : Map.of());
    result.put("errors", "admin".equals(user.role) ? db.errors.stream().map(ErrorRecord::toMap).toList() : List.of());
    result.put("historyEvents", "admin".equals(user.role)
        ? db.historyEvents.stream().map(HistoryRecord::toMap).toList()
        : List.of());
    result.put("notifications", "admin".equals(user.role)
        ? db.notifications.stream().map(item -> item.toMap(user.id)).toList()
        : List.of());
    result.put("metrics", Map.of(
        "separating", separating,
        "waiting", waiting,
        "perfect", perfect,
        "errorCount", db.errors.size()
    ));
    return result;
  }

  private static Map<String, Object> countData() {
    return Map.of(
        "counts", db.counts.stream().map(CountItem::toMap).toList(),
        "updatedAt", db.countsUpdatedAt,
        "sourceName", db.countsSourceName,
        "warnings", db.countsImportWarnings,
        "importMetrics", db.countsImportMetrics.toMap()
    );
  }

  private static Map<String, Object> historyData() {
    List<CargoMap> maps = db.maps.stream()
        .filter(map -> !"separacao".equals(map.status))
        .toList();
    List<Map<String, Object>> events = new ArrayList<>(db.historyEvents.stream()
        .filter(event -> !List.of("count_upload", "update_counts").contains(event.action()))
        .map(HistoryRecord::toMap)
        .toList());
    events.addAll(relationalDatabase.loadHistory().stream().map(entry -> Map.<String, Object>of(
        "at", entry.at().toString(),
        "userName", entry.operator(),
        "action", entry.action(),
        "description", entry.description()
    )).toList());
    events.sort((left, right) -> string(right.get("at")).compareTo(string(left.get("at"))));
    return Map.of(
        "maps", maps.stream().map(CargoMap::toMap).toList(),
        "errors", db.errors.stream().map(ErrorRecord::toMap).toList(),
        "events", events
    );
  }

  private static void applyRelationalBalanceSnapshot() {
    PostgresDatabase.BalanceSnapshot snapshot = relationalDatabase.loadLatestBalances();
    if (snapshot.importSummary() == null) {
      db.counts = new ArrayList<>();
      db.countsUpdatedAt = "";
      db.countsSourceName = "";
      db.countsImportMetrics = BalancePdfParser.Metrics.empty();
      return;
    }
    db.counts = snapshot.rows().stream()
        .map(row -> new CountItem(row.sku(), row.systemBalance(), row.countedQuantity()))
        .toList();
    PostgresDatabase.ImportSummary summary = snapshot.importSummary();
    db.countsUpdatedAt = summary.updatedAt().toString();
    db.countsSourceName = summary.fileName();
    db.countsImportMetrics = new BalancePdfParser.Metrics(
        summary.pagesProcessed(),
        summary.skuCount(),
        summary.ignoredLines(),
        summary.duplicateSkus(),
        summary.conflictsFound(),
        0
    );
  }

  private static User requireUser(HttpExchange exchange) {
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
    String userId = SESSIONS.get(token);
    if (userId == null) throw new ApiException(401, "Sessão expirada. Faça login novamente.");
    return db.users.stream().filter(user -> user.id.equals(userId)).findFirst()
        .orElseThrow(() -> new ApiException(401, "Sessão inválida."));
  }

  private static void requireAdmin(User user) {
    if (!"admin".equals(user.role)) throw new ApiException(403, "Ação permitida apenas para administradores.");
  }

  private static void requireRole(User user, String... roles) {
    if (!List.of(roles).contains(user.role)) {
      throw new ApiException(403, "Ação não permitida para este usuário.");
    }
  }

  private static void serveStatic(HttpExchange exchange, String rawPath) throws IOException {
    String cleanPath = rawPath.equals("/") ? "/index.html" : rawPath;
    Path file = FRONTEND.resolve(cleanPath.substring(1)).normalize();
    if (!file.startsWith(FRONTEND) || !Files.exists(file)) {
      file = FRONTEND.resolve("index.html");
    }
    String type = switch (extension(file)) {
      case ".html" -> "text/html; charset=utf-8";
      case ".css" -> "text/css; charset=utf-8";
      case ".js" -> "text/javascript; charset=utf-8";
      case ".svg" -> "image/svg+xml";
      default -> "application/octet-stream";
    };
    byte[] bytes = Files.readAllBytes(file);
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
    exchange.getResponseHeaders().set("Pragma", "no-cache");
    exchange.getResponseHeaders().set("Expires", "0");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static Map<String, Object> readJson(HttpExchange exchange) throws IOException {
    try (InputStream input = exchange.getRequestBody()) {
      String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      if (body.isBlank()) return new LinkedHashMap<>();
      try {
        Object parsed = Json.parse(body);
        if (parsed instanceof Map<?, ?> map) {
          Map<String, Object> result = new LinkedHashMap<>();
          map.forEach((key, value) -> result.put(String.valueOf(key), value));
          addFallbackStringFields(body, result);
          return result;
        }
      } catch (RuntimeException ignored) {
        Map<String, Object> result = new LinkedHashMap<>();
        addFallbackStringFields(body, result);
        return result;
      }
      return new LinkedHashMap<>();
    }
  }

  private static void addFallbackStringFields(String body, Map<String, Object> result) {
    for (String key : List.of("username", "password", "name", "role", "fileName", "contentType", "dataUrl", "currentPassword", "newPassword")) {
      extractString(body, key).ifPresent(value -> result.put(key, value));
    }
    if (!result.containsKey("ok")) {
      if (body.contains("\"ok\":true")) result.put("ok", true);
      if (body.contains("\"ok\":false")) result.put("ok", false);
    }
  }

  private static Optional<String> extractString(String body, String key) {
    String marker = "\"" + key + "\"";
    int keyIndex = body.indexOf(marker);
    if (keyIndex < 0) return Optional.empty();
    int colon = body.indexOf(":", keyIndex + marker.length());
    int firstQuote = body.indexOf("\"", colon + 1);
    int secondQuote = body.indexOf("\"", firstQuote + 1);
    if (colon < 0 || firstQuote < 0 || secondQuote < 0) return Optional.empty();
    return Optional.of(body.substring(firstQuote + 1, secondQuote));
  }

  private static void json(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static String hash(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(bytes);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static byte[] decodeDataUrl(String dataUrl) {
    try {
      int comma = dataUrl.indexOf(",");
      String encoded = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
      return Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException error) {
      throw new ApiException(400, "Arquivo enviado em formato inválido.");
    }
  }

  private static String safeFileName(String fileName) {
    String cleaned = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    return cleaned.isBlank() ? "mapa.pdf" : cleaned;
  }

  private static void deleteUpload(String attachmentPath) {
    if (attachmentPath == null || attachmentPath.isBlank()) return;
    Path fileName = Path.of(attachmentPath).getFileName();
    if (fileName == null) return;
    persistence.deleteFile(fileName.toString());
  }

  private static String digits(String value) {
    return value == null ? "" : value.replaceAll("\\D", "");
  }

  private static CountImportResult analyzeCountsWithPdfBox(byte[] pdfBytes) throws IOException {
    BalancePdfParser.Result parsed;
    try {
      parsed = BalancePdfParser.parse(pdfBytes);
    } catch (IOException error) {
      throw new ApiException(422, "Não foi possível ler o PDF de saldo: " + error.getMessage());
    }
    BalancePdfParser.Metrics metrics = parsed.metrics();
    if (!parsed.conflicts().isEmpty()) {
      System.err.println("SALDO_PDF_CONFLITOS paginas=" + metrics.pagesProcessed()
          + " skus=" + metrics.skusRead()
          + " linhas_ignoradas=" + metrics.ignoredLines()
          + " conflitos=" + metrics.conflictsFound()
          + " detalhes=\"" + String.join(" | ", parsed.conflicts()) + "\"");
      throw new ApiException(422, "Foram encontrados saldos conflitantes: "
          + String.join(" ", parsed.conflicts()));
    }
    if (parsed.rows().isEmpty()) {
      throw new ApiException(422, "O PDFBox não encontrou linhas válidas com Produto, Grade X, Grade Y e Saldo.");
    }
    List<CountItem> items = parsed.rows().stream()
        .map(row -> new CountItem(row.sku(), row.balance(), 0))
        .toList();
    return new CountImportResult(items, parsed.warnings(), metrics);
  }

  private static Map<String, Object> analyzeDocumentWithGemini(
      String contentType,
      String dataUrl,
      String prompt,
      Map<String, Object> schema,
      String documentLabel
  ) throws Exception {
    String apiKey = System.getenv("GEMINI_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      throw new ApiException(503, "Leitura por IA não configurada. Adicione GEMINI_API_KEY no servidor.");
    }
    String encoded = dataUrl.substring(dataUrl.indexOf(",") + 1);
    String model = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-2.5-flash");
    Map<String, Object> requestBody = Map.of(
        "contents", List.of(Map.of(
            "role", "user",
            "parts", List.of(
                Map.of("text", prompt),
                Map.of("inlineData", Map.of("mimeType", contentType, "data", encoded))
            )
        )),
        "generationConfig", Map.of(
            "temperature", 0,
            "maxOutputTokens", 65536,
            "responseMimeType", "application/json",
            "responseJsonSchema", schema
        )
    );

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
        .timeout(Duration.ofSeconds(120))
        .header("Content-Type", "application/json")
        .header("x-goog-api-key", apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(requestBody), StandardCharsets.UTF_8))
        .build();
    HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() >= 400) {
      throw new ApiException(502, "A IA não conseguiu processar o " + documentLabel + ". Verifique a chave e tente novamente.");
    }

    Map<String, Object> root = castMap(Json.parse(response.body()));
    List<Object> candidates = list(root.get("candidates"));
    if (candidates.isEmpty()) throw new ApiException(422, "A IA não encontrou dados no " + documentLabel + ".");
    Map<String, Object> content = castMap(castMap(candidates.get(0)).get("content"));
    List<Object> parts = list(content.get("parts"));
    if (parts.isEmpty()) throw new ApiException(422, "A IA não retornou dados do " + documentLabel + ".");
    String text = string(castMap(parts.get(0)).get("text"));
    return castMap(Json.parse(text));
  }

  private static CargoMap analyzeMapWithGemini(String id, String createdBy, String contentType, String dataUrl) throws Exception {
    Map<String, Object> itemSchema = Map.of(
        "type", "object",
        "properties", Map.of(
            "sku", Map.of("type", "string", "description", "Código exato do produto impresso no documento"),
            "name", Map.of("type", "string", "description", "Descrição exata do produto"),
            "quantity", Map.of("type", "integer", "description", "Quantidade inteira do produto"),
            "barcode", Map.of("type", "string", "description", "Código de barras se estiver visível; caso contrário string vazia")
        ),
        "required", List.of("sku", "name", "quantity", "barcode")
    );
    Map<String, Object> schema = Map.of(
        "type", "object",
        "properties", Map.of(
            "route", Map.of("type", "string"),
            "client", Map.of("type", "string"),
            "carrier", Map.of("type", "string"),
            "branch", Map.of("type", "string"),
            "date", Map.of("type", "string"),
            "items", Map.of("type", "array", "items", itemSchema)
        ),
        "required", List.of("route", "client", "carrier", "branch", "date", "items")
    );
    String prompt = """
        Extraia este mapa de carga logístico brasileiro. Não invente nenhum dado.
        Identifique rota, cliente principal, transportadora, filial, data e todos os produtos.
        Para cada produto extraia SKU, descrição, quantidade inteira e código de barras quando visível.
        Ignore totais, pesos, cabeçalhos repetidos e anotações manuscritas.
        Se um campo não estiver legível, retorne string vazia. Preserve os códigos exatamente como impressos.
        """;
    Map<String, Object> analysis = analyzeDocumentWithGemini(contentType, dataUrl, prompt, schema, "mapa");

    CargoMap map = new CargoMap();
    map.id = id;
    map.route = string(analysis.get("route")).trim();
    map.client = string(analysis.get("client")).trim();
    map.carrier = string(analysis.get("carrier")).trim();
    map.branch = string(analysis.get("branch")).trim();
    map.date = string(analysis.get("date")).trim();
    map.status = "separacao";
    map.createdBy = createdBy;
    for (Object value : list(analysis.get("items"))) {
      Map<String, Object> itemData = castMap(value);
      String sku = string(itemData.get("sku")).trim();
      String name = string(itemData.get("name")).trim();
      int quantity = number(itemData.get("quantity"));
      if (sku.isBlank() || name.isBlank() || quantity <= 0) continue;
      MapItem item = new MapItem(sku, name, quantity, false);
      String barcode = string(itemData.get("barcode")).trim();
      if (!barcode.isBlank()) item.barcode = barcode;
      map.items.add(item);
    }
    if (map.items.isEmpty()) throw new ApiException(422, "Nenhum produto legível foi encontrado no arquivo.");
    if (map.client.isBlank()) map.client = "Cliente não identificado";
    if (map.route.isBlank()) map.route = "Não identificada";
    if (map.branch.isBlank()) map.branch = "281";
    return map;
  }

  private static String extension(Path file) {
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf(".");
    return dot >= 0 ? name.substring(dot) : "";
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String requireDatabaseUrl() {
    String databaseUrl = System.getenv("DATABASE_URL");
    if (databaseUrl == null || databaseUrl.isBlank()) {
      throw new PersistenceException(
          "DATABASE_URL é obrigatória. Configure a conexão PostgreSQL do Neon antes de iniciar."
      );
    }
    return databaseUrl;
  }

  private static PersistenceStore createPersistenceStore() {
    return new PostgresPersistenceStore(requireDatabaseUrl());
  }

  private interface PersistenceStore {
    Optional<String> load();
    void save(String payload);
    void saveFile(String name, String contentType, byte[] content);
    void deleteFile(String name);
    String description();
  }

  private static class PostgresPersistenceStore implements PersistenceStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    PostgresPersistenceStore(String databaseUrl) {
      try {
        Class.forName("org.postgresql.Driver");
      } catch (ClassNotFoundException error) {
        throw new PersistenceException("Driver PostgreSQL JDBC não encontrado.", error);
      }

      DatabaseUrlParser.JdbcConfig config;
      try {
        config = DatabaseUrlParser.parse(databaseUrl);
      } catch (DatabaseUrlParser.DatabaseUrlException error) {
        throw new PersistenceException(error.getMessage(), error);
      }
      this.jdbcUrl = config.url();
      this.username = config.username();
      this.password = config.password();
      createTables();
    }

    private Connection connect() throws SQLException {
      if (username.isBlank()) return DriverManager.getConnection(jdbcUrl);
      return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void createTables() {
      String stateSql = """
          CREATE TABLE IF NOT EXISTS mn_check_state (
            id SMALLINT PRIMARY KEY CHECK (id = 1),
            payload JSONB NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
          )
          """;
      String historySql = """
          CREATE TABLE IF NOT EXISTS mn_check_state_history (
            id BIGSERIAL PRIMARY KEY,
            payload JSONB NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
          )
          """;
      String historyIndexSql = """
          CREATE INDEX IF NOT EXISTS idx_mn_check_state_history_created_at
          ON mn_check_state_history (created_at DESC)
          """;
      String filesSql = """
          CREATE TABLE IF NOT EXISTS mn_check_files (
            name TEXT PRIMARY KEY,
            content_type TEXT NOT NULL,
            content BYTEA NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
          )
          """;
      try (Connection connection = connect(); Statement statement = connection.createStatement()) {
        statement.execute(stateSql);
        statement.execute(historySql);
        statement.execute(historyIndexSql);
        statement.execute(filesSql);
      } catch (SQLException error) {
        throw new PersistenceException("Não foi possível preparar as tabelas do PostgreSQL.", error);
      }
    }

    public Optional<String> load() {
      String sql = "SELECT payload::text FROM mn_check_state WHERE id = 1";
      try (Connection connection = connect();
           PreparedStatement statement = connection.prepareStatement(sql);
           ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
      } catch (SQLException error) {
        throw new PersistenceException("Não foi possível carregar os dados do PostgreSQL.", error);
      }
    }

    public synchronized void save(String payload) {
      String upsert = """
          INSERT INTO mn_check_state (id, payload, updated_at)
          VALUES (1, CAST(? AS JSONB), now())
          ON CONFLICT (id) DO UPDATE
          SET payload = EXCLUDED.payload, updated_at = now()
          """;
      String snapshot = "INSERT INTO mn_check_state_history (payload) VALUES (CAST(? AS JSONB))";
      String cleanup = """
          DELETE FROM mn_check_state_history
          WHERE id NOT IN (
            SELECT id FROM mn_check_state_history ORDER BY id DESC LIMIT 500
          )
          """;
      try (Connection connection = connect()) {
        connection.setAutoCommit(false);
        try (PreparedStatement stateStatement = connection.prepareStatement(upsert);
             PreparedStatement historyStatement = connection.prepareStatement(snapshot);
             Statement cleanupStatement = connection.createStatement()) {
          stateStatement.setString(1, payload);
          stateStatement.executeUpdate();
          historyStatement.setString(1, payload);
          historyStatement.executeUpdate();
          cleanupStatement.executeUpdate(cleanup);
          connection.commit();
        } catch (SQLException error) {
          connection.rollback();
          throw error;
        } finally {
          connection.setAutoCommit(true);
        }
      } catch (SQLException error) {
        throw new PersistenceException("Não foi possível salvar os dados no PostgreSQL.", error);
      }
    }

    public void saveFile(String name, String contentType, byte[] content) {
      String sql = """
          INSERT INTO mn_check_files (name, content_type, content, created_at)
          VALUES (?, ?, ?, now())
          ON CONFLICT (name) DO UPDATE
          SET content_type = EXCLUDED.content_type,
              content = EXCLUDED.content,
              created_at = now()
          """;
      try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, name);
        statement.setString(2, contentType);
        statement.setBytes(3, content);
        statement.executeUpdate();
      } catch (SQLException error) {
        throw new PersistenceException("Não foi possível salvar o arquivo no PostgreSQL.", error);
      }
    }

    public void deleteFile(String name) {
      try (Connection connection = connect();
           PreparedStatement statement = connection.prepareStatement("DELETE FROM mn_check_files WHERE name = ?")) {
        statement.setString(1, name);
        statement.executeUpdate();
      } catch (SQLException error) {
        throw new PersistenceException("Não foi possível remover o arquivo do PostgreSQL.", error);
      }
    }

    public String description() {
      return "PostgreSQL";
    }
  }

  private static class ApiException extends RuntimeException {
    final int status;
    ApiException(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  private static class PersistenceException extends RuntimeException {
    PersistenceException(String message) {
      super(message);
    }

    PersistenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private record User(String id, String username, String name, String role, String label, String passwordHash) {
    static List<String> allowedRoles() {
      return List.of("admin", "separation", "expedition", "stock");
    }

    static String label(String role) {
      return switch (role) {
        case "admin" -> "Administrador";
        case "separation" -> "Conferente de separação";
        case "expedition" -> "Conferente de expedição";
        case "stock" -> "Conferente de estoque";
        default -> "Usuário";
      };
    }

    Map<String, Object> publicMap() {
      return Map.of(
          "id", id,
          "username", username,
          "name", name,
          "role", role,
          "label", label,
          "allowedViews", switch (role) {
            case "admin" -> List.of("overview", "separation", "counting", "conference", "history", "users");
            case "separation" -> List.of("separation");
            case "expedition" -> List.of("conference");
            case "stock" -> List.of("counting");
            default -> List.of();
          }
      );
    }

    Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>(publicMap());
      map.put("passwordHash", passwordHash);
      return map;
    }

    static User fromMap(Map<String, Object> map) {
      return new User(string(map.get("id")), string(map.get("username")), string(map.get("name")),
          string(map.get("role")), string(map.get("label")), string(map.get("passwordHash")));
    }
  }

  private static class CargoMap {
    String id;
    String route;
    String client;
    String carrier;
    String branch;
    String date;
    String status;
    String createdBy;
    String attachmentName = "";
    String attachmentType = "";
    String attachmentPath = "";
    String evidenceName = "";
    String evidencePath = "";
    String evidenceAt = "";
    String evidenceBy = "";
    List<MapItem> items = new ArrayList<>();

    static CargoMap sample(String id, String createdBy) {
      int variant = Math.abs(number(id)) % 4;
      CargoMap map = new CargoMap();
      map.id = id;
      map.route = List.of("1135/1135-CEDRAL", "1135/1140-RIO PRETO", "1135/1128-MIRASSOL", "1135/1172-BADY").get(variant);
      map.client = List.of("LEITE EXPRESS TRANSPORTES LTDA", "RENOVA MATERIAIS DE CONSTRUÇÃO", "MERCADOMOVEIS LTDA", "CLIENTE BALCÃO").get(variant);
      map.carrier = List.of("MARIVALDO D A LUZ", "VICTOR PEREIRA", "ROGERIO TRANSPORTES", "EXPEDIÇÃO INTERNA").get(variant);
      map.branch = "281";
      map.date = "29/05/2026";
      map.status = "separacao";
      map.createdBy = createdBy;
      List<List<MapItem>> variants = List.of(
          List.of(new MapItem("73578-1.2", "Lavadora Midea", 6, false), new MapItem("75480-1.2", "Refrigerador Midea", 6, false), new MapItem("66878-1.2", "Freezer Consul horizontal", 2, false)),
          List.of(new MapItem("74684-4.2", "Refrigerador Consul", 4, false), new MapItem("75480-1.2", "Refrigerador Midea", 6, false), new MapItem("66878-1.2", "Freezer Consul horizontal", 3, false)),
          List.of(new MapItem("88012-1.2", "Micro-ondas 32L", 8, false), new MapItem("90144-1.2", "Air fryer 12L", 5, false), new MapItem("73578-1.2", "Lavadora Midea", 2, false)),
          List.of(new MapItem("66220-1.2", "Fogão 5 bocas", 4, false), new MapItem("71390-1.2", "Aspirador 1800W", 7, false), new MapItem("80818-1.2", "Cafeteira elétrica", 6, false))
      );
      map.items.addAll(variants.get(variant).stream().map(item -> new MapItem(item.sku, item.name, item.quantity, item.ok)).toList());
      return map;
    }

    Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("id", id);
      map.put("route", route);
      map.put("client", client);
      map.put("carrier", carrier);
      map.put("branch", branch);
      map.put("date", date);
      map.put("status", status);
      map.put("createdBy", createdBy);
      map.put("attachmentName", attachmentName == null ? "" : attachmentName);
      map.put("attachmentType", attachmentType == null ? "" : attachmentType);
      map.put("attachmentPath", attachmentPath == null ? "" : attachmentPath);
      map.put("evidenceName", evidenceName == null ? "" : evidenceName);
      map.put("evidencePath", evidencePath == null ? "" : evidencePath);
      map.put("evidenceAt", evidenceAt == null ? "" : evidenceAt);
      map.put("evidenceBy", evidenceBy == null ? "" : evidenceBy);
      map.put("items", items.stream().map(MapItem::toMap).toList());
      return map;
    }

    static CargoMap fromMap(Map<String, Object> map) {
      CargoMap cargo = new CargoMap();
      cargo.id = string(map.get("id"));
      cargo.route = string(map.get("route"));
      cargo.client = string(map.get("client"));
      cargo.carrier = string(map.get("carrier"));
      cargo.branch = string(map.get("branch"));
      cargo.date = string(map.get("date"));
      cargo.status = string(map.get("status"));
      cargo.createdBy = string(map.get("createdBy"));
      cargo.attachmentName = string(map.get("attachmentName"));
      cargo.attachmentType = string(map.get("attachmentType"));
      cargo.attachmentPath = string(map.get("attachmentPath"));
      cargo.evidenceName = string(map.get("evidenceName"));
      cargo.evidencePath = string(map.get("evidencePath"));
      cargo.evidenceAt = string(map.get("evidenceAt"));
      cargo.evidenceBy = string(map.get("evidenceBy"));
      cargo.items = list(map.get("items")).stream().map(item -> MapItem.fromMap(castMap(item))).toList();
      return cargo;
    }
  }

  private static class MapItem {
    String sku;
    String name;
    String barcode;
    int quantity;
    int checkedQuantity;
    boolean ok;

    MapItem(String sku, String name, int quantity, boolean ok) {
      this.sku = sku;
      this.name = name;
      this.barcode = barcodeFor(sku);
      this.quantity = quantity;
      this.ok = ok;
    }

    Map<String, Object> toMap() {
      return Map.of(
          "sku", sku,
          "name", name,
          "barcode", barcode,
          "quantity", quantity,
          "checkedQuantity", checkedQuantity,
          "ok", ok
      );
    }

    static MapItem fromMap(Map<String, Object> map) {
      MapItem item = new MapItem(string(map.get("sku")), string(map.get("name")), number(map.get("quantity")), Boolean.TRUE.equals(map.get("ok")));
      String storedBarcode = string(map.get("barcode"));
      if (!storedBarcode.isBlank()) item.barcode = storedBarcode;
      String checked = string(map.get("checkedQuantity"));
      item.checkedQuantity = checked.isBlank() ? 0 : number(map.get("checkedQuantity"));
      return item;
    }

    private static String barcodeFor(String sku) {
      return switch (sku) {
        case "75480-1.2" -> "7548143";
        default -> digits(sku);
      };
    }
  }

  record CountItem(String sku, int system, int counted) {
    Map<String, Object> toMap() {
      return Map.of("sku", sku, "system", system, "counted", counted);
    }
  }

  record CountImportResult(
      List<CountItem> items,
      List<String> warnings,
      BalancePdfParser.Metrics metrics
  ) {}

  private record ErrorRecord(String order, String issue, String owner) {
    Map<String, Object> toMap() {
      return Map.of("order", order, "issue", issue, "owner", owner);
    }
  }

  private record HistoryRecord(String at, String userName, String action, String description) {
    Map<String, Object> toMap() {
      return Map.of("at", at, "userName", userName, "action", action, "description", description);
    }
  }

  private static class AdminNotification {
    final String id;
    final String mapId;
    final String title;
    final String message;
    final String at;
    final List<String> readBy;

    AdminNotification(String id, String mapId, String title, String message, String at, List<String> readBy) {
      this.id = id;
      this.mapId = mapId;
      this.title = title;
      this.message = message;
      this.at = at;
      this.readBy = readBy;
    }

    Map<String, Object> toMap(String userId) {
      return Map.of(
          "id", id,
          "mapId", mapId,
          "title", title,
          "message", message,
          "at", at,
          "read", readBy.contains(userId)
      );
    }

    Map<String, Object> toStoredMap() {
      return Map.of("id", id, "mapId", mapId, "title", title, "message", message, "at", at, "readBy", readBy);
    }

    static AdminNotification fromMap(Map<String, Object> map) {
      return new AdminNotification(
          string(map.get("id")),
          string(map.get("mapId")),
          string(map.get("title")),
          string(map.get("message")),
          string(map.get("at")),
          new ArrayList<>(list(map.get("readBy")).stream().map(MmCheckServer::string).toList())
      );
    }
  }

  private static class Database {
    List<User> users = new ArrayList<>();
    List<CargoMap> maps = new ArrayList<>();
    List<CountItem> counts = new ArrayList<>();
    String countsUpdatedAt = "";
    String countsSourceName = "";
    List<String> countsImportWarnings = new ArrayList<>();
    BalancePdfParser.Metrics countsImportMetrics = BalancePdfParser.Metrics.empty();
    List<ErrorRecord> errors = new ArrayList<>();
    List<HistoryRecord> historyEvents = new ArrayList<>();
    List<AdminNotification> notifications = new ArrayList<>();

    static Database load() throws IOException {
      Optional<String> stored = persistence.load();
      if (stored.isEmpty()) {
        Database created = seed();
        created.save();
        return created;
      }
      Map<String, Object> map = castMap(Json.parse(stored.get()));
      Database db = new Database();
      db.users = new ArrayList<>(list(map.get("users")).stream().map(item -> User.fromMap(castMap(item))).toList());
      db.maps = new ArrayList<>(list(map.get("maps")).stream().map(item -> CargoMap.fromMap(castMap(item))).toList());
      db.counts = new ArrayList<>(list(map.get("counts")).stream().map(item -> {
        Map<String, Object> count = castMap(item);
        return new CountItem(string(count.get("sku")), number(count.get("system")), number(count.get("counted")));
      }).toList());
      db.countsUpdatedAt = string(map.get("countsUpdatedAt"));
      db.countsSourceName = string(map.get("countsSourceName"));
      db.countsImportWarnings = new ArrayList<>(list(map.get("countsImportWarnings")).stream()
          .map(MmCheckServer::string).toList());
      db.countsImportMetrics = BalancePdfParser.Metrics.fromMap(castMap(map.get("countsImportMetrics")));
      db.errors = new ArrayList<>(list(map.get("errors")).stream().map(item -> {
        Map<String, Object> error = castMap(item);
        return new ErrorRecord(string(error.get("order")), string(error.get("issue")), string(error.get("owner")));
      }).toList());
      List<Object> storedHistory = list(map.get("historyEvents"));
      if (storedHistory.isEmpty()) storedHistory = list(map.get("auditLog"));
      db.historyEvents = new ArrayList<>(storedHistory.stream().map(item -> {
        Map<String, Object> event = castMap(item);
        return new HistoryRecord(
            string(event.get("at")),
            string(event.get("userName")),
            string(event.get("action")),
            string(event.get("description"))
        );
      }).toList());
      boolean migrated = false;
      if (db.countsUpdatedAt.isBlank()) {
        db.countsUpdatedAt = db.historyEvents.stream()
            .filter(event -> "count_upload".equals(event.action()))
            .map(HistoryRecord::at)
            .reduce((first, second) -> second)
            .orElse("");
        migrated = !db.countsUpdatedAt.isBlank();
      }
      if (db.countsSourceName.isBlank()) {
        String description = db.historyEvents.stream()
            .filter(event -> "count_upload".equals(event.action()))
            .map(HistoryRecord::description)
            .reduce((first, second) -> second)
            .orElse("");
        int start = description.indexOf("PDF ");
        int end = description.lastIndexOf(" com ");
        if (start >= 0 && end > start + 4) {
          db.countsSourceName = description.substring(start + 4, end);
          migrated = true;
        }
      }
      db.notifications = new ArrayList<>(list(map.get("notifications")).stream()
          .map(item -> AdminNotification.fromMap(castMap(item))).toList());
      boolean removedExamples = db.maps.removeIf(Database::isLegacyExampleMap);
      removedExamples |= db.errors.removeIf(error -> "15727".equals(error.order));
      if (removedExamples || migrated) db.save();
      return db;
    }

    static Database seed() throws IOException {
      Database db = new Database();
      String adminPassword = System.getenv("MMCHECK_ADMIN_PASSWORD");
      if (adminPassword == null || adminPassword.isBlank()) {
        throw new IOException("Defina MMCHECK_ADMIN_PASSWORD antes de criar o primeiro banco de dados.");
      }
      db.users.add(new User(UUID.randomUUID().toString(), "Marcos", "Marcos", "admin", "Administrador", hash(adminPassword)));
      return db;
    }

    private static boolean isLegacyExampleMap(CargoMap map) {
      return List.of("15728", "15729").contains(map.id)
          && "Marcos".equalsIgnoreCase(map.createdBy)
          && (map.attachmentName == null || map.attachmentName.isBlank());
    }

    Optional<User> findUserByUsername(String username) {
      return users.stream().filter(user -> user.username.equalsIgnoreCase(username)).findFirst();
    }

    void recordHistory(User user, String action, String description) {
      historyEvents.add(new HistoryRecord(Instant.now().toString(), user.name, action, description));
    }

    void save() throws IOException {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("users", users.stream().map(User::toMap).toList());
      map.put("maps", maps.stream().map(CargoMap::toMap).toList());
      map.put("counts", counts.stream().map(CountItem::toMap).toList());
      map.put("countsUpdatedAt", countsUpdatedAt);
      map.put("countsSourceName", countsSourceName);
      map.put("countsImportWarnings", countsImportWarnings);
      map.put("countsImportMetrics", countsImportMetrics.toMap());
      map.put("errors", errors.stream().map(ErrorRecord::toMap).toList());
      map.put("historyEvents", historyEvents.stream().map(HistoryRecord::toMap).toList());
      map.put("notifications", notifications.stream().map(AdminNotification::toStoredMap).toList());
      persistence.save(Json.stringify(map));
    }
  }

  private static int number(Object value) {
    return value instanceof Number n ? n.intValue() : Integer.parseInt(string(value));
  }

  private static int integerField(Map<String, Object> item, String field) {
    try {
      return number(item.get(field));
    } catch (RuntimeException error) {
      throw new ApiException(400, "Valor inteiro inválido no campo " + field + ".");
    }
  }

  private static List<Object> list(Object value) {
    return value instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
  }

  private static class Json {
    static Object parse(String text) {
      return new Parser(text).parseValue();
    }

    static String stringify(Object value) {
      if (value == null) return "null";
      if (value instanceof String text) return "\"" + escape(text) + "\"";
      if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
      if (value instanceof Map<?, ?> map) {
        List<String> entries = new ArrayList<>();
        map.forEach((key, item) -> entries.add(stringify(String.valueOf(key)) + ":" + stringify(item)));
        return "{" + String.join(",", entries) + "}";
      }
      if (value instanceof Iterable<?> iterable) {
        List<String> items = new ArrayList<>();
        iterable.forEach(item -> items.add(stringify(item)));
        return "[" + String.join(",", items) + "]";
      }
      return stringify(String.valueOf(value));
    }

    private static String escape(String text) {
      return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
  }

  private static class Parser {
    private final String text;
    private int index;

    Parser(String text) {
      this.text = text;
    }

    Object parseValue() {
      skip();
      if (peek() == '"') return parseString();
      if (peek() == '{') return parseObject();
      if (peek() == '[') return parseArray();
      if (text.startsWith("true", index)) { index += 4; return true; }
      if (text.startsWith("false", index)) { index += 5; return false; }
      if (text.startsWith("null", index)) { index += 4; return null; }
      return parseNumber();
    }

    private Map<String, Object> parseObject() {
      Map<String, Object> map = new LinkedHashMap<>();
      index++;
      skip();
      while (peek() != '}') {
        String key = parseString();
        skip();
        index++;
        Object value = parseValue();
        map.put(key, value);
        skip();
        if (peek() == ',') {
          index++;
          skip();
        }
      }
      index++;
      return map;
    }

    private List<Object> parseArray() {
      List<Object> list = new ArrayList<>();
      index++;
      skip();
      while (peek() != ']') {
        list.add(parseValue());
        skip();
        if (peek() == ',') {
          index++;
          skip();
        }
      }
      index++;
      return list;
    }

    private String parseString() {
      StringBuilder builder = new StringBuilder();
      index++;
      while (index < text.length()) {
        char c = text.charAt(index++);
        if (c == '"') break;
        if (c == '\\' && index < text.length()) {
          char next = text.charAt(index++);
          builder.append(switch (next) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            default -> next;
          });
        } else {
          builder.append(c);
        }
      }
      return builder.toString();
    }

    private Number parseNumber() {
      int start = index;
      while (index < text.length() && "-0123456789.".indexOf(text.charAt(index)) >= 0) index++;
      String number = text.substring(start, index);
      return number.contains(".") ? Double.parseDouble(number) : Long.parseLong(number);
    }

    private char peek() {
      return index < text.length() ? text.charAt(index) : '\0';
    }

    private void skip() {
      while (index < text.length() && (Character.isWhitespace(text.charAt(index)) || text.charAt(index) == '\uFEFF')) index++;
    }
  }
}
