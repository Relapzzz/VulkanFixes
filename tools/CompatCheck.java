import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Checks a built VulkanFixes jar against a Minecraft version without launching the game.
 *
 * <p>Usage: {@code java -cp asm.jar;asm-tree.jar tools/CompatCheck.java <mod jar> <minecraft intermediary jar>}
 *
 * <p>The Minecraft jar must be in intermediary names, like the jars in the mod; Loom keeps one per version at
 * {@code ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-intermediary/}. It checks that:
 * <ul>
 * <li>every Minecraft class, method and field the mod's code references exists (also through superclasses),</li>
 * <li>every mixin target method exists and contains the call its {@code @At} injects at,</li>
 * <li>every access widener entry exists.</li>
 * </ul>
 * Prints each problem and exits with 1 if there are any.
 */
public final class CompatCheck {
	private final Map<String, ClassNode> minecraft = new HashMap<>();
	private final List<String> problems = new ArrayList<>();

	public static void main(String[] args) throws IOException {
		if (args.length != 2) {
			System.err.println("usage: CompatCheck <mod jar> <minecraft intermediary jar>");
			System.exit(2);
		}
		CompatCheck check = new CompatCheck();
		check.loadMinecraft(args[1]);
		check.checkMod(args[0]);
		if (check.problems.isEmpty()) {
			System.out.println("OK");
		} else {
			check.problems.forEach(p -> System.out.println("PROBLEM " + p));
			System.exit(1);
		}
	}

	private void loadMinecraft(String path) throws IOException {
		try (JarFile jar = new JarFile(path)) {
			for (JarEntry entry : jar.stream().toList()) {
				if (entry.getName().endsWith(".class")) {
					ClassNode node = read(jar.getInputStream(entry));
					minecraft.put(node.name, node);
				}
			}
		}
	}

	private void checkMod(String path) throws IOException {
		try (JarFile jar = new JarFile(path)) {
			for (JarEntry entry : jar.stream().toList()) {
				if (entry.getName().endsWith(".class")) {
					checkClass(read(jar.getInputStream(entry)));
				} else if (entry.getName().endsWith(".accesswidener")) {
					try (InputStream in = jar.getInputStream(entry)) {
						checkAccessWidener(new String(in.readAllBytes(), StandardCharsets.UTF_8));
					}
				}
			}
		}
	}

	private void checkClass(ClassNode node) {
		String where = node.name.substring(node.name.lastIndexOf('/') + 1);
		checkType(Type.getObjectType(node.superName), where);
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call) {
					checkType(Type.getObjectType(call.owner), where);
					if (isMinecraft(call.owner) && findMethod(call.owner, call.name, call.desc) == null) {
						problems.add(where + ": missing method " + call.owner + "." + call.name + call.desc);
					}
				} else if (insn instanceof FieldInsnNode field) {
					if (isMinecraft(field.owner) && findField(field.owner, field.name, field.desc) == null) {
						problems.add(where + ": missing field " + field.owner + "." + field.name + " " + field.desc);
					}
				}
			}
			for (Type arg : Type.getArgumentTypes(method.desc)) {
				checkType(arg, where);
			}
		}
		checkMixin(node, where);
	}

	private void checkType(Type type, String where) {
		if (type.getSort() == Type.ARRAY) {
			type = type.getElementType();
		}
		if (type.getSort() == Type.OBJECT && isMinecraft(type.getInternalName()) && !minecraft.containsKey(type.getInternalName())) {
			problems.add(where + ": missing class " + type.getInternalName());
		}
	}

	private void checkMixin(ClassNode node, String where) {
		AnnotationNode mixin = annotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
		if (mixin == null) {
			return;
		}
		@SuppressWarnings("unchecked")
		List<Type> targets = (List<Type>) value(mixin, "value");
		for (MethodNode handler : node.methods) {
			for (List<AnnotationNode> list : List.of(nullToEmpty(handler.visibleAnnotations), nullToEmpty(handler.invisibleAnnotations))) {
				for (AnnotationNode injector : list) {
					@SuppressWarnings("unchecked")
					List<String> selectors = (List<String>) value(injector, "method");
					// @Inject and @WrapOperation take "at" as an array.
					Object at = value(injector, "at");
					List<?> ats = at instanceof List<?> list2 ? list2 : at == null ? List.of() : List.of(at);
					if (selectors == null || ats.isEmpty()) {
						continue;
					}
					for (Type target : targets) {
						for (String selector : selectors) {
							for (Object point : ats) {
								checkInjection(target.getInternalName(), selector, (AnnotationNode) point, where + "." + handler.name);
							}
						}
					}
				}
			}
		}
	}

	private void checkInjection(String owner, String selector, AnnotationNode at, String where) {
		ClassNode target = minecraft.get(owner);
		if (target == null) {
			problems.add(where + ": mixin target class " + owner + " missing");
			return;
		}
		int paren = selector.indexOf('(');
		String name = paren < 0 ? selector : selector.substring(0, paren);
		String desc = paren < 0 ? null : selector.substring(paren);
		List<MethodNode> candidates = target.methods.stream()
			.filter(m -> m.name.equals(name) && (desc == null || m.desc.equals(desc)))
			.toList();
		if (candidates.isEmpty()) {
			problems.add(where + ": target method " + owner + "." + selector + " missing");
			return;
		}
		String invoke = (String) value(at, "target");
		if (!"INVOKE".equals(value(at, "value")) || invoke == null) {
			return;
		}
		Integer ordinal = (Integer) value(at, "ordinal");
		int count = 0;
		for (MethodNode method : candidates) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && invoke.equals("L" + call.owner + ";" + call.name + call.desc)) {
					count++;
				}
			}
		}
		if (count == 0 || (ordinal != null && count <= ordinal)) {
			problems.add(where + ": " + owner + "." + selector + " has " + count + " calls to " + invoke
				+ (ordinal != null ? ", needs ordinal " + ordinal : ""));
		}
	}

	private void checkAccessWidener(String text) {
		for (String line : text.split("\n")) {
			String[] parts = line.trim().split("\\s+");
			if (parts.length < 3 || line.startsWith("accessWidener") || line.startsWith("#")) {
				continue;
			}
			switch (parts[1]) {
				case "class" -> {
					if (!minecraft.containsKey(parts[2])) {
						problems.add("access widener: missing class " + parts[2]);
					}
				}
				case "method" -> {
					if (findMethod(parts[2], parts[3], parts[4]) == null) {
						problems.add("access widener: missing method " + parts[2] + "." + parts[3] + parts[4]);
					}
				}
				case "field" -> {
					if (findField(parts[2], parts[3], parts[4]) == null) {
						problems.add("access widener: missing field " + parts[2] + "." + parts[3] + " " + parts[4]);
					}
				}
				default -> problems.add("access widener: unknown entry " + line);
			}
		}
	}

	/** Finds a method in a Minecraft class or its supertypes; methods of JDK supertypes count as present. */
	private Object findMethod(String owner, String name, String desc) {
		ClassNode node = minecraft.get(owner);
		if (node == null) {
			return isMinecraft(owner) ? null : owner;
		}
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) {
				return method;
			}
		}
		List<String> supers = new ArrayList<>(node.interfaces);
		if (node.superName != null) {
			supers.add(node.superName);
		}
		for (String parent : supers) {
			if (!isMinecraft(parent) && hasJdkMethod(parent, name, desc)) {
				return parent;
			}
			Object found = isMinecraft(parent) ? findMethod(parent, name, desc) : null;
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private Object findField(String owner, String name, String desc) {
		ClassNode node = minecraft.get(owner);
		if (node == null) {
			return null;
		}
		for (FieldNode field : node.fields) {
			if (field.name.equals(name) && field.desc.equals(desc)) {
				return field;
			}
		}
		return node.superName != null && isMinecraft(node.superName) ? findField(node.superName, name, desc) : null;
	}

	private static boolean hasJdkMethod(String owner, String name, String desc) {
		try {
			Class<?> type = Class.forName(owner.replace('/', '.'), false, CompatCheck.class.getClassLoader());
			for (var method : type.getMethods()) {
				if (method.getName().equals(name) && Type.getMethodDescriptor(method).equals(desc)) {
					return true;
				}
			}
		} catch (ClassNotFoundException e) {
			// Not on this JDK: treat as absent.
		}
		return false;
	}

	private static boolean isMinecraft(String internalName) {
		return internalName.startsWith("net/minecraft/") || internalName.startsWith("com/mojang/blaze3d/");
	}

	private static ClassNode read(InputStream in) throws IOException {
		try (in) {
			ClassNode node = new ClassNode();
			new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
			return node;
		}
	}

	private static AnnotationNode annotation(List<AnnotationNode> annotations, String desc) {
		for (AnnotationNode annotation : nullToEmpty(annotations)) {
			if (annotation.desc.equals(desc)) {
				return annotation;
			}
		}
		return null;
	}

	private static Object value(AnnotationNode annotation, String key) {
		if (annotation.values == null) {
			return null;
		}
		for (int i = 0; i < annotation.values.size(); i += 2) {
			if (annotation.values.get(i).equals(key)) {
				return annotation.values.get(i + 1);
			}
		}
		return null;
	}

	private static <T> List<T> nullToEmpty(List<T> list) {
		return list == null ? List.of() : list;
	}
}
