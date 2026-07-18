plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

val detektVersion = "2.0.0-alpha.5"

dependencies {
    compileOnly("dev.detekt:detekt-api:$detektVersion")
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.10")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
