package com.campus.studyroom.repair;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

@Component
class RepairJson {
    private final ObjectMapper mapper;
    RepairJson(ObjectMapper mapper) { this.mapper = mapper; }
    String write(Object value) { return mapper.writeValueAsString(value); }
    Map<String, Object> read(String value) { return mapper.readValue(value, new TypeReference<Map<String, Object>>() {}); }
    String hash(Object value) { return digest(write(canonical(value))); }
    static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k,v) -> sorted.put(k.toString(), canonical(v))); return sorted;
        }
        if (value instanceof java.util.Collection<?> list) return list.stream().map(RepairJson::canonical).toList();
        return value;
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
