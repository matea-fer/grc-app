# --- build: prevedi bootJar ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x ./gradlew && ./gradlew --no-daemon clean bootJar -x test

# --- runtime: JRE + grype (CLI) s ugradenom bazom ranjivosti ---
# Grype se ugraduje u sliku i baza se povuce pri buildu -> na hostu ne treba ni grype ni
# Docker socket; SBOM evaluacija radi offline (VALIDATE_AGE=false u GrypeRunneru prihvaca
# zatecenu bazu). Osvjezavanje baze = ponovni build slike.
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -sSfL https://raw.githubusercontent.com/anchore/grype/main/install.sh | sh -s -- -b /usr/local/bin \
    && grype db update \
    && apt-get purge -y curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
# CLI nacin (grype je u slici); heap ogranicen zbog malog Dropleta - povecaj po potrebi.
ENV SBOM_MODE=cli \
    GRYPE_PATH=grype \
    JAVA_OPTS=-Xmx256m
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
