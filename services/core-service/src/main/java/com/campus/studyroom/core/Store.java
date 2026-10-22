package com.campus.studyroom.core;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class Store {
  @PersistenceContext EntityManager em;
  static final JsonMapper JSON = JsonMapper.builder().build();

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> list(String sql, Object... params) {
    var q =
        em.createNativeQuery(
            "select cast(row_to_json(q) as text) from (" + sql + ") q", String.class);
    bind(q, params);
    return ((List<String>) q.getResultList())
        .stream()
            .map(s -> (Map<String, Object>) JSON.readValue(s, Map.class))
            .map(Store::camelMap)
            .toList();
  }

  public com.campus.studyroom.api.PageData<Map<String, Object>> page(
      String sql, int page, int size, Object... params) {
    if (page < 1 || size < 1 || size > 100)
      throw new com.campus.studyroom.api.ApiException(400, "INVALID_PAGE", "页码从1开始，每页1至100条");
    long total = count("select count(*) from (" + sql + ") page_count", params);
    return new com.campus.studyroom.api.PageData<>(
        list(sql + " limit " + size + " offset " + ((long) (page - 1) * size), params),
        page,
        size,
        total);
  }

  public Map<String, Object> one(String sql, Object... p) {
    var l = list(sql, p);
    return l.isEmpty() ? null : l.getFirst();
  }

  public Map<String, Object> need(String sql, Object... p) {
    var m = one(sql, p);
    if (m == null) throw com.campus.studyroom.api.ApiException.notFound();
    return m;
  }

  public void clear() {
    em.clear();
  }

  public void lockGuard(UUID user) {
    if (em.find(BookingGuard.class, user, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
        == null) throw com.campus.studyroom.api.ApiException.notFound();
  }

  public int update(String sql, Object... p) {
    var q = em.createNativeQuery(sql);
    bind(q, p);
    return q.executeUpdate();
  }

  public long count(String sql, Object... p) {
    var q = em.createNativeQuery(sql);
    bind(q, p);
    return ((Number) q.getSingleResult()).longValue();
  }

  void bind(jakarta.persistence.Query q, Object[] p) {
    for (int i = 0; i < p.length; i++) q.setParameter(i + 1, p[i]);
  }

  public void advisory(String key) {
    em.createNativeQuery("select 1 from pg_advisory_xact_lock(hashtextextended(?1,0))", Long.class)
        .setParameter(1, key)
        .getResultList();
  }

  static Map<String, Object> camelMap(Map<String, Object> m) {
    Map<String, Object> r = new LinkedHashMap<>();
    m.forEach(
        (k, v) -> {
          StringBuilder s = new StringBuilder();
          boolean upper = false;
          for (char c : k.toCharArray()) {
            if (c == '_') {
              upper = true;
              continue;
            }
            s.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
          }
          r.put(s.toString(), v);
        });
    return r;
  }

  public static String json(Object value) {
    return JSON.writeValueAsString(value);
  }

  public static UUID id(Object value) {
    return value instanceof UUID u ? u : UUID.fromString(value.toString());
  }

  public static String str(Map<String, Object> m, String k) {
    Object v = m.get(k);
    return v == null ? null : v.toString();
  }

  public static long number(Map<String, Object> m, String k) {
    return ((Number) m.get(k)).longValue();
  }
}
