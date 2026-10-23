package se.geobook.tracker.tracks.controller;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

// Multipart parsing can fail before Spring selects a controller.
@RestControllerAdvice
class UploadErrors {
  private final int uploadLimitMb;

  UploadErrors(@Value("${geobook.upload-limit-mb}") int uploadLimitMb) {
    this.uploadLimitMb = uploadLimitMb;
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<Map<String, String>> tooLarge() {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(
            Map.of(
                "message",
                "Upload exceeds the " + uploadLimitMb + " MB limit. Choose a smaller file."));
  }
}
