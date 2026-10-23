package com.campus.studyroom.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Checks the OpenAPI schema subset used by our wire responses, against real HTTP JSON. */
final class ContractAssertions {
  final Map<String, Object> document;

  ContractAssertions(String name) throws Exception {
    Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (!Files.exists(root.resolve("contracts/openapi/" + name))) {
      root = root.getParent();
      if (root == null) throw new IllegalStateException("Contract root not found");
    }
    document =
        map(
            Store.JSON.readValue(
                Files.readString(root.resolve("contracts/openapi/" + name)), Object.class));
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object o) {
    return (Map<String, Object>) o;
  }

  Map<String, Object> resolve(Map<String, Object> value) {
    if (!value.containsKey("$ref")) return value;
    String pointer = value.get("$ref").toString();
    assertTrue(pointer.startsWith("#/"), "Only local schema refs are used in this contract");
    Object current = document;
    for (String part : pointer.substring(2).split("/")) current = map(current).get(part);
    assertNotNull(current, "Broken contract reference " + pointer);
    return resolve(map(current));
  }

  void response(String method, String path, int status, Map<String, Object> body) {
    String route = path.split("\\?", 2)[0];
    var entry =
        map(document.get("paths")).entrySet().stream()
            .filter(x -> route.matches(x.getKey().replace("{id}", "[^/]+")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Undocumented route " + route));
    Map<String, Object> operation = map(map(entry.getValue()).get(method.toLowerCase(Locale.ROOT)));
    assertNotNull(operation, "Undocumented method " + method + " " + route);
    Map<String, Object> response =
        resolve(map(map(operation.get("responses")).get(Integer.toString(status))));
    assertNotNull(response, "Undocumented status " + status);
    if (status == 204) {
      assertTrue(body.isEmpty(), "204 must have no body");
      return;
    }
    Map<String, Object> schema =
        map(map(map(response.get("content")).get("application/json")).get("schema"));
    validate(schema, body, route + " response");
  }

  void validate(Map<String, Object> original, Object value, String location) {
    Map<String, Object> s = resolve(original);
    if (value == null) {
      assertTrue(Boolean.TRUE.equals(s.get("nullable")), location + " is unexpectedly null");
      return;
    }
    if (s.containsKey("enum"))
      assertTrue(
          ((List<?>) s.get("enum"))
              .stream()
                  .anyMatch(
                      x ->
                          Objects.equals(x, value)
                              || x instanceof Number a
                                  && value instanceof Number b
                                  && a.doubleValue() == b.doubleValue()),
          location + " has an undeclared enum value");
    String type = Objects.toString(s.get("type"), "");
    switch (type) {
      case "object" -> {
        assertInstanceOf(Map.class, value, location);
        var record = map(value);
        var properties = map(s.getOrDefault("properties", Map.of()));
        for (Object key : (List<?>) s.getOrDefault("required", List.of()))
          assertTrue(record.containsKey(key), location + " missing " + key);
        for (var item : record.entrySet()) {
          if (properties.containsKey(item.getKey()))
            validate(
                map(properties.get(item.getKey())),
                item.getValue(),
                location + "." + item.getKey());
          else
            assertNotEquals(
                Boolean.FALSE,
                s.get("additionalProperties"),
                location + " has undocumented field " + item.getKey());
        }
      }
      case "array" -> {
        assertInstanceOf(List.class, value, location);
        for (Object element : (List<?>) value)
          validate(map(s.get("items")), element, location + "[]");
      }
      case "string" -> {
        assertInstanceOf(String.class, value, location);
        String text = value.toString();
        if ("uuid".equals(s.get("format"))) UUID.fromString(text);
        if ("date-time".equals(s.get("format"))) OffsetDateTime.parse(text);
        if ("date".equals(s.get("format"))) LocalDate.parse(text);
        if (s.containsKey("pattern"))
          assertTrue(text.matches(s.get("pattern").toString()), location + " violates format");
      }
      case "boolean" -> assertInstanceOf(Boolean.class, value, location);
      case "integer", "number" -> {
        assertInstanceOf(Number.class, value, location);
        double number = ((Number) value).doubleValue();
        if (type.equals("integer")) assertEquals(Math.rint(number), number, location);
        if (s.containsKey("minimum"))
          assertTrue(number >= ((Number) s.get("minimum")).doubleValue(), location);
        if (s.containsKey("maximum"))
          assertTrue(number <= ((Number) s.get("maximum")).doubleValue(), location);
      }
      case "" -> {
        /* Explicit action-dependent audit JSON. */
      }
      default -> fail("Unsupported schema type " + type);
    }
  }
}
