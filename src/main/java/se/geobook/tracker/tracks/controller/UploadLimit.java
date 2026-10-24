package se.geobook.tracker.tracks.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
record UploadLimit(@Value("${geobook.upload-limit-mb}") int megabytes) {
  UploadLimit {
    if (megabytes <= 0) {
      throw new IllegalArgumentException(
          "GEOBOOK_UPLOAD_LIMIT_MB must be a positive whole number.");
    }
  }
}
