FROM eclipse-temurin:27_35-jre-resolute

WORKDIR /app
COPY target/tracker-0.0.1-SNAPSHOT.jar app.jar
COPY LICENSE LICENSE
COPY --chmod=0755 src/main/docker/geobook /usr/local/bin/geobook
ENV GEOBOOK_DATA_DIRECTORY=/data
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
