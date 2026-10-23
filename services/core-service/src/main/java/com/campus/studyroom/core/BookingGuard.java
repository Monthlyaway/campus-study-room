package com.campus.studyroom.core;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "booking_guard", schema = "core")
public class BookingGuard {
  @Id
  @Column(name = "user_id")
  public UUID userId;
}
