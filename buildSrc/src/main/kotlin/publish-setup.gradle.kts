import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("maven-publish")
    id("signing")
}

publishing {
    repositories {
        maven {
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2")
            credentials {
                val localProperties = loadProperties(rootDir.resolve("local.properties"))
                username = localProperties.getProperty("ossrhUsername") ?: System.getenv("OSSRH_USERNAME")
                password = localProperties.getProperty("ossrhPassword") ?: System.getenv("OSSRH_PASSWORD")
            }
        }
    }

    publications.all {
        this as MavenPublication

        pom {
            name.set(project.name)
            description.set("WebRTC Kotlin Multiplatform SDK")
            url.set("https://github.com/shepeliev/webrtc-kmp")

            scm {
                url.set("https://github.com/shepeliev/webrtc-kmp")
                connection.set("scm:git:https://github.com/shepeliev/webrtc-kmp.git")
                developerConnection.set("scm:git:https://github.com/shepeliev/webrtc-kmp.git")
                tag.set("HEAD")
            }

            issueManagement {
                system.set("GitHub Issues")
                url.set("https://github.com/shepeliev/webrtc-kmp/issues")
            }

            developers {
                developer {
                    name.set("Alex Shepeliev")
                    email.set("a.shepeliev@gmail.com")
                }
            }

            licenses {
                license {
                    name.set("The Apache Software License, Version 2.0")
                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                    comments.set("A business-friendly OSS license")
                }
            }
        }
    }
}

signing {
    val localProperties = loadProperties(rootDir.resolve("local.properties"))
    val signingKey = localProperties.getProperty("signing.key") ?: System.getenv("SIGNING_KEY")
    val signingPassword = localProperties.getProperty("signing.password") ?: System.getenv("SIGNING_PASSWORD")
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications)
}
