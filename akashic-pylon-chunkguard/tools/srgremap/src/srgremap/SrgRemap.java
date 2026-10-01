package srgremap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

/**
 * Build tool (not shipped): remaps the notch-named Minecraft/Forge classes of a jar to SRG names using FML's own
 * deobfuscation data (deobfuscation_data-1.7.10.lzma inside the Crucible/Forge server jar, decompressed to .srg).
 * This is the same notch->SRG table FML's DeobfuscationTransformer applies at runtime. The output is only used as a
 * compile classpath (javac reads declarations), so member references inside method bodies are not hierarchy-resolved.
 *
 * usage: java -cp asm.jar:asm-commons.jar:. srgremap.SrgRemap <deobf.srg> <in.jar> <outDir> [prefix ...]
 */
public final class SrgRemap {

	public static void main(String[] args) throws Exception {
		Map<String, String> map = new HashMap<String, String>();
		BufferedReader r = new BufferedReader(new FileReader(args[0]));
		String line;
		while ((line = r.readLine()) != null) {
			String[] p = line.split(" ");
			if (line.startsWith("CL: ")) {
				map.put(p[1], p[2]);
			}
			else if (line.startsWith("FD: ")) {
				int i = p[1].lastIndexOf('/');
				map.put(p[1].substring(0, i) + "." + p[1].substring(i + 1), p[2].substring(p[2].lastIndexOf('/') + 1));
			}
			else if (line.startsWith("MD: ")) {
				int i = p[1].lastIndexOf('/');
				map.put(p[1].substring(0, i) + "." + p[1].substring(i + 1) + p[2], p[3].substring(p[3].lastIndexOf('/') + 1));
			}
		}
		r.close();
		SimpleRemapper remapper = new SimpleRemapper(map);
		File out = new File(args[2]);
		int n = 0;
		ZipInputStream zin = new ZipInputStream(new FileInputStream(args[1]));
		ZipEntry e;
		while ((e = zin.getNextEntry()) != null) {
			String name = e.getName();
			if (!name.endsWith(".class"))
				continue;
			if (args.length > 3) {
				boolean ok = false;
				for (int k = 3; k < args.length; k++)
					ok |= name.startsWith(args[k]);
				if (!ok)
					continue;
			}
			byte[] bytes = readAll(zin);
			ClassReader cr = new ClassReader(bytes);
			ClassWriter cw = new ClassWriter(0);
			cr.accept(new ClassRemapper(cw, remapper), 0);
			String internal = remapper.map(cr.getClassName());
			if (internal == null)
				internal = cr.getClassName();
			File f = new File(out, internal + ".class");
			f.getParentFile().mkdirs();
			OutputStream o = new FileOutputStream(f);
			o.write(cw.toByteArray());
			o.close();
			n++;
		}
		zin.close();
		System.out.println("remapped " + n + " classes into " + out);
	}

	private static byte[] readAll(InputStream in) throws Exception {
		java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int k;
		while ((k = in.read(buf)) > 0)
			b.write(buf, 0, k);
		return b.toByteArray();
	}
}
