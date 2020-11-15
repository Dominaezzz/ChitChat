import org.jetbrains.compose.compose
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
	kotlin("jvm") version "1.4.0"
	id("org.jetbrains.compose") version "0.1.0-m1-build62"
}

group = "me.dominaezzz"
version = "1.0-SNAPSHOT"

repositories {
	maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
	mavenLocal()
	jcenter()
}

val ktorVersion = "1.4.2"
val coroutinesVersion = "1.4.1"
val serializationVersion = "1.0.1"
val matrixKtVersion = "0.1.0-dev-1-20-g6449827"
val sqliteVersion = "3.32.3.2"

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

	testImplementation(kotlin("test-junit"))
}

compose.desktop {
	application {
		mainClass = "me.dominaezzz.chitchat.MainKt"

		nativeDistributions {
			targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
			packageName = "KotlinJvmComposeDesktopApplication"
		}
	}
}
