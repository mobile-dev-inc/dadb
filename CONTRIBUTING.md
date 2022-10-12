# Contributing Guide 

## Development 

### Pre-requisites 

This project is made in Kotlin, and build with Gradle, thus to build `dadb` from source you'll need 

- JDK (1.8, 11 or 14) setup on your machine
- Gradle 7.2 (optional; this project can use gradle wrapper automatically too) 

### Building 

```shell
$ ./gradlew assemble # using the gradle wrapper script 
```
or
```shell
$ gradle assemble # using your local gradle distribution
```

### Running Tests 

You can run all tests using 

```shell 
$ ./gradlew check
```

> NOTE: Do remember that the unit tests need either an ADB-enabled device or emulator connected to your machine