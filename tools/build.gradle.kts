plugins {
    id("java-library")
}

group = "com.operit.aiclaw"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    maven { url = uri("https://maven.aliyun.com/repository/public") }
}

dependencies {
    api("com.google.code.gson:gson:2.10.1")
    api("org.jsoup:jsoup:1.17.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("tools")
}

