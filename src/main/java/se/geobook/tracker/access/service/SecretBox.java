package se.geobook.tracker.access.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts 2FA secrets with AES-256-GCM. The key is 32 random bytes in {@code secret.key} in the
 * data directory, created with owner-only permissions on first start. A copied database without
 * that file cannot generate codes.
 */
@Component
final class SecretBox {
  static final String FILE_NAME = "secret.key";
  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final byte[] CONTEXT = "geobook totp secret v1".getBytes(StandardCharsets.UTF_8);
  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();

  SecretBox(@Value("${geobook.data-directory:./data}") String directory) {
    this.key = new SecretKeySpec(readOrCreate(Path.of(directory).toAbsolutePath()), "AES");
  }

  byte[] seal(byte[] plain) {
    final byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    final byte[] sealed = run(Cipher.ENCRYPT_MODE, nonce, plain);
    return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
  }

  byte[] open(byte[] stored) {
    if (stored.length <= NONCE_BYTES) {
      throw new IllegalStateException("The stored 2FA secret is damaged.");
    }
    return run(
        Cipher.DECRYPT_MODE,
        Arrays.copyOf(stored, NONCE_BYTES),
        Arrays.copyOfRange(stored, NONCE_BYTES, stored.length));
  }

  private byte[] run(int mode, byte[] nonce, byte[] input) {
    try {
      final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(CONTEXT);
      return cipher.doFinal(input);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(
          "The 2FA secret could not be decrypted. Was " + FILE_NAME + " replaced?", exception);
    }
  }

  private byte[] readOrCreate(Path directory) {
    final Path file = directory.resolve(FILE_NAME);
    try {
      Files.createDirectories(directory);
      if (!Files.exists(file)) {
        create(file);
      }
      final byte[] bytes = Files.readAllBytes(file);
      if (bytes.length != KEY_BYTES) {
        throw new IllegalStateException(
            file + " must hold " + KEY_BYTES + " bytes. Restore it from a backup.");
      }
      return bytes;
    } catch (IOException exception) {
      throw new UncheckedIOException("Could not read or create " + file, exception);
    }
  }

  private void create(Path file) throws IOException {
    final byte[] bytes = new byte[KEY_BYTES];
    random.nextBytes(bytes);
    final Path temporary = file.resolveSibling(FILE_NAME + ".new");
    Files.deleteIfExists(temporary);
    if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.createFile(
          temporary,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } else {
      Files.createFile(temporary);
    }
    Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
    try {
      Files.move(temporary, file);
    } catch (FileAlreadyExistsException exception) {
      // Another process created it first; use theirs.
      Files.delete(temporary);
    }
  }
}
