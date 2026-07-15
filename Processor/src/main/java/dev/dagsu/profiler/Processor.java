package dev.dagsu.profiler;

import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@SupportedAnnotationTypes("*")
@SupportedOptions(Processor.OPTION_WEAVE)
public class Processor extends AbstractProcessor {
    static final String THREAD_ANNOTATION = "dev.dagsu.profiler.Profiler.Thread";
    static final String SCOPE_ANNOTATION = "dev.dagsu.profiler.Profiler.Scope";
    static final String SETUP_ANNOTATION = "dev.dagsu.profiler.Profiler.Setup";
    static final String OPTION_WEAVE = "tracy.weave";

    private static final String TRACY = "com.mojang.jtracy.ProfilerHooks";
    private static final String ZONE_VAR = "profiler$zone";
    private static final String FRAME_VAR = "profiler$frame";

    private boolean weave;
    private Trees trees;
    private TreeMaker make;
    private Names names;
    private Elements elements;
    private Types types;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.weave = "true".equals(env.getOptions().get(OPTION_WEAVE));
        if (!weave) {
            return;
        }
        try {
            // IntelliJ (and some other tools) hand the processor a wrapper around the real
            // environment; unwrap to the javac one before touching Trees / the Context.
            ProcessingEnvironment javacEnv = unwrap(env);
            this.trees = Trees.instance(javacEnv);
            Context context = ((JavacProcessingEnvironment) javacEnv).getContext();
            this.make = TreeMaker.instance(context);
            this.names = Names.instance(context);
            this.elements = env.getElementUtils();
            this.types = env.getTypeUtils();
        } catch (Throwable t) {
            // A profiling tool must never break the build; just disable weaving and warn.
            this.weave = false;
            env.getMessager().printMessage(Diagnostic.Kind.WARNING,
                "@Profiler.Scope weaving disabled: could not access the javac AST API (" + t + ")");
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!weave || roundEnv.processingOver()) {
            return false;
        }
        // Visit every type being compiled and weave each concrete method that either carries
        // @Profiler.Scope directly or overrides a @Profiler.Scope method up the hierarchy.
        for (Element root : roundEnv.getRootElements()) {
            if (root instanceof TypeElement type) {
                weaveType(type);
            }
        }
        // Don't claim the annotation — harmless, and lets other tools observe it if they care.
        return false;
    }

    private void weaveType(TypeElement type) {
        // Interfaces are skipped: a @Profiler.Scope on an interface method is a marker to weave its
        // implementations, not the (usually bodiless) interface method itself. Abstract *classes*
        // are NOT skipped — their concrete methods (e.g. an abstract thread's run()) still weave;
        // any bodiless method is handled per-method in weaveMethod.
        if (type.getKind().isInterface()) return;

        weaveSetup(type);

        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed instanceof TypeElement nested) {
                weaveType(nested);
            } else if (enclosed instanceof ExecutableElement method && method.getKind() == ElementKind.METHOD) {
                weaveMethod(method, scopeFor(method, type));
            }
        }
    }

    private String scopeFor(ExecutableElement method, TypeElement owner) {
        ExecutableElement source;
        if (hasScope(method)) {
            source = method;
        } else if (owner.getModifiers().contains(Modifier.ABSTRACT)) {
            source = null;
        } else {
            source = findScopeAncestor(method, owner, owner.asType());
        }
        if (source == null) {
            return null;
        }
        String custom = annotationValue(source);
        return custom.isEmpty() ? generatedName(method) : custom;
    }

    private ExecutableElement findScopeAncestor(ExecutableElement method, TypeElement owner, TypeMirror type) {
        for (TypeMirror supertype : types.directSupertypes(type)) {
            if (types.asElement(supertype) instanceof TypeElement superElement) {
                for (Element member : superElement.getEnclosedElements()) {
                    if (member instanceof ExecutableElement candidate
                        && candidate.getKind() == ElementKind.METHOD
                        && hasScope(candidate)
                        && elements.overrides(method, candidate, owner)) {
                        return candidate;
                    }
                }
                ExecutableElement deeper = findScopeAncestor(method, owner, supertype);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }

    private void weaveSetup(TypeElement type) {
        AnnotationMirror setup = annotationMirror(type, SETUP_ANNOTATION);
        if (setup == null || !(trees.getTree(type) instanceof JCClassDecl decl)) {
            return;
        }
        make.at(decl.pos);
        // Insert `static { ProfilerHooks.startup("<appInfo>"); }` at the top of the class body.
        JCStatement call = make.Exec(call("startup", make.Literal(annotationMember(setup, "value"))));
        JCBlock init = make.Block(Flags.STATIC, List.of(call));
        decl.defs = decl.defs.prepend(init);
    }

    private void weaveMethod(ExecutableElement method, String scopeName) {
        if (!(trees.getTree(method) instanceof JCMethodDecl decl) || decl.body == null) {
            // Abstract / native method, or a tree we can't resolve — nothing to weave.
            return;
        }

        make.at(decl.pos);

        // Frame: rewrite @Profiler.Frame local booleans into createFrame / markFrame, on the
        // original body before any zone wrapping moves it.
        weaveFrames(decl);

        // Scope: wrap the body in beginZone / try / finally / endZone.
        if (scopeName != null) {
            JCVariableDecl zoneVar = make.VarDef(
                make.Modifiers(Flags.FINAL),
                names.fromString(ZONE_VAR),
                make.TypeIdent(TypeTag.INT),
                call("beginZone", make.Literal(scopeName)));

            JCStatement endZone = make.Exec(call("endZone", make.Ident(names.fromString(ZONE_VAR))));
            JCBlock finalizer = make.Block(0, List.of(endZone));
            JCTry tryStmt = make.Try(decl.body, List.nil(), finalizer);

            decl.body = make.Block(0, List.of(zoneVar, tryStmt));
        }

        // Thread: name the current thread as the very first statement, ahead of any zone.
        AnnotationMirror thread = annotationMirror(method, THREAD_ANNOTATION);
        if (thread != null) {
            JCStatement setThread = make.Exec(call("setThread", threadNameExpr(method, thread)));
            decl.body.stats = decl.body.stats.prepend(setThread);
        }
    }

    private void weaveFrames(JCMethodDecl decl) {
        Map<Name, Name> holders = new HashMap<>();   // original local name -> generated holder name
        Map<Name, String> frameNames = new HashMap<>(); // original local name -> createFrame arg

        decl.body.accept(new TreeScanner() {
            @Override
            public void visitVarDef(JCVariableDecl var) {
                super.visitVarDef(var);
                if (hasFrameAnnotation(var) && !holders.containsKey(var.name)) {
                    String suffix = holders.isEmpty() ? "" : String.valueOf(holders.size());
                    holders.put(var.name, names.fromString(FRAME_VAR + suffix));
                    frameNames.put(var.name, frameValue(var));
                }
            }
        });

        if (holders.isEmpty()) {
            return;
        }

        decl.body = (JCBlock) new TreeTranslator() {
            @Override
            public void visitVarDef(JCVariableDecl var) {
                super.visitVarDef(var);
                Name holder = holders.get(var.name);
                if (holder != null && hasFrameAnnotation(var)) {
                    result = make.at(var.pos).VarDef(
                        make.Modifiers(Flags.FINAL),
                        holder,
                        make.TypeIdent(TypeTag.LONG),
                        call("createFrame", make.Literal(frameNames.get(var.name))));
                }
            }

            @Override
            public void visitIdent(JCIdent ident) {
                super.visitIdent(ident);
                Name holder = holders.get(ident.name);
                if (holder != null) {
                    result = call("markFrame", make.Ident(holder));
                }
            }
        }.translate(decl.body);
    }

    private static boolean hasFrameAnnotation(JCVariableDecl var) {
        for (JCAnnotation annotation : var.mods.annotations) {
            if (isFrameAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFrameAnnotation(JCAnnotation annotation) {
        String type = annotation.annotationType.toString();
        return type.equals("Frame") || type.equals("Profiler.Frame") || type.endsWith(".Profiler.Frame");
    }

    private static String frameValue(JCVariableDecl var) {
        for (JCAnnotation annotation : var.mods.annotations) {
            if (!isFrameAnnotation(annotation)) {
                continue;
            }
            for (JCExpression arg : annotation.args) {
                JCExpression value = arg instanceof JCAssign assign ? assign.rhs : arg;
                if (value instanceof JCLiteral literal && literal.value instanceof String string) {
                    return string;
                }
            }
        }
        return "";
    }

    private JCExpression threadNameExpr(ExecutableElement method, AnnotationMirror thread) {
        String value = annotationMember(thread, "value");
        if (!value.isEmpty()) {
            return make.Literal(value);
        }
        String var = annotationMember(thread, "var");
        if (!var.isEmpty()) {
            return make.Apply(List.nil(),
                make.Select(dotted("java.lang.String"), names.fromString("valueOf")),
                List.of(make.Ident(names.fromString(var))));
        }
        return make.Literal(method.getEnclosingElement().getSimpleName().toString());
    }

    private static boolean hasScope(Element element) {
        return annotationMirror(element, SCOPE_ANNOTATION) != null;
    }

    private JCExpression call(String method, JCExpression arg) {
        return make.Apply(List.nil(), make.Select(dotted(TRACY), names.fromString(method)), List.of(arg));
    }

    private JCExpression dotted(String path) {
        String[] parts = path.split("\\.");
        JCExpression expr = make.Ident(names.fromString(parts[0]));
        for (int i = 1; i < parts.length; i++) {
            expr = make.Select(expr, names.fromString(parts[i]));
        }
        return expr;
    }

    private static String generatedName(ExecutableElement method) {
        final var parts = new ArrayList<String>();

            // Name after the outermost enclosing type so nested helper classes read naturally.
        Element type = method.getEnclosingElement();
        parts.add(type.getSimpleName().toString());

        while (type.getEnclosingElement() instanceof TypeElement enclosing) {
            type = enclosing;
            parts.add(type.getSimpleName().toString());
        }

        StringBuilder sb = new StringBuilder();
        for (int i = parts.size() - 1; i >= 0; --i) {
            sb.append(parts.get(i)).append(i > 0 ? "." : "");
        }

        sb.append('#').append(method.getSimpleName()).append('(');
        var params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(simpleType(params.get(i).asType()));
        }
        return sb.append(')').toString();
    }

    private static String annotationValue(Element method) {
        AnnotationMirror scope = annotationMirror(method, SCOPE_ANNOTATION);
        return scope == null ? "" : annotationMember(scope, "value");
    }

    private static AnnotationMirror annotationMirror(Element element, String annotationName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().equals(annotationName)) {
                return mirror;
            }
        }
        return null;
    }

    private static String annotationMember(AnnotationMirror mirror, String member) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : mirror.getElementValues().entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(member)) {
                return String.valueOf(entry.getValue().getValue());
            }
        }
        return "";
    }

    private static String simpleType(TypeMirror type) {
        String name = type.toString();
        int generics = name.indexOf('<');
        if (generics >= 0) {
            name = name.substring(0, generics);
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private static ProcessingEnvironment unwrap(ProcessingEnvironment env) {
        if (env instanceof JavacProcessingEnvironment) {
            return env;
        }
        // IntelliJ exposes its own unwrapper; ask it directly.
        ProcessingEnvironment jetbrains = jetBrainsUnwrap(env);
        if (jetbrains instanceof JavacProcessingEnvironment) {
            return jetbrains;
        }
        // Generic fallback: follow any delegate field (or dynamic-proxy handler field) that holds
        // another ProcessingEnvironment until we reach the javac one.
        ProcessingEnvironment delegate = delegateOf(env);
        if (delegate != null && delegate != env) {
            return unwrap(delegate);
        }
        return jetbrains != null ? jetbrains : env;
    }

    private static ProcessingEnvironment jetBrainsUnwrap(ProcessingEnvironment env) {
        try {
            Class<?> apiWrappers = env.getClass().getClassLoader().loadClass("org.jetbrains.jps.javac.APIWrappers");
            Method unwrap = apiWrappers.getDeclaredMethod("unwrap", Class.class, Object.class);
            Object result = unwrap.invoke(null, ProcessingEnvironment.class, env);
            if (result instanceof ProcessingEnvironment unwrapped) {
                return unwrapped;
            }
        } catch (Throwable ignored) {
            // Not an IntelliJ build, or the internal API moved — fall back to field digging.
        }
        return null;
    }

    private static ProcessingEnvironment delegateOf(ProcessingEnvironment env) {
        Object target = Proxy.isProxyClass(env.getClass()) ? Proxy.getInvocationHandler(env) : env;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (ProcessingEnvironment.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        if (field.get(target) instanceof ProcessingEnvironment delegate && delegate != env) {
                            return delegate;
                        }
                    } catch (Throwable ignored) {
                        // keep looking
                    }
                }
            }
        }
        return null;
    }
}
