package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "room_checkin_code", schema = "core")
public class CheckinCodeEntity {
  @Id public UUID id;
  public UUID roomId;
  public LocalDate localDate;
  public String codeHash;
  public String codeCiphertext;
  public Instant expiresAt;
}
