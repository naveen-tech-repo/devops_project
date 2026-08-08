# ============================
# Runtime Image optiisation
# ============================

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Create non-root user
RUN addgroup -S spring && \
    adduser -S spring -G spring

# Copy JAR created by Maven/GitHub Actions
COPY target/app.jar app.jar

# Run application as non-root user
USER spring:spring

EXPOSE 8081

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
