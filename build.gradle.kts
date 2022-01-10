plugins {
    application
    kotlin("jvm") version "1.3.72"
    kotlin("plugin.serialization") version "1.3.72"
}

group = "org.jaq"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

application{
    mainClassName = "com.jaq.web_scraper.chaldal.ChaldalScraperKt"
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime:0.20.0")
    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:0.11.0")
    implementation("org.seleniumhq.selenium:selenium-java:3.141.59")
    implementation("io.github.biezhi:webp-io:0.0.5")

}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
    compileTestKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
}