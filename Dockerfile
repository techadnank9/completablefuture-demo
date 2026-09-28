# ─── build ───────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the POM first so the dependency layer is cached across source edits.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ─── run ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /build/target/completablefuture-demo-1.0.0.jar app.jar

# The platform injects PORT; these two are the knobs worth retuning from the
# hosting dashboard after testing on the actual presentation network.
ENV PORT=10000
ENV DEMO_SIZE_MB=8
ENV DEMO_THROTTLE_MBPS=0.4

EXPOSE 10000

# "serve" runs LocalFileServer as a long-lived public server rather than as a
# fixture inside the demo. exec form so the JVM is PID 1 and receives SIGTERM.
ENTRYPOINT ["java", "-jar", "app.jar", "serve", "--plain"]
