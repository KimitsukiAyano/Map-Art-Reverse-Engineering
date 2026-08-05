package rvmaptools;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Development-only. Not shipped in the mod jar.
 *
 * <p>Copies a jar, widening every class / field / non-private method to public.
 *
 * <p>Why: yarn splits classes that were flat in the obfuscated jar into real packages, so
 * package-private calls inside Minecraft become illegal cross-package accesses. Inside a Fabric
 * runtime that never matters, but the offline probes here run plain {@code java}, where
 * {@code Bootstrap.initialize()} dies with {@code IllegalAccessError}. Widening the copy once
 * lets the probes touch the real registries.
 *
 * <p>Private methods are left alone: they are reached with {@code invokespecial} from inside their
 * own class, which stays legal, and widening them would change dispatch.
 */
public final class Publicizer {
	public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			System.err.println("usage: Publicizer <input.jar> <output.jar>");
			System.exit(2);
		}

		try (ZipFile in = new ZipFile(args[0]);
				ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])))) {
			Enumeration<? extends ZipEntry> entries = in.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();

				if (entry.isDirectory()) {
					continue;
				}

				byte[] data = in.getInputStream(entry).readAllBytes();

				if (entry.getName().endsWith(".class")) {
					data = widen(data);
				}

				out.putNextEntry(new ZipEntry(entry.getName()));
				out.write(data);
				out.closeEntry();
			}
		}

		System.out.println("publicized -> " + args[1]);
	}

	private static byte[] widen(byte[] data) {
		ClassReader reader = new ClassReader(data);
		ClassWriter writer = new ClassWriter(0);

		reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				super.visit(version, publicize(access) & ~Opcodes.ACC_FINAL, name, signature, superName, interfaces);
			}

			@Override
			public void visitInnerClass(String name, String outerName, String innerName, int access) {
				super.visitInnerClass(name, outerName, innerName, publicize(access));
			}

			@Override
			public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
				return super.visitField(publicize(access), name, descriptor, signature, value);
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				if (name.equals("<clinit>") || (access & Opcodes.ACC_PRIVATE) != 0 && !name.equals("<init>")) {
					return super.visitMethod(access, name, descriptor, signature, exceptions);
				}

				return super.visitMethod(publicize(access), name, descriptor, signature, exceptions);
			}
		}, 0);

		return writer.toByteArray();
	}

	private static int publicize(int access) {
		return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
	}
}
