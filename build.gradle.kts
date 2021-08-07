import org.jetbrains.compose.compose

plugins {
	kotlin("jvm") version "1.5.21"
	kotlin("plugin.serialization") version "1.5.21"
	id("org.jetbrains.compose") version "1.0.0-alpha3"
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
	google()
}

val ktorVersion = "1.6.2"
val coroutinesVersion = "1.5.1"
val serializationVersion = "1.2.2"
val matrixKtVersion = "0.1.5"
val sqliteVersion = "3.36.0.1"

dependencies {
	implementation(compose.desktop.currentOs)
	implementation(compose.materialIconsExtended)

	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

	implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

	implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.2")

	implementation("io.github.matrixkt:client-jvm:$matrixKtVersion")
	implementation("io.github.matrixkt:olm-jvm:$matrixKtVersion")
	implementation("io.ktor:ktor-client-core:$ktorVersion")
	implementation("io.ktor:ktor-client-java:$ktorVersion")
	implementation("io.ktor:ktor-client-logging:$ktorVersion")

	implementation("org.xerial:sqlite-jdbc:$sqliteVersion")

	implementation("org.jsoup:jsoup:1.13.1")

	testImplementation(kotlin("test-junit"))
}

compose.desktop {
	application {
		mainClass = "me.dominaezzz.chitchat.MainKt"
		args(projectDir.resolve("appdir").absolutePath)
	}
}

tasks {
	compileKotlin {
		kotlinOptions {
			freeCompilerArgs = listOf("-Xopt-in=kotlin.RequiresOptIn")
		}
	}
}
