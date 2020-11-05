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
	jcenter()
}

dependencies {
	implementation(compose.desktop.currentOs)

	testImplementation(kotlin("test-junit"))
}

compose.desktop {
	application {
		mainClass = "MainKt"

		nativeDistributions {
			targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
			packageName = "KotlinJvmComposeDesktopApplication"
		}
	}
}
