plugins {
    id("java")
    id("application")
}

group = "com.operit.aiclaw"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.aliyun.com/repository/public")
    }
}

dependencies {
    implementation(project(":tools"))
    // JSON
    implementation("com.google.code.gson:gson:2.10.1")
    // YAML 配置
    implementation("org.yaml:snakeyaml:2.2")
    // HTML 解析 + 简易 CSS 选择

    // JUnit 5 for testing. Gradle 9 requires the Platform launcher explicitly.
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
}

application {
    mainClass.set("com.operit.aiclaw.Main")
}

tasks.processResources {
    from("src/main/resources") {
        include("**/*.yaml")
        include("**/*.yml")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.operit.aiclaw.Main"
    }

    // Fat JAR：延迟读取 runtimeClasspath，确保 :tools:jar 已经生成后
    // 才调用 zipTree；否则 Gradle 配置阶段会尝试展开不存在的 tools jar。
    dependsOn(":tools:jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name != "tools-1.0.0.jar" }
            .map { if (it.isDirectory) it else zipTree(it) }
    })
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}