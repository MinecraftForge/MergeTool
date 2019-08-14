/*
 * MergeTool
 * Copyright (c) 2016-2018.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package net.minecraftforge.mergetool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

public class Stripper
{
    private Set<String> classes = new HashSet<>();
    private Set<String> targets = new HashSet<>();

    public void loadData(File file) throws IOException
    {
        Files.lines(file.toPath()).forEach(line ->
        {
            int idx = line.indexOf('#');
            if (idx == 0 || line.isEmpty()) return;
            if (idx != -1) line = line.substring(0, idx - 1);
            if (line.charAt(0) == '\t') line = line.substring(1);
            String[] pts = (line.trim() + "    ").split(" ", -1);
            classes.add(pts[0]);
            if (pts.length > 1)
                targets.add(pts[0] + ' ' + pts[1]);
            else
                targets.add(pts[0]);
        });
    }

    public void process(File input, File output) throws IOException
    {
        if (output.exists()) output.delete();
        if (!output.getParentFile().exists()) output.getParentFile().mkdirs();
        output.createNewFile();

        Set<String> types = new HashSet<>();
        for (AnnotationVersion an : AnnotationVersion.values())
        {
            for (String cls : an.getClasses())
                types.add('L' + cls + ';');
        }

        try (ZipInputStream  zis = new ZipInputStream(new FileInputStream(input));
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(output)))
        {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null)
            {
                ZipEntry next = new ZipEntry(entry.getName());
                next.setTime(entry.getTime());
                next.setLastModifiedTime(entry.getLastModifiedTime());
                zos.putNextEntry(next);
                if (!entry.getName().endsWith(".class") || !classes.contains(entry.getName().substring(0, entry.getName().length() - 6)))
                {
                    int read;
                    byte[] buf = new byte[0x100];
                    while ((read = zis.read(buf, 0, buf.length)) != -1)
                        zos.write(buf, 0, read);
                }
                else
                {
                    ClassReader reader = new ClassReader(zis);
                    ClassNode node = new ClassNode();
                    reader.accept(node, 0);

                    if (node.methods != null)
                    {
                        node.methods.forEach(mtd ->
                        {
                            if (targets.contains(node.name + ' ' + mtd.name + mtd.desc))
                            {
                                if (mtd.visibleAnnotations != null)
                                {
                                    Iterator<AnnotationNode> itr = mtd.visibleAnnotations.iterator();
                                    while (itr.hasNext())
                                    {
                                        if (types.contains(itr.next().desc))
                                            itr.remove();
                                    }
                                }
                            }
                        });
                    }

                    if (node.visibleAnnotations != null)
                    {
                        Iterator<AnnotationNode> itr = node.visibleAnnotations.iterator();
                        while (itr.hasNext())
                        {
                            if (types.contains(itr.next().desc))
                                itr.remove();
                        }
                    }

                    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    node.accept(writer);
                    zos.write(writer.toByteArray());
                }
                zos.closeEntry();
            }
        }
    }
}
