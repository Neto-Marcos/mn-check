import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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
  private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "4173"));
  private static final Path ROOT = Path.of("").toAbsolutePath();
  private static final Path FRONTEND = ROOT.resolve("frontend");
  private static final Path DB_PATH = Path.of(
      System.getenv().getOrDefault("MMCHECK_DB_PATH", ROOT.resolve("data").resolve("java-db.json").toString())
  ).toAbsolutePath();
  private static final Path UPLOAD_DIR = Path.of(
      System.getenv().getOrDefault("MMCHECK_UPLOAD_DIR", ROOT.resolve("data").resolve("uploads").toString())
  ).toAbsolutePath();
  private static final Map<String, String> SESSIONS = new LinkedHashMap<>();
  private static HttpServer server;
  private static ExecutorService executor;
  private static Database db;

  public static void main(String[] args) throws Exception {
    Files.createDirectories(DB_PATH.getParent());
    db = Database.load(DB_PATH);

    server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
    server.createContext("/", MmCheckServer::handle);
    executor = Executors.newFixedThreadPool(8);
    server.setExecutor(executor);
    server.start();
    System.out.println("MM check Java rodando em http://0.0.0.0:" + PORT);
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
      json(exchange, 200, Map.of("status", "ok", "app", "MM check"));
      return;
    }

    User user = requireUser(exchange);

    if ("GET".equals(method) && "/api/bootstrap".equals(path)) {
      json(exchange, 200, visibleData(user));
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
      db.audit(user, "create_user", "Usuário " + username + " cadastrado");
      db.save(DB_PATH);
      json(exchange, 201, Map.of("user", created.publicMap()));
      return;
    }

    if ("POST".equals(method) && "/api/maps/upload".equals(path)) {
      requireAdmin(user);
      Map<String, Object> body = readJson(exchange);
      String fileName = string(body.get("fileName")).trim();
      String contentType = string(body.get("contentType")).trim();
      String dataUrl = string(body.get("dataUrl")).trim();
      if (fileName.isBlank() || dataUrl.isBlank()) {
        throw new ApiException(400, "Selecione um PDF ou imagem para importar.");
      }
      if (!List.of("application/pdf", "image/png", "image/jpeg").contains(contentType)) {
        throw new ApiException(400, "Formato não permitido. Use PDF, PNG ou JPG.");
      }

      Files.createDirectories(UPLOAD_DIR);
      String next = String.valueOf(db.maps.stream().mapToInt(item -> Integer.parseInt(item.id)).max().orElse(15727) + 1);
      String storedName = next + "-" + safeFileName(fileName);
      Files.write(UPLOAD_DIR.resolve(storedName), decodeDataUrl(dataUrl));

      CargoMap map = CargoMap.sample(next, user.id);
      map.client = "Mapa importado";
      map.attachmentName = fileName;
      map.attachmentType = contentType;
      map.attachmentPath = "data/uploads/" + storedName;
      db.maps.add(0, map);
      db.audit(user, "upload_map", "Mapa " + next + " criado por upload: " + fileName);
      db.save(DB_PATH);
      json(exchange, 201, visibleData(user));
      return;
    }

    if ("POST".equals(method) && "/api/counts/upload".equals(path)) {
      if (!List.of("admin", "stock").contains(user.role)) throw new ApiException(403, "Ação não permitida.");
      Map<String, Object> body = readJson(exchange);
      String fileName = string(body.get("fileName")).trim();
      String dataUrl = string(body.get("dataUrl")).trim();
      List<Object> rows = list(body.get("counts"));
      if (fileName.isBlank() || dataUrl.isBlank()) throw new ApiException(400, "Selecione um PDF de saldo.");
      if (rows.isEmpty()) throw new ApiException(400, "Nenhum SKU foi identificado no PDF.");

      List<CountItem> imported = new ArrayList<>();
      for (Object row : rows) {
        Map<String, Object> item = castMap(row);
        String sku = string(item.get("sku")).trim();
        if (sku.isBlank()) continue;
        imported.add(new CountItem(sku, number(item.get("system")), number(item.get("counted"))));
      }
      if (imported.isEmpty()) throw new ApiException(400, "Nenhum SKU válido foi identificado no PDF.");

      Files.createDirectories(UPLOAD_DIR);
      String storedName = "contagem-" + System.currentTimeMillis() + "-" + safeFileName(fileName);
      Files.write(UPLOAD_DIR.resolve(storedName), decodeDataUrl(dataUrl));
      db.counts = imported;
      db.audit(user, "count_upload", "Saldo atualizado pelo PDF " + fileName + " com " + imported.size() + " SKUs");
      db.save(DB_PATH);
      json(exchange, 200, visibleData(user));
      return;
    }

    if ("PATCH".equals(method) && "/api/counts".equals(path)) {
      if (!List.of("admin", "stock").contains(user.role)) throw new ApiException(403, "Ação não permitida.");
      Map<String, Object> body = readJson(exchange);
      List<Object> rows = list(body.get("counts"));
      if (rows.isEmpty()) throw new ApiException(400, "Não há contagens para atualizar.");
      List<CountItem> updated = new ArrayList<>();
      for (Object row : rows) {
        Map<String, Object> item = castMap(row);
        String sku = string(item.get("sku")).trim();
        if (sku.isBlank()) continue;
        updated.add(new CountItem(sku, number(item.get("system")), number(item.get("counted"))));
      }
      db.counts = updated;
      db.audit(user, "update_counts", "Contagem física atualizada em " + updated.size() + " SKUs");
      db.save(DB_PATH);
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
        db.audit(user, "update_item", "Mapa " + map.id + ": " + item.sku);
        db.save(DB_PATH);
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
          db.audit(user, "scan_item", "Mapa " + map.id + ": código " + code + " conferido");
          db.save(DB_PATH);
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

          Files.createDirectories(UPLOAD_DIR);
          String storedName = "evidence-" + map.id + "-" + System.currentTimeMillis() + "-" + safeFileName(fileName);
          Files.write(UPLOAD_DIR.resolve(storedName), image);
          map.evidenceName = fileName;
          map.evidencePath = "data/uploads/" + storedName;
          map.evidenceAt = Instant.now().toString();
          map.evidenceBy = user.name;
          db.audit(user, "camera_evidence", "Foto de conferência registrada no mapa " + map.id);
        } else if ("send-conference".equals(action)) {
          if (!List.of("admin", "separation").contains(user.role)) throw new ApiException(403, "Ação não permitida.");
          if (!map.items.stream().allMatch(item -> item.ok)) throw new ApiException(400, "Todos os itens precisam estar ok.");
          map.status = "aguardando conferencia";
          db.audit(user, "send_conference", "Mapa " + map.id + " enviado para conferência");
        } else if ("approve".equals(action)) {
          if (!List.of("admin", "expedition").contains(user.role)) throw new ApiException(403, "Ação não permitida.");
          if (!map.items.stream().allMatch(item -> item.checkedQuantity >= item.quantity)) {
            throw new ApiException(400, "Confira todas as unidades antes de finalizar o mapa.");
          }
          map.status = "conferido";
          db.audit(user, "approve_map", "Mapa " + map.id + " conferido sem divergência");
        } else if ("problem".equals(action)) {
          if (!List.of("admin", "expedition").contains(user.role)) throw new ApiException(403, "Ação não permitida.");
          map.status = "corrigir problema";
          db.errors.add(0, new ErrorRecord(map.id, "Divergência na conferência", user.name));
          db.audit(user, "problem_map", "Mapa " + map.id + " marcado com divergência");
        } else if ("corrected".equals(action)) {
          if (!List.of("admin", "expedition").contains(user.role)) throw new ApiException(403, "Ação não permitida.");
          if (!"corrigir problema".equals(map.status)) throw new ApiException(403, "Mapa fora da etapa de correção.");
          map.status = "conferido";
          db.audit(user, "corrected_map", "Mapa " + map.id + " corrigido e conferido");
        } else {
          throw new ApiException(404, "Rota não encontrada.");
        }
        db.save(DB_PATH);
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

    return Map.of(
        "user", user.publicMap(),
        "maps", maps.stream().map(CargoMap::toMap).toList(),
        "users", "admin".equals(user.role) ? db.users.stream().map(User::publicMap).toList() : List.of(),
        "counts", List.of("admin", "stock").contains(user.role) ? db.counts.stream().map(CountItem::toMap).toList() : List.of(),
        "errors", "admin".equals(user.role) ? db.errors.stream().map(ErrorRecord::toMap).toList() : List.of(),
        "auditLog", "admin".equals(user.role) ? db.auditLog.stream().map(AuditRecord::toMap).toList() : List.of(),
        "metrics", Map.of("separating", separating, "waiting", waiting, "perfect", perfect, "errorCount", db.errors.size())
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
    for (String key : List.of("username", "password", "name", "role", "fileName", "contentType", "dataUrl")) {
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
    int comma = dataUrl.indexOf(",");
    String encoded = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
    return Base64.getDecoder().decode(encoded);
  }

  private static String safeFileName(String fileName) {
    String cleaned = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    return cleaned.isBlank() ? "mapa.pdf" : cleaned;
  }

  private static String digits(String value) {
    return value == null ? "" : value.replaceAll("\\D", "");
  }

  private static String extension(Path file) {
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf(".");
    return dot >= 0 ? name.substring(dot) : "";
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static class ApiException extends RuntimeException {
    final int status;
    ApiException(int status, String message) {
      super(message);
      this.status = status;
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

  private record CountItem(String sku, int system, int counted) {
    Map<String, Object> toMap() {
      return Map.of("sku", sku, "system", system, "counted", counted);
    }
  }

  private record ErrorRecord(String order, String issue, String owner) {
    Map<String, Object> toMap() {
      return Map.of("order", order, "issue", issue, "owner", owner);
    }
  }

  private record AuditRecord(String at, String userName, String action, String description) {
    Map<String, Object> toMap() {
      return Map.of("at", at, "userName", userName, "action", action, "description", description);
    }
  }

  private static class Database {
    List<User> users = new ArrayList<>();
    List<CargoMap> maps = new ArrayList<>();
    List<CountItem> counts = new ArrayList<>();
    List<ErrorRecord> errors = new ArrayList<>();
    List<AuditRecord> auditLog = new ArrayList<>();

    static Database load(Path file) throws IOException {
      if (!Files.exists(file)) {
        Database created = seed();
        created.save(file);
        return created;
      }
      Map<String, Object> map = castMap(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
      Database db = new Database();
      db.users = new ArrayList<>(list(map.get("users")).stream().map(item -> User.fromMap(castMap(item))).toList());
      db.maps = new ArrayList<>(list(map.get("maps")).stream().map(item -> CargoMap.fromMap(castMap(item))).toList());
      db.counts = new ArrayList<>(list(map.get("counts")).stream().map(item -> {
        Map<String, Object> count = castMap(item);
        return new CountItem(string(count.get("sku")), number(count.get("system")), number(count.get("counted")));
      }).toList());
      db.errors = new ArrayList<>(list(map.get("errors")).stream().map(item -> {
        Map<String, Object> error = castMap(item);
        return new ErrorRecord(string(error.get("order")), string(error.get("issue")), string(error.get("owner")));
      }).toList());
      db.auditLog = new ArrayList<>(list(map.get("auditLog")).stream().map(item -> {
        Map<String, Object> audit = castMap(item);
        return new AuditRecord(string(audit.get("at")), string(audit.get("userName")), string(audit.get("action")), string(audit.get("description")));
      }).toList());
      return db;
    }

    static Database seed() throws IOException {
      Database db = new Database();
      String adminPassword = System.getenv("MMCHECK_ADMIN_PASSWORD");
      if (adminPassword == null || adminPassword.isBlank()) {
        throw new IOException("Defina MMCHECK_ADMIN_PASSWORD antes de criar o primeiro banco de dados.");
      }
      db.users.add(new User(UUID.randomUUID().toString(), "Marcos", "Marcos", "admin", "Administrador", hash(adminPassword)));
      db.maps.add(CargoMap.sample("15728", "Marcos"));
      CargoMap second = CargoMap.sample("15729", "Marcos");
      second.client = "RENOVA MATERIAIS DE CONSTRUÇÃO";
      second.status = "aguardando conferencia";
      second.items.get(0).ok = true;
      second.items.get(1).ok = true;
      second.items.get(2).ok = true;
      db.maps.add(second);
      db.errors.add(new ErrorRecord("15727", "Quantidade divergente no mapa", "Expedição"));
      return db;
    }

    Optional<User> findUserByUsername(String username) {
      return users.stream().filter(user -> user.username.equalsIgnoreCase(username)).findFirst();
    }

    void audit(User user, String action, String description) {
      auditLog.add(new AuditRecord(Instant.now().toString(), user.name, action, description));
    }

    void save(Path file) throws IOException {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("users", users.stream().map(User::toMap).toList());
      map.put("maps", maps.stream().map(CargoMap::toMap).toList());
      map.put("counts", counts.stream().map(CountItem::toMap).toList());
      map.put("errors", errors.stream().map(ErrorRecord::toMap).toList());
      map.put("auditLog", auditLog.stream().map(AuditRecord::toMap).toList());
      Files.writeString(file, Json.stringify(map), StandardCharsets.UTF_8);
    }
  }

  private static int number(Object value) {
    return value instanceof Number n ? n.intValue() : Integer.parseInt(string(value));
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
