### About

This experimental library builds off Mojang's Tracy JNI library to provide annotation-based profiling markup and
compile-time code-generation, with the following goals:
- Minimally invasive on source code: user should not have to manually call hooks to mark the begin/end of sections.
- Zero overhead when disabled: no code injected when AP is disabled. Profiling effectively compiled out.

#### Code Example:

```java
import dev.dagsu.profiler.Profiler;

// Start up the profiler so that Tracy client can connect
@Profiler.Setup("MyAppInfo")
class AppMain {

    // Generates a thread identifier under which frames and scopes are recorded.
    // The default thread name will be derived from the class + method name but can be statically overridden
    // by setting the annotation value `@Profiler.Thread(value="ThreadName")` or by pulling from an accessible
    // `String` field in the class `@Profiler.Thread(var="nameField")`.
    @Profiler.Thread
    void run() {
        // Generates a frame identifier and counter for the thread which is incremented in the `while` condition.
        @Profiler.Frame("Frame") 
        final boolean frameMarker = true;

        while (running && frameMarker) {
            tick();
        }
    }

    // Generates a scoped section that opens at the head of the method and auto-closes on exit.
    // Scopes can be nested if the method calls other methods that are also scope-annotated.
    // The default scope name is derived from the class + method name but can be statically overridden
    // by setting the annotation value `@Profiler.Scope("Tick")`
    @Profiler.Scope
    void tick() {
        // do work
    }
}
```
#### Maven Config:

With this configuration you can toggle the "frame-profiler" profile to toggle the profiler injections on and off.  
You can use this via `mvn clean compile -P frame-profiler`.

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.dagsu</groupId>
        <artifactId>FrameProfiler</artifactId>
        <version>-SNAPSHOT</version>
    </dependency>
</dependencies>

<profiles>
    <profile>
        <id>frame-profiler</id>
        <build>
            <pluginManagement>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <version>3.13.0</version>
                        <configuration>
                            <source>${java.targetversion}</source>
                            <target>${java.targetversion}</target>
                            <fork>true</fork>
                            <compilerArgs>
                                <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED</arg>
                                <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED</arg>
                                <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED</arg>
                                <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED</arg>
                                <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED</arg>
                                <arg>-Atracy.weave=true</arg>
                            </compilerArgs>
                            <annotationProcessorPaths>
                                <path>
                                    <groupId>com.github.dagsu.FrameProfiler</groupId>
                                    <artifactId>Processor</artifactId>
                                    <version>-SNAPSHOT</version>
                                </path>
                            </annotationProcessorPaths>
                        </configuration>
                    </plugin>
                </plugins>
            </pluginManagement>
        </build>
    </profile>
</profiles>
```