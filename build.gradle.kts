import org.jetbrains.compose.compose

plugins {
	kotlin("jvm") version "1.5.10"
	kotlin("plugin.serialization") version "1.5.10"
	id("org.jetbrains.compose") version "0.4.0"
}

group = "me.dominaezzz"
version = "1.0-SNAPSHOT"

repositories {
	maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
	maven("https://maven.pkg.github.com/Dominaezzz/matrix-kt") {
		credentials {
			username = System.getenv("GITHUB_USER")
			password = System.getenv("GITHUB_TOKEN")
		}
	}
	mavenCentral()
}

val ktorVersion = "1.6.0"
val coroutinesVersion = "1.5.0"
val serializationVersion = "1.2.1"
val matrixKtVersion = "0.1.4"
val sqliteVersion = "3.34.0"

dependencies {
	implementation(compose.desktop.currentOs)
	implementation(compose.materialIconsExtended)

	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

	implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

	implementation("io.github.matrixkt:client-jvm:$matrixKtVersion")
	implementation("io.github.matrixkt:olm-jvm:$matrixKtVersion")
	implementation("io.ktor:ktor-client-core:$ktorVersion")
	implementation("io.ktor:ktor-client-apache:$ktorVersion")
	implementation("io.ktor:ktor-client-logging:$ktorVersion")

	implementation("org.xerial:sqlite-jdbc:$sqliteVersion")

	implementation("org.jsoup:jsoup:1.13.1")

	testImplementation(kotlin("test-junit"))
}

compose.desktop {
	application {
		mainClass = "me.dominaezzz.chitchat.MainKt"
	}
}

tasks {
	compileKotlin {
		kotlinOptions {
			freeCompilerArgs = listOf("-Xopt-in=kotlin.RequiresOptIn")
		}
	}
}
