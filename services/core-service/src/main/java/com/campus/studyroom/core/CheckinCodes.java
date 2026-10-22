package com.campus.studyroom.core;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckinCodes {
  final Store db;
  final Clock clock;
  final PasswordEncoder encoder;
  final byte[] key;
  final SecureRandom random = new SecureRandom();

  public CheckinCodes(
      Store d, Clock c, PasswordEncoder e, @Value("${studyroom.checkin-key}") String k) {
    db = d;
    clock = c;
    encoder = e;
    key = Base64.getDecoder().decode(k);
    if (key.length != 32)
      throw new IllegalArgumentException("CHECKIN_CODE_ENCRYPTION_KEY must be base64 32 bytes");
  }

  @Transactional
  public Map<String, Object> get(UUID room) {
    LocalDate day = clock.instant().atZone(DomainPolicy.ZONE).toLocalDate();
    db.advisory("code:" + room + ":" + day);
    var r =
        db.one(
            "select * from core.room_checkin_code where room_id=?1 and local_date=?2", room, day);
    if (r == null) {
      String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
      StringBuilder s = new StringBuilder();
      for (int i = 0; i < 8; i++) s.append(alphabet.charAt(random.nextInt(alphabet.length())));
      Instant end = day.plusDays(1).atStartOfDay(DomainPolicy.ZONE).toInstant();
      db.update(
          "insert into"
              + " core.room_checkin_code(id,room_id,local_date,code_hash,code_ciphertext,expires_at)"
              + " values(?1,?2,?3,?4,?5,?6)",
          UUID.randomUUID(),
          room,
          day,
          encoder.encode(s),
          encrypt(s.toString()),
          end);
      r =
          db.need(
              "select * from core.room_checkin_code where room_id=?1 and local_date=?2", room, day);
    }
    return Map.of(
        "localDate",
        day.toString(),
        "code",
        decrypt(r.get("codeCiphertext").toString()),
        "expiresAt",
        r.get("expiresAt"));
  }

  public boolean matches(UUID room, String code) {
    LocalDate day = clock.instant().atZone(DomainPolicy.ZONE).toLocalDate();
    var r =
        db.one(
            "select code_hash from core.room_checkin_code where room_id=?1 and local_date=?2 and"
                + " expires_at>?3",
            room,
            day,
            clock.instant());
    return r != null
        && encoder.matches(code.trim().toUpperCase(Locale.ROOT), r.get("codeHash").toString());
  }

  String encrypt(String s) {
    try {
      byte[] n = new byte[12];
      random.nextBytes(n);
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, n));
      return "v1:"
          + Base64.getEncoder().encodeToString(n)
          + ":"
          + Base64.getEncoder().encodeToString(c.doFinal(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String decrypt(String s) {
    try {
      String[] p = s.split(":");
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Base64.getDecoder().decode(p[1])));
      return new String(c.doFinal(Base64.getDecoder().decode(p[2])), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
