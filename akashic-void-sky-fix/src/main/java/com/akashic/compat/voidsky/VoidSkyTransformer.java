package com.akashic.compat.voidsky;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Inserts one {@code INVOKESTATIC VoidSkyHooks.x(D)D} right after the arithmetic that feeds the
 * vanilla "below the world" darkening:
 * <ul>
 *   <li>RenderGlobal.renderSky: {@code ... - world.getHorizon()} (INVOKEVIRTUAL getHorizon, DSUB)</li>
 *   <li>EntityRenderer.updateFogColor: {@code ... * provider.getVoidFogYFactor()} (INVOKEVIRTUAL, DMUL)</li>
 *   <li>Dynamic Surroundings BiomeFogColorCalculator.applyPlayerEffects: same as updateFogColor,
 *       because it recomputes the fog colour in the FogColors event</li>
 * </ul>
 * The inserted call pops and pushes one double, so stack heights and frames are unchanged.
 * Each target must match exactly once; otherwise the class is left untouched and the refusal is
 * logged.
 */
public final class VoidSkyTransformer implements IClassTransformer {

    private static final String HOOKS = "com/akashic/compat/voidsky/VoidSkyHooks";
    private static final String LOG = "[Akashic Void Sky Fix] ";

    private static final Target[] TARGETS = {
            new Target("net.minecraft.client.renderer.RenderGlobal",
                    names("func_72714_a", "renderSky"), "(F)V",
                    names("func_72919_O", "getHorizon"), Opcodes.DSUB, "skyHeightAboveHorizon"),
            new Target("net.minecraft.client.renderer.EntityRenderer",
                    names("func_78466_h", "updateFogColor"), "(F)V",
                    names("func_76565_k", "getVoidFogYFactor"), Opcodes.DMUL, "voidFogBrightness"),
            new Target("org.blockartistry.mod.DynSurround.client.fog.BiomeFogColorCalculator",
                    names("applyPlayerEffects"), null,
                    names("func_76565_k", "getVoidFogYFactor"), Opcodes.DMUL, "voidFogBrightness"),
    };

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || Boolean.getBoolean("akashic.voidsky.disable")) {
            return basicClass;
        }
        for (Target target : TARGETS) {
            if (target.className.equals(transformedName)) {
                return patch(target, basicClass);
            }
        }
        return basicClass;
    }

    static byte[] patch(Target target, byte[] basicClass) {
        try {
            ClassNode classNode = new ClassNode();
            new ClassReader(basicClass).accept(classNode, 0);

            List<AbstractInsnNode> anchors = new ArrayList<AbstractInsnNode>();
            for (MethodNode method : classNode.methods) {
                if (!target.methodNames.contains(method.name)
                        || (target.methodDesc != null && !target.methodDesc.equals(method.desc))) {
                    continue;
                }
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && target.callNames.contains(((MethodInsnNode) insn).name)
                            && "()D".equals(((MethodInsnNode) insn).desc)) {
                        AbstractInsnNode next = nextReal(insn);
                        if (next != null && next.getOpcode() == target.followingOpcode) {
                            anchors.add(next);
                        }
                    }
                }
                if (anchors.size() == 1) {
                    method.instructions.insert(anchors.get(0),
                            new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, target.hook, "(D)D", false));
                }
                break;
            }

            if (anchors.size() != 1) {
                System.err.println(LOG + "REFUSED patch of " + target.className + ": expected exactly 1 match, found "
                        + anchors.size() + ". Class left untouched.");
                return basicClass;
            }

            ClassWriter writer = new ClassWriter(0);
            classNode.accept(writer);
            System.out.println(LOG + "Patched " + target.className + " -> VoidSkyHooks." + target.hook);
            return writer.toByteArray();
        } catch (Throwable t) {
            System.err.println(LOG + "REFUSED patch of " + target.className + " after transformer failure; original class kept. " + t);
            t.printStackTrace();
            return basicClass;
        }
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    private static List<String> names(String... names) {
        return Arrays.asList(names);
    }

    static final class Target {
        final String className;
        final List<String> methodNames;
        final String methodDesc;
        final List<String> callNames;
        final int followingOpcode;
        final String hook;

        Target(String className, List<String> methodNames, String methodDesc, List<String> callNames,
               int followingOpcode, String hook) {
            this.className = className;
            this.methodNames = methodNames;
            this.methodDesc = methodDesc;
            this.callNames = callNames;
            this.followingOpcode = followingOpcode;
            this.hook = hook;
        }
    }
}
