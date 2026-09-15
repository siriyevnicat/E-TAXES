# TaxData 7.1.4 — Railway production image
# Use the official Gradle image so Railway does not need to download the Gradle
# distribution through the wrapper during the build. Dependencies are still
# resolved normally from Maven Central.
FROM gradle:8.14.4-jdk21 AS build
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . .

# bootJar depends on agentBootJar, so one build invocation produces both
# executable artifacts. --stacktrace keeps Railway build logs actionable.
RUN gradle --no-daemon clean bootJar -x test --stacktrace \
    && test -s build/libs/taxdata-server.jar \
    && test -s build/libs/taxdata-agent.jar \
    && test $(stat -c%s build/libs/taxdata-agent.jar) -gt 100000 \
    && jar tf build/libs/taxdata-server.jar | grep -q '^BOOT-INF/classes/agent-dist/taxdata-agent.jar$' \
    && jar tf build/libs/taxdata-server.jar | grep -q '^BOOT-INF/classes/agent/setup.cmd$' \
    && jar tf build/libs/taxdata-server.jar | grep -q '^BOOT-INF/classes/agent/setup.ps1$'

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /home/gradle/project/build/libs/taxdata-server.jar /app/app.jar
COPY --from=build /home/gradle/project/build/libs/taxdata-agent.jar /app/agent/taxdata-agent.jar
RUN chown -R 10001:10001 /app
USER 10001
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENV ETAXES_EXECUTION_MODE=LOCAL_AGENT
ENV TZ=Asia/Baku
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-XX:+ExitOnOutOfMemoryError","-jar","/app/app.jar"]
