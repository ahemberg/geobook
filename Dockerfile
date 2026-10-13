FROM eclipse-temurin:27_35-jre-resolute

WORKDIR /app
COPY target/tracker-0.0.1-SNAPSHOT.jar app.jar
COPY LICENSE LICENSE
LABEL org.opencontainers.image.source="https://github.com/ahemberg/geobook" \
      org.opencontainers.image.licenses="MIT"
ENV GEOBOOK_DATA_DIRECTORY=/data
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
