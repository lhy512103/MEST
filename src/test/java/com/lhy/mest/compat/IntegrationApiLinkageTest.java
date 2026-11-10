package com.lhy.mest.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Checks actual javac output against selected dependencies using class resources only.
 * Never defines, reflects on, or initializes game/mod classes. This is an existence
 * smoke test, not a JVM verifier or Mixin application test. Access checks are omitted
 * deliberately because access transformers change them at launch.
 */
class IntegrationApiLinkageTest {
    private static final String MEST = "com/lhy/mest/";
    private static final List<String> PREFIXES = List.of(
            "appeng/", "de/mari_023/", "com/extendedae_plus/",
            "com/glodblock/github/extendedae/", "com/moakiee/ae2lt/",
            "gripe/_90/megacells/", "dev/terminalmc/clientsort/",
            "com/sorrowmist/useless/", "mezz/jei/", "dev/emi/");

    @Test
    void compiledIntegrationReferencesStillLink() throws IOException {
        Path root = Path.of("build/classes/java/main", MEST);
        assertTrue(Files.isDirectory(root), "Missing main javac output: " + root.toAbsolutePath()
                + "; compile main before running this test (never silently skip linkage checks)");
        Repository repository = new Repository();
        List<byte[]> classes = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                byte[] bytes = Files.readAllBytes(path);
                classes.add(bytes);
                repository.add(bytes);
            }
        }
        assertFalse(classes.isEmpty(), "No compiled MEST classes found in " + root.toAbsolutePath());
        Checker checker = new Checker(repository);
        for (byte[] bytes : classes) checker.scan(bytes);
        assertTrue(checker.references > 0, "No selected integration references visited; check output location");
        checker.assertValid();
    }

    @Test
    void uselessBridgeReflectionTargetsStillExist() {
        Repository repository = new Repository();
        Checker checker = new Checker(repository);
        String bridge = MEST + "compat/UselessPatternBridge";
        Info info = repository.get(bridge);
        assertTrue(info != null, "Missing compiled bridge " + bridge);
        // Read ConstantValue attributes from the actual bridge, without loading it.
        String entry = bridgeTarget(info, "ENTRY_CLASS");
        String catalog = bridgeTarget(info, "CATALOG_CLASS");
        String identity = bridgeTarget(info, "IDENTITY_CLASS");
        String encoding = bridgeTarget(info, "ENCODING_CLASS");
        String support = bridgeTarget(info, "SUPPORT_CLASS");
        String level = "Lnet/minecraft/world/level/Level;";
        String stack = "Lnet/minecraft/world/item/ItemStack;";
        // Mirror getConstructor/getMethod in UselessPatternBridge.convert/encode.
        checker.member(bridge + " reflection", identity, "<init>",
                "(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)V", true);
        checker.member(bridge + " reflection", catalog, "resolve",
                "(" + level + "Ljava/lang/String;L" + identity + ";)Ljava/util/Optional;", true);
        checker.member(bridge + " reflection", encoding, "encode",
                "(" + stack + "L" + entry + ";" + level + ")" + stack, true);
        checker.member(bridge + " reflection", support, "encode",
                "(Lcom/lhy/mest/terminal/MESTMenu;L" + entry + ";Ljava/util/List;Ljava/util/List;)Z", true);
        checker.requireClass(bridge + " reflection", entry);
        checker.assertValid();
    }

    @Test
    void checkerDetectsDescriptorChangesWithoutRejectingInheritedMembers() {
        Repository repository = new Repository();
        Info base = new Info();
        base.methods.add(new Member("<init>", "(I)V"));
        base.methods.add(new Member("inherited", "()I"));
        base.fields.add(new Member("value", "J"));
        Info iface = new Info();
        iface.methods.add(new Member("fromInterface", "()V"));
        Info child = new Info();
        child.parent = "appeng/linkagefixture/Base";
        child.interfaces = List.of("appeng/linkagefixture/Interface");
        repository.cache.put(child.parent, base);
        repository.cache.put(child.interfaces.getFirst(), iface);
        repository.cache.put("appeng/linkagefixture/Child", child);
        Checker checker = new Checker(repository);
        String owner = "appeng/linkagefixture/Child";
        checker.instruction("fixture", owner, "inherited", "()I", true);
        checker.instruction("fixture", owner, "fromInterface", "()V", true);
        checker.instruction("fixture", owner, "value", "J", false);
        checker.instruction("fixture", "[Lappeng/linkagefixture/Child;", "clone", "()Ljava/lang/Object;", true);
        checker.instruction("fixture", "java/lang/invoke/MethodHandle", "invokeExact", "(I)J", true);
        checker.instruction("fixture", MEST + "mixin/Example", "shadow$method", "()V", true);
        checker.assertValid();
        checker.instruction("caller/Mest.method()V", owner, "inherited", "()J", true);
        checker.instruction("caller/Mest.method()V", owner, "value", "I", false);
        checker.instruction("caller/Mest.method()V", owner, "<init>", "(I)V", true);
        assertTrue(checker.errors.size() == 3, "Must reject changed descriptors and inherited constructors: " + checker.errors);
        assertTrue(checker.errors.stream().allMatch(error -> error.contains("caller/Mest.method()V")
                && error.contains(owner)), "Failures must retain target and call-site context");
    }

    private static String bridgeTarget(Info bridge, String field) {
        Object value = bridge.constants.get(field);
        assertTrue(value instanceof String, "Bridge string constant missing: " + field);
        return ((String) value).replace('.', '/');
    }

    private record Member(String name, String descriptor) {}

    private static final class Info {
        String parent;
        List<String> interfaces = List.of();
        final Set<Member> methods = new HashSet<>();
        final Set<Member> fields = new HashSet<>();
        final Map<String, Object> constants = new HashMap<>();
    }

    private static final class Repository {
        final Map<String, Info> cache = new HashMap<>();
        final ClassLoader loader = IntegrationApiLinkageTest.class.getClassLoader();

        void add(byte[] bytes) {
            ClassReader reader = new ClassReader(bytes);
            Info info = new Info();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature,
                        String superName, String[] interfaces) {
                    info.parent = superName;
                    info.interfaces = List.of(interfaces);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    info.methods.add(new Member(name, descriptor));
                    return null;
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                        String signature, Object value) {
                    info.fields.add(new Member(name, descriptor));
                    if (value != null) info.constants.put(name, value);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            cache.put(reader.getClassName(), info);
        }

        Info get(String owner) {
            if (!cache.containsKey(owner)) {
                try (InputStream stream = loader.getResourceAsStream(owner + ".class")) {
                    if (stream == null) cache.put(owner, null);
                    else add(stream.readAllBytes());
                } catch (IOException ex) {
                    throw new UncheckedIOException("Cannot read class resource " + owner, ex);
                }
            }
            return cache.get(owner);
        }

        boolean contains(String owner, Member member, boolean method, Set<String> visited,
                Set<String> missingAncestors) {
            if (!visited.add(owner)) return false;
            Info info = get(owner);
            if (info == null) {
                missingAncestors.add(owner);
                return false;
            }
            if ((method ? info.methods : info.fields).contains(member)) return true;
            if (method && member.name().equals("<init>")) return false; // Never inherited.
            for (String iface : info.interfaces) {
                if (contains(iface, member, method, visited, missingAncestors)) return true;
            }
            return info.parent != null && contains(info.parent, member, method, visited, missingAncestors);
        }
    }

    private static final class Checker {
        final Repository repository;
        final Set<String> errors = new TreeSet<>();
        final Set<String> resolvedMembers = new HashSet<>();
        int references;

        Checker(Repository repository) {
            this.repository = repository;
        }

        static boolean selected(String name) {
            return PREFIXES.stream().anyMatch(name::startsWith);
        }

        void requireClass(String caller, String owner) {
            if (repository.get(owner) == null) errors.add(caller + " -> missing class " + owner);
        }

        void type(String caller, Type type) {
            switch (type.getSort()) {
                case Type.ARRAY -> type(caller, type.getElementType());
                case Type.OBJECT -> {
                    if (selected(type.getInternalName())) {
                        references++;
                        requireClass(caller, type.getInternalName());
                    }
                }
                case Type.METHOD -> {
                    type(caller, type.getReturnType());
                    for (Type argument : type.getArgumentTypes()) type(caller, argument);
                }
                default -> { /* primitive / void */ }
            }
        }

        void descriptor(String caller, String descriptor) {
            type(caller, Type.getType(descriptor));
        }

        void namedType(String caller, String name) {
            if (name != null) type(caller, name.startsWith("[") ? Type.getType(name) : Type.getObjectType(name));
        }

        void instruction(String caller, String owner, String name, String descriptor, boolean method) {
            descriptor(caller, descriptor);
            namedType(caller, owner);
            // Array clone / JDK signature-polymorphic calls have special JVM resolution.
            // Local MEST Mixin shadow/accessor/synthetic calls are not rewritten into
            // external-owner calls: their untransformed declarations are not dependency APIs.
            if (!owner.startsWith("[") && selected(owner)) member(caller, owner, name, descriptor, method);
        }

        void member(String caller, String owner, String name, String descriptor, boolean method) {
            references++;
            String target = (method ? "method " : "field ") + owner + "." + name + descriptor;
            if (resolvedMembers.contains(target)) return;
            Set<String> missingAncestors = new TreeSet<>();
            if (repository.contains(owner, new Member(name, descriptor), method,
                    new HashSet<>(), missingAncestors)) {
                resolvedMembers.add(target);
            } else {
                errors.add(caller + " -> missing " + target + (missingAncestors.isEmpty() ? ""
                        : " (unavailable hierarchy resources: " + missingAncestors + ")"));
            }
        }

        void constant(String caller, Object value) {
            if (value instanceof Type type) type(caller, type);
            else if (value instanceof Handle handle) {
                boolean method = handle.getTag() >= Opcodes.H_INVOKEVIRTUAL;
                instruction(caller, handle.getOwner(), handle.getName(), handle.getDesc(), method);
            } else if (value instanceof ConstantDynamic dynamic) {
                descriptor(caller, dynamic.getDescriptor());
                constant(caller, dynamic.getBootstrapMethod());
                for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                    constant(caller, dynamic.getBootstrapMethodArgument(i));
                }
            }
        }

        void scan(byte[] bytes) {
            ClassReader reader = new ClassReader(bytes);
            String owner = reader.getClassName();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature,
                        String superName, String[] interfaces) {
                    namedType(owner, superName);
                    for (String iface : interfaces) namedType(owner, iface);
                }

                @Override
                public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                    descriptor(owner + "." + name, desc);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                        String signature, String[] exceptions) {
                    String caller = owner + "." + name + desc;
                    descriptor(caller, desc);
                    if (exceptions != null) for (String exception : exceptions) namedType(caller, exception);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String target, String method, String descriptor, boolean itf) {
                            instruction(caller, target, method, descriptor, true);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String target, String field, String descriptor) {
                            instruction(caller, target, field, descriptor, false);
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            namedType(caller, type);
                        }

                        @Override
                        public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                            descriptor(caller, descriptor);
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            constant(caller, value);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
                            descriptor(caller, descriptor);
                            constant(caller, bootstrap);
                            for (Object argument : arguments) constant(caller, argument);
                        }

                        @Override
                        public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
                                org.objectweb.asm.Label handler, String type) {
                            namedType(caller, type);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        void assertValid() {
            assertTrue(errors.isEmpty(), () -> "Selected dependency API linkage failures ("
                    + errors.size() + "):\n" + String.join("\n", errors));
        }
    }
}
