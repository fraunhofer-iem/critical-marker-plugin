plugins {
    id("java")
    kotlin("jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "de.fraunhofer.iem"
version = 1.0

repositories {
    maven("https://www.jetbrains.com/intellij-repository/releases")
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}


val ideaVersion = providers.gradleProperty("ideaVersion").get()


dependencies {

    implementation("com.openai:openai-java:3.1.2")
    intellijPlatform {
        create("IC", "2024.2.6")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("com.intellij.java")
    }

    implementation("com.github.mauricioaniche:ck:0.7.0")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")

    implementation("nz.ac.waikato.cms.weka:weka-stable:3.8.6")
}


intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("242") // 2024.2 line
            untilBuild.set(null as String?)
        }
    }
}


tasks {
    patchPluginXml {
        changeNotes.set("Initial version: Security critical marker and explanation to assist developers pro-actively")
    }

    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
}