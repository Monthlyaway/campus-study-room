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
  final com.campus.studyroom.core.persistence.CheckinCodeRepository codes;
  final PasswordEncoder encoder;
  final byte[] key;
  final SecureRandom random = new SecureRandom();

  public CheckinCodes(
      Store d,
      Clock c,
      com.campus.studyroom.core.persistence.CheckinCodeRepository codes,
      PasswordEncoder e,
      @Value("${studyroom.checkin-key}") String k) {
    db = d;
    clock = c;
    this.codes = codes;
    encoder = e;
    key = Base64.getDecoder().decode(k);
    if (key.length != 32)
      throw new IllegalArgumentException("CHECKIN_CODE_ENCRYPTION_KEY must be base64 32 bytes");
  }

  @Transactional
  public Map<String, Object> get(UUID room) {
    LocalDate day = clock.instant().atZone(DomainPolicy.ZONE).toLocalDate();
    db.advisory("code:" + room + ":" + day);
    var r = codes.findByRoomIdAndLocalDate(room, day).orElse(null);
    if (r == null) {
      String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
      StringBuilder s = new StringBuilder();
      for (int i = 0; i < 8; i++) s.append(alphabet.charAt(random.nextInt(alphabet.length())));
      r = new com.campus.studyroom.core.persistence.CheckinCodeEntity();
      r.id = UUID.randomUUID();
      r.roomId = room;
      r.localDate = day;
      r.codeHash = encoder.encode(s);
      r.codeCiphertext = encrypt(s.toString());
      r.expiresAt = day.plusDays(1).atStartOfDay(DomainPolicy.ZONE).toInstant();
      codes.save(r);
    }
    return Map.of(
        "localDate", day.toString(), "code", decrypt(r.codeCiphertext), "expiresAt", r.expiresAt);
  }

  public boolean matches(UUID room, String code) {
    LocalDate day = clock.instant().atZone(DomainPolicy.ZONE).toLocalDate();
    return codes
        .findByRoomIdAndLocalDate(room, day)
        .filter(r -> r.expiresAt.isAfter(clock.instant()))
        .map(r -> encoder.matches(code.trim().toUpperCase(Locale.ROOT), r.codeHash))
        .orElse(false);
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
