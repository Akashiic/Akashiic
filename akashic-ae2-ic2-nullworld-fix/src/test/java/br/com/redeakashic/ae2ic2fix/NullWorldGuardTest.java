package br.com.redeakashic.ae2ic2fix;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.minecraft.launchwrapper.LaunchClassLoader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Runs the real {@code appeng/tile/powersink/IC2.class} from the AE2 jar against minimal stubs.
 *
 * <p>Usage: {@code NullWorldGuardTest <ae2.jar> <stubs-classes-dir> <ic2-only-dir> <mod.jar> <asm.jar> <forge.jar>}
 */
public final class NullWorldGuardTest {

    private static final String IC2 = "appeng.tile.powersink.IC2";
    private static final String INSCRIBER = "appeng.tile.misc.FakeAdvancedInscriber";
    private static int checks;

    public static void main(final String[] args) throws Exception {
        final File ae2Jar = new File(args[0]);
        final File stubs = new File(args[1]);
        final byte[] original = readEntry(ae2Jar, "appeng/tile/powersink/IC2.class");
        final byte[] patched = AE2PowerSinkTransformer.patch(original, AE2PowerSinkTransformer.WORLD_FIELD_SRG);

        check(!Arrays.equals(original, patched), "patch changes the class");
        checkGuardAtHead(patched);

        // 1. Unpatched AE2 bytecode reproduces the crash from the report.
        final StubLoader unpatchedLoader = new StubLoader(stubs, original);
        final Object net0 = installEnergyNet(unpatchedLoader);
        final Throwable crash = constructExpectingFailure(unpatchedLoader);
        check(crash instanceof NullPointerException && "world is null".equals(crash.getMessage()),
                "unpatched TileAdvancedInscriber.<init> throws NPE 'world is null' (got " + crash + ")");
        check(loadedCount(net0) == 0, "unpatched: nothing registered");

        // 2. Patched: constructor succeeds, IC2 registration is deferred to onReady().
        final StubLoader patchedLoader = new StubLoader(stubs, patched);
        final Object net = installEnergyNet(patchedLoader);
        final Object tile = patchedLoader.loadClass(INSCRIBER).getConstructor().newInstance();
        check(loadedCount(net) == 0, "patched: constructor (world == null) does not touch the IC2 energy net");

        final Object world = patchedLoader.loadClass("net.minecraft.world.World").getConstructor().newInstance();
        tile.getClass().getMethod("func_145834_a", world.getClass()).invoke(tile, world);
        tile.getClass().getMethod("onReady").invoke(tile);
        check(loadedCount(net) == 1, "patched: onReady() with a world registers the tile in the IC2 energy net");
        tile.getClass().getMethod("onReady").invoke(tile);
        check(loadedCount(net) == 1, "patched: second onReady() does not register twice");

        // 3. Transformer scope and idempotency.
        final AE2PowerSinkTransformer transformer = new AE2PowerSinkTransformer();
        check(transformer.transform("x", "appeng.tile.misc.TileInscriber", original) == original,
                "other classes are returned untouched");
        check(transformer.transform(IC2, IC2, null) == null, "null class bytes are passed through");
        check(AE2PowerSinkTransformer.patch(patched, AE2PowerSinkTransformer.WORLD_FIELD_SRG) == patched,
                "patching an already guarded class is a no-op");

        // 4. End to end through a real launchwrapper LaunchClassLoader, as FML does it.
        final URL[] urls = { stubs.toURI().toURL(), new File(args[2]).toURI().toURL(),
                new File(args[3]).toURI().toURL(), new File(args[4]).toURI().toURL(),
                new File(args[5]).toURI().toURL() };
        final LaunchClassLoader lcl = new LaunchClassLoader(urls);
        lcl.addTransformerExclusion("br.com.redeakashic.ae2ic2fix.");
        lcl.registerTransformer(AE2PowerSinkTransformer.class.getName());
        final Object lclNet = installEnergyNet(lcl);
        Class.forName(INSCRIBER, true, lcl).getConstructor().newInstance();
        check(loadedCount(lclNet) == 0, "LaunchClassLoader: AE2 IC2 class is patched on load, constructor is safe");

        System.out.println("ALL " + checks + " CHECKS PASSED");
    }

    private static void checkGuardAtHead(final byte[] patched) {
        final ClassNode cn = new ClassNode();
        new ClassReader(patched).accept(cn, 0);
        for (final Object o : cn.methods) {
            final MethodNode m = (MethodNode) o;
            if (!"addToENet".equals(m.name)) continue;
            AbstractInsnNode n = m.instructions.getFirst();
            while (n.getOpcode() < 0) n = n.getNext();
            check(n.getOpcode() == Opcodes.ALOAD, "guard: aload_0");
            n = n.getNext();
            check(n.getOpcode() == Opcodes.GETFIELD && "field_145850_b".equals(((FieldInsnNode) n).name)
                    && "net/minecraft/tileentity/TileEntity".equals(((FieldInsnNode) n).owner),
                    "guard: getfield TileEntity.field_145850_b (worldObj)");
            check(n.getNext().getOpcode() == Opcodes.IFNONNULL, "guard: ifnonnull");
            check(n.getNext().getNext().getOpcode() == Opcodes.RETURN, "guard: return");
            return;
        }
        throw new AssertionError("addToENet not found in patched class");
    }

    private static Object installEnergyNet(final ClassLoader loader) throws Exception {
        final Object net = Class.forName("akashic.test.FakeEnergyNet", true, loader).getConstructor().newInstance();
        Class.forName("appeng.integration.IntegrationRegistry", true, loader).getField("ic2").set(null, net);
        return net;
    }

    private static int loadedCount(final Object net) throws Exception {
        return ((List<?>) net.getClass().getField("loaded").get(net)).size();
    }

    private static Throwable constructExpectingFailure(final ClassLoader loader) throws Exception {
        try {
            loader.loadClass(INSCRIBER).getConstructor().newInstance();
        } catch (final InvocationTargetException e) {
            return e.getCause();
        }
        return null;
    }

    private static void check(final boolean ok, final String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        checks++;
        System.out.println("ok - " + what);
    }

    private static byte[] readEntry(final File jar, final String entry) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            final ZipEntry e = zip.getEntry(entry);
            if (e == null) throw new IOException(entry + " not in " + jar);
            try (InputStream in = zip.getInputStream(e)) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buf = new byte[8192];
                for (int r; (r = in.read(buf)) > 0;) out.write(buf, 0, r);
                return out.toByteArray();
            }
        }
    }

    /** Child-first loader: the given IC2 bytes plus the compiled stubs; everything else from the parent. */
    private static final class StubLoader extends ClassLoader {
        private final File stubs;
        private final byte[] ic2;

        StubLoader(final File stubs, final byte[] ic2) {
            super(NullWorldGuardTest.class.getClassLoader());
            this.stubs = stubs;
            this.ic2 = ic2;
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            synchronized (this.getClassLoadingLock(name)) {
                Class<?> c = this.findLoadedClass(name);
                if (c == null) {
                    final byte[] b = IC2.equals(name) ? this.ic2 : this.readStub(name);
                    if (b == null) return super.loadClass(name, resolve);
                    c = this.defineClass(name, b, 0, b.length);
                }
                if (resolve) this.resolveClass(c);
                return c;
            }
        }

        private byte[] readStub(final String name) {
            final File f = new File(this.stubs, name.replace('.', '/') + ".class");
            try {
                return f.isFile() ? Files.readAllBytes(f.toPath()) : null;
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
