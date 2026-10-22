package com.campus.studyroom.core;

import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Jobs {
  final TaskExecutor tasks;
  final Notifications notifications;
  final JavaMailSender mail;

  @Value("${studyroom.mail-mode}")
  String mode;

  @Value("${studyroom.mail-directory}")
  String dir;

  @Value("${studyroom.smtp-from}")
  String from;

  @Value("${CONTROL_DIR:./.local/control}")
  String control;

  public Jobs(TaskExecutor t, Notifications n, JavaMailSender m) {
    tasks = t;
    notifications = n;
    mail = m;
  }

  boolean paused() {
    return Files.exists(Path.of(control, "jobs-paused"));
  }

  @Scheduled(fixedDelay = 30000, initialDelay = 3000)
  public void deadlines() {
    if (paused()) return;
    for (UUID id : tasks.due()) {
      if (paused()) break;
      try {
        tasks.run(id);
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(Jobs.class)
            .error("Deadline task failed id={} type={}", id, e.getClass().getSimpleName());
      }
    }
  }

  @Scheduled(fixedDelay = 30000, initialDelay = 5000)
  public void sendMail() {
    if (paused()) return;
    for (var n : notifications.claim()) {
      boolean sent = false;
      try {
        String text = Store.json(n.get("payloadJson"));
        if (mode.equals("capture")) {
          Files.createDirectories(Path.of(dir));
          Files.writeString(
              Path.of(dir, n.get("id") + ".txt"),
              "To: "
                  + n.get("email")
                  + "\nSubject: 校园资源通知 · "
                  + n.get("eventType")
                  + "\n\n"
                  + text);
        } else {
          var message = new SimpleMailMessage();
          message.setFrom(from);
          message.setTo(n.get("email").toString());
          message.setSubject("校园资源通知 · " + n.get("eventType"));
          message.setText(text);
          mail.send(message);
        }
        sent = true;
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(Jobs.class)
            .warn(
                "Mail delivery failed notification={} type={}",
                n.get("id"),
                e.getClass().getSimpleName());
      }
      notifications.delivered(Store.id(n.get("id")), sent);
    }
  }
}
