package br.com.redeakashic.ae2ic2fix;

import net.minecraft.launchwrapper.IClassTransformer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Injects a null-world guard at the head of {@code appeng.tile.powersink.IC2#addToENet()}.
 *
 * <p>AE2 rv3-beta GTNH (up to 1077) calls {@code setPowerSides(...)} from the
 * {@code TileAdvancedInscriber} constructor. That reaches {@code addToENet()}, which posts an IC2
 * {@code EnergyTileLoadEvent} while {@code worldObj} is still null, and IC2 throws
 * {@code NullPointerException: world is null}. The patched method becomes:
 *
 * <pre>
 * private void addToENet() {
 *     if (this.worldObj == null) return; // injected
 *     ...original body...
 * }
 * </pre>
 *
 * The tile still joins the IC2 energy net through {@code onReady()}, which AE2 calls once the
 * tile has a world.
 */
public class AE2PowerSinkTransformer implements IClassTransformer {

    static final Logger LOG = LogManager.getLogger("AkashicAE2IC2Fix");

    static final String TARGET_CLASS = "appeng.tile.powersink.IC2";
    static final String TARGET_METHOD = "addToENet";
    static final String TARGET_DESC = "()V";

    static final String TILE_ENTITY = "net/minecraft/tileentity/TileEntity";
    static final String WORLD_DESC = "Lnet/minecraft/world/World;";
    /** TileEntity.worldObj: SRG name at runtime, MCP name in a dev workspace. */
    static final String WORLD_FIELD_SRG = "field_145850_b";
    static final String WORLD_FIELD_MCP = "worldObj";

    @Override
    public byte[] transform(final String name, final String transformedName, final byte[] basicClass) {
        if (basicClass == null || !TARGET_CLASS.equals(transformedName)) {
            return basicClass;
        }
        try {
            return patch(basicClass, AkashicAE2FixPlugin.isObfuscatedEnvironment() ? WORLD_FIELD_SRG : WORLD_FIELD_MCP);
        } catch (final Throwable t) {
            LOG.error("Failed to patch {}#{}; leaving it untouched.", TARGET_CLASS, TARGET_METHOD, t);
            return basicClass;
        }
    }

    static byte[] patch(final byte[] bytes, final String worldField) {
        final ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);

        MethodNode target = null;
        for (final Object o : cn.methods) { // raw List in asm-all 5.0.3
            final MethodNode m = (MethodNode) o;
            if (TARGET_METHOD.equals(m.name) && TARGET_DESC.equals(m.desc) && (m.access & Opcodes.ACC_STATIC) == 0) {
                target = m;
                break;
            }
        }
        if (target == null) {
            LOG.warn("{}#{}{} not found (AE2 version changed?). Patch skipped.", TARGET_CLASS, TARGET_METHOD, TARGET_DESC);
            return bytes;
        }

        final AbstractInsnNode head = firstRealInsn(target);
        if (isNullWorldGuard(head)) {
            LOG.info("{}#{} already has a null-world guard. Nothing to do.", TARGET_CLASS, TARGET_METHOD);
            return bytes;
        }

        final LabelNode proceed = new LabelNode();
        final InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new FieldInsnNode(Opcodes.GETFIELD, TILE_ENTITY, worldField, WORLD_DESC));
        guard.add(new JumpInsnNode(Opcodes.IFNONNULL, proceed));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(proceed);
        // The jump target needs a stack map frame, unless the original code already starts with one.
        if (!(head instanceof FrameNode)) {
            guard.add(new FrameNode(Opcodes.F_NEW, 1, new Object[] { cn.name }, 0, new Object[0]));
        }
        target.instructions.insert(guard);
        target.maxStack = Math.max(target.maxStack, 1);

        final ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        LOG.info("Patched {}#{}: null-world guard injected (fixes IC2 'world is null' from TileAdvancedInscriber).",
                TARGET_CLASS, TARGET_METHOD);
        return cw.toByteArray();
    }

    /** First instruction that is not a label or line number (frames are kept, they matter here). */
    private static AbstractInsnNode firstRealInsn(final MethodNode m) {
        AbstractInsnNode n = m.instructions.getFirst();
        while (n != null && (n.getType() == AbstractInsnNode.LABEL || n.getType() == AbstractInsnNode.LINE)) {
            n = n.getNext();
        }
        return n;
    }

    /** Matches {@code aload_0; getfield <any>:World; ifnull/ifnonnull}. */
    private static boolean isNullWorldGuard(final AbstractInsnNode head) {
        if (head == null || head.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) head).var != 0) {
            return false;
        }
        final AbstractInsnNode get = head.getNext();
        if (get == null || get.getOpcode() != Opcodes.GETFIELD || !WORLD_DESC.equals(((FieldInsnNode) get).desc)) {
            return false;
        }
        final AbstractInsnNode jump = get.getNext();
        return jump != null && (jump.getOpcode() == Opcodes.IFNULL || jump.getOpcode() == Opcodes.IFNONNULL);
    }
}
