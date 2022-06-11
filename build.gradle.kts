import org.jetbrains.compose.compose

plugins {
	kotlin("jvm") version "1.6.21"
	kotlin("plugin.serialization") version "1.6.21"
	id("org.jetbrains.compose") version "1.2.0-alpha01-dev686"
}

group = "me.dominaezzz"
version = "1.0-SNAPSHOT"

repositories {
	maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
	mavenCentral()
	google()
}

val ktorVersion = "2.0.1"
val coroutinesVersion = "1.6.1-native-mt"
val serializationVersion = "1.3.3"
val matrixKtVersion = "0.2.0"
val sqliteVersion = "3.36.0.3"

dependencies {
	implementation(compose.desktop.currentOs)
	implementation(compose.materialIconsExtended)

	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

	implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

	implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.4")

	implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.5")

	implementation("io.github.dominaezzz.matrixkt:client-jvm:$matrixKtVersion")
	implementation("io.github.dominaezzz.matrixkt:olm-jvm:$matrixKtVersion")
	implementation("io.ktor:ktor-client-core:$ktorVersion")
	implementation("io.ktor:ktor-client-java:$ktorVersion")
	implementation("io.ktor:ktor-client-logging:$ktorVersion")
	implementation("io.ktor:ktor-client-resources:$ktorVersion")

	implementation("org.xerial:sqlite-jdbc:$sqliteVersion")

	implementation("org.jsoup:jsoup:1.14.3")

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
			freeCompilerArgs = listOf("-opt-in=kotlin.RequiresOptIn")
		}
	}
	compileTestKotlin {
		kotlinOptions {
			freeCompilerArgs = listOf("-opt-in=kotlin.RequiresOptIn")
		}
	}
}
