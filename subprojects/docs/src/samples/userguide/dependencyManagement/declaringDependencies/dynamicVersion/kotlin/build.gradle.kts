// tag::dependencies[]
plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework:spring-web:5.+")
}
// end::dependencies[]

task<Copy>("copyLibs") {
    from(configurations.compileClasspath)
    into("$buildDir/libs")
}
