plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

val detektVersion = "2.0.0-alpha.6"

dependencies {
    compileOnly("dev.detekt:detekt-api:$detektVersion")
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
