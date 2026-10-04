package com.akashic.compat.voidsky;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runs {@link VoidSkyTransformer} over the real classes and checks the result.
 *
 * <p>Usage: {@code VoidSkyTransformerTest <minecraft-1.7.10-client-srg.jar> [DynamicSurroundings.jar]}.
 * The client jar must be remapped to SRG member names (what Forge uses at runtime).
 */
public final class VoidSkyTransformerTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        VoidSkyTransformer transformer = new VoidSkyTransformer();

        check(args[0], "net.minecraft.client.renderer.RenderGlobal", "func_72714_a", "skyHeightAboveHorizon",
                Opcodes.DSUB, Opcodes.DSTORE, transformer);
        check(args[0], "net.minecraft.client.renderer.EntityRenderer", "func_78466_h", "voidFogBrightness",
                Opcodes.DMUL, Opcodes.DSTORE, transformer);
        if (args.length > 1) {
            check(args[1], "org.blockartistry.mod.DynSurround.client.fog.BiomeFogColorCalculator", "applyPlayerEffects",
                    "voidFogBrightness", Opcodes.DMUL, Opcodes.D2F, transformer);
        }

        byte[] other = read(args[0], "net.minecraft.client.renderer.EntityRenderer");
        report(transformer.transform("x", "net.minecraft.client.renderer.ItemRenderer", other) == other,
                "non-target classes are returned untouched");

        System.setProperty("akashic.voidsky.disable", "true");
        byte[] rg = read(args[0], "net.minecraft.client.renderer.RenderGlobal");
        report(transformer.transform("x", "net.minecraft.client.renderer.RenderGlobal", rg) == rg,
                "-Dakashic.voidsky.disable=true leaves classes untouched");
        System.clearProperty("akashic.voidsky.disable");

        if (failures > 0) {
            System.out.println("FAILED: " + failures + " check(s)");
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED");
    }

    private static void check(String jar, String className, String methodName, String hook, int before, int after,
                              VoidSkyTransformer transformer) throws Exception {
        System.out.println("== " + className + "." + methodName);
        byte[] original = read(jar, className);
        byte[] patched = transformer.transform("obf", className, original);
        report(patched != original && !Arrays.equals(patched, original), "class was patched");

        ClassNode node = new ClassNode();
        new ClassReader(patched).accept(node, 0);
        int hooks = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode && "com/akashic/compat/voidsky/VoidSkyHooks".equals(((MethodInsnNode) insn).owner)) {
                    hooks++;
                    MethodInsnNode call = (MethodInsnNode) insn;
                    report(method.name.equals(methodName), "hook sits in " + methodName + " (found in " + method.name + ")");
                    report(call.name.equals(hook) && call.desc.equals("(D)D") && call.getOpcode() == Opcodes.INVOKESTATIC,
                            "calls VoidSkyHooks." + hook + "(D)D");
                    report(prevReal(insn).getOpcode() == before, "right after " + opName(before));
                    report(nextReal(insn).getOpcode() == after, "right before " + opName(after));
                }
            }
            // Stack/local type consistency of every method after the change.
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
        report(hooks == 1, "exactly one hook inserted (" + hooks + ")");
        report(true, "all " + node.methods.size() + " methods pass ASM BasicVerifier");
    }

    private static AbstractInsnNode prevReal(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getPrevious();
        while (p != null && p.getOpcode() < 0) {
            p = p.getPrevious();
        }
        return p;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode n = insn.getNext();
        while (n != null && n.getOpcode() < 0) {
            n = n.getNext();
        }
        return n;
    }

    private static String opName(int opcode) {
        switch (opcode) {
            case Opcodes.DSUB: return "DSUB";
            case Opcodes.DMUL: return "DMUL";
            case Opcodes.DSTORE: return "DSTORE";
            case Opcodes.D2F: return "D2F";
            default: return String.valueOf(opcode);
        }
    }

    private static byte[] read(String jar, String className) throws Exception {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry(className.replace('.', '/') + ".class");
            if (entry == null) {
                throw new IllegalArgumentException(className + " not found in " + jar);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        }
    }

    private static void report(boolean ok, String what) {
        System.out.println((ok ? "   OK   " : "   FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
