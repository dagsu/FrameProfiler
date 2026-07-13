### About

This experimental library builds off Mojang's Tracy JNI library to provide annotation-based profiling markup and
compile-time code-generation, with the following goals:
- Minimally invasive on source code: user should not have to manually call hooks to mark the begin/end of sections.
- Zero overhead when disabled: no code injected when AP is disabled. Profiling effectively compiled out.

#### Code Example:

```java
import dev.dagsu.profiler.Profiler;

static {
    // Call once to init tracy if it is available.
    Profiler.start("MyApp");
}

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
```

### Annotation Processor:

```
dev.dagsu.profiler.Processor
```

#### Options

Enable / disable profiler code generation.

```
tracy.weave=true|false
```


### JVM Option:

Enable or disable runtime profiling.

```
-Dtracy=true|false
```