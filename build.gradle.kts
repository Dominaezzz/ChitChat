import org.jetbrains.compose.compose

plugins {
	kotlin("jvm") version "1.4.32"
	kotlin("plugin.serialization") version "1.4.32"
	id("org.jetbrains.compose") version "0.4.0-build184"
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

val ktorVersion = "1.5.3"
val coroutinesVersion = "1.4.3"
val serializationVersion = "1.1.0"
val matrixKtVersion = "0.1.4-RC8"
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
