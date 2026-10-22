package com.campus.studyroom.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Authenticates the randomly generated machine secret, separately from human password hashing. */
final class InternalServiceAuthenticationProvider implements AuthenticationProvider {
  private final String username;
  private final byte[] usernameDigest;
  private final byte[] secretDigest;

  InternalServiceAuthenticationProvider(String username, String secret) {
    if (username == null || username.isBlank() || secret == null || secret.isBlank()) {
      throw new IllegalStateException("Internal service credentials must be configured");
    }
    this.username = username;
    usernameDigest = digest(username);
    secretDigest = digest(secret);
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    String suppliedName = authentication.getName();
    Object credentials = authentication.getCredentials();
    byte[] suppliedUser = digest(suppliedName == null ? "" : suppliedName);
    byte[] suppliedSecret = digest(credentials == null ? "" : credentials.toString());
    // Fixed-size digests; always perform both comparisons, including for an unknown username.
    boolean validUser = MessageDigest.isEqual(usernameDigest, suppliedUser);
    boolean validSecret = MessageDigest.isEqual(secretDigest, suppliedSecret);
    if (!(validUser & validSecret)) throw new BadCredentialsException("Invalid credentials");
    return UsernamePasswordAuthenticationToken.authenticated(
        username, null, List.of(new SimpleGrantedAuthority("SERVICE")));
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
    }
  }
}
