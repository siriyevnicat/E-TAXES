# Railway build fix — 6.5.1

This release fixes the `agentBootJar` failure seen on Railway:

`Cannot query the value of task ':agentBootJar' property 'targetJavaVersion' because it has no value available.`

Changes:
- `agentBootJar.targetJavaVersion` is explicitly set to Java 21.
- `agentBootJar` receives `runtimeClasspath` resolved artifact metadata, matching Spring Boot's standard `bootJar` configuration.
- Gradle wrapper is pinned to 8.14.4. Spring Boot 3.5.x officially supports Gradle 8.x; Gradle 9.0.0 is avoided for production compatibility.
- Wrapper network timeout is increased to 60 seconds.
