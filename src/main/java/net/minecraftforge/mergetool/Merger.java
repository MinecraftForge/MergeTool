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

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.google.common.base.Equivalence;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

import com.google.common.base.Function;
import com.google.common.collect.Lists;

@SuppressWarnings("unchecked")
public class Merger
{
    private static final boolean DEBUG = false;

    private final File client;
    private final File server;
    private final File merged;
    private AnnotationVersion annotation = null;
    private boolean annotationInject = true;
    private FieldName FIELD = new FieldName();
    private MethodDesc METHOD = new MethodDesc();
    private HashSet<String> whitelist = new HashSet<>();
    private boolean copyData = false;
    private boolean keepMeta = false;

    public Merger(File client, File server, File merged)
    {
        this.client = client;
        this.server = server;
        this.merged = merged;
    }

    public Merger annotate(AnnotationVersion ano, boolean inject)
    {
        this.annotation = ano;
        this.annotationInject = inject;
        return this;
    }

    public Merger whitelist(String file)
    {
        this.whitelist.add(file);
        return this;
    }

    public Merger keepData()
    {
        this.copyData = true;
        return this;
    }

    public Merger skipData()
    {
        this.copyData = false;
        return this;
    }

    public Merger keepMeta()
    {
        this.keepMeta = true;
        return this;
    }

    public Merger skipMeta()
    {
        this.keepMeta = false;
        return this;
    }

    public void process() throws IOException
    {
        try (
            ZipFile cInJar = new ZipFile(this.client);
            ZipFile sInJar = new ZipFile(this.server);
            ZipOutputStream outJar = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(this.merged)))
        ) {
            Set<String> added = new HashSet<>();
            Map<String, ZipEntry> cClasses = getClassEntries(cInJar, outJar, added);
            Map<String, ZipEntry> sClasses = getClassEntries(sInJar, outJar, null); //Skip data from the server, as it contains libraries.

            for (Entry<String, ZipEntry> entry : cClasses.entrySet())
            {
                String name = entry.getKey();

                if (!this.whitelist.isEmpty() && !this.whitelist.contains(name))
                    continue;

                ZipEntry cEntry = entry.getValue();
                ZipEntry sEntry = sClasses.get(name);

                if (sEntry == null)
                {
                    if (DEBUG)
                    {
                        System.out.println("Copy class c->s : " + name);
                    }
                    copyClass(cInJar, cEntry, outJar, true);
                }
                else
                {

                    if (DEBUG)
                    {
                        System.out.println("Processing class: " + name);
                    }

                    sClasses.remove(name);

                    byte[] cData = readEntry(cInJar, entry.getValue());
                    byte[] sData = readEntry(sInJar, sEntry);
                    byte[] data = processClass(cData, sData);

                    outJar.putNextEntry(getNewEntry(cEntry.getName()));
                    outJar.write(data);
                }
            }

            for (Entry<String, ZipEntry> entry : sClasses.entrySet())
            {
                if (!this.whitelist.isEmpty() && !this.whitelist.contains(entry.getKey()))
                    continue;

                if (DEBUG)
                {
                    System.out.println("Copy class s->c : " + entry.getKey());
                }
                copyClass(sInJar, entry.getValue(), outJar, false);
            }

            if (this.annotation != null && this.annotationInject)
            {
                for (String cls : this.annotation.getClasses())
                {
                    byte[] data = getResourceBytes(cls + ".class");

                    outJar.putNextEntry(getNewEntry(cls + ".class"));
                    outJar.write(data);
                }
            }

        }
    }

    private ZipEntry getNewEntry(String name)
    {
        ZipEntry ret = new ZipEntry(name);
        ret.setTime(0); //Stabilize output as java will use current time if we don't set this.
        return ret;
    }

    private void copyClass(ZipFile inJar, ZipEntry entry, ZipOutputStream outJar, boolean isClientOnly) throws IOException
    {
        ClassReader reader = new ClassReader(readEntry(inJar, entry));
        ClassNode classNode = new ClassNode();

        reader.accept(classNode, 0);

        if (this.annotation != null)
            this.annotation.add(classNode, isClientOnly);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);

        byte[] data = writer.toByteArray();

        outJar.putNextEntry(getNewEntry(entry.getName()));
        outJar.write(data);
    }

    private Map<String, ZipEntry> getClassEntries(ZipFile inFile, ZipOutputStream output, Set<String> added) throws IOException
    {
        Map<String, ZipEntry> ret = new Hashtable<String, ZipEntry>();
        for (ZipEntry entry : Collections.list((Enumeration<ZipEntry>)inFile.entries()))
        {
            String entryName = entry.getName();
            if (!entry.isDirectory() && entryName.endsWith(".class") && !entryName.startsWith("."))
            {
                ret.put(entryName.replace(".class", ""), entry);
            }
            else if (this.copyData && added != null && !added.contains(entryName))
            {
                if (!this.keepMeta && entryName.startsWith("META-INF"))
                    continue;

                if (entry.isDirectory())
                {
                    //Skip directories, they arnt required.
                    //output.putNextEntry(getNewEntry(entryName)); //New entry to reset time
                    added.add(entryName);
                }
                else
                {
                    output.putNextEntry(getNewEntry(entryName));
                    output.write(readEntry(inFile, entry));
                    added.add(entryName);
                }
            }
        }
        return ret;
    }

    private byte[] readEntry(ZipFile inFile, ZipEntry entry) throws IOException
    {
        return readFully(inFile.getInputStream(entry));
    }

    private byte[] readFully(InputStream stream) throws IOException
    {
        byte[] data = new byte[4096];
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int len;
        do
        {
            len = stream.read(data);
            if (len > 0)
            {
                buf.write(data, 0, len);
            }
        } while (len != -1);

        return buf.toByteArray();
    }

    private byte[] processClass(byte[] cIn, byte[] sIn)
    {
        ClassNode cClassNode = getClassNode(cIn);
        ClassNode sClassNode = getClassNode(sIn);

        processFields(cClassNode, sClassNode);
        processMethods(cClassNode, sClassNode);
        processInners(cClassNode, sClassNode);
        processInterfaces(cClassNode, sClassNode);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cClassNode.accept(writer);
        return writer.toByteArray();
    }

    private boolean innerMatches(InnerClassNode o, InnerClassNode o2)
    {
        return equals(o.innerName, o2.innerName) &&
               equals(o.name,      o2.name) &&
               equals(o.outerName, o2.outerName);
    }

    private boolean equals(Object o1, Object o2)
    {
        return o1 == null ? o2 == null : o2 == null ? false : o1.equals(o2);
    }

    private void processInners(ClassNode cClass, ClassNode sClass)
    {
        List<InnerClassNode> cIners = cClass.innerClasses;
        List<InnerClassNode> sIners = sClass.innerClasses;

        for (InnerClassNode n : cIners)
        {
            if (!sIners.stream().anyMatch(e -> innerMatches(e, n)))
                sIners.add(n);
        }
        for (InnerClassNode n : sIners)
        {
            if (!cIners.stream().anyMatch(e -> innerMatches(e, n)))
                cIners.add(n);
        }
    }

    private void processInterfaces(ClassNode cClass, ClassNode sClass)
    {
        List<String> cIntfs = cClass.interfaces;
        List<String> sIntfs = sClass.interfaces;
        List<String> cOnly = new ArrayList<>();
        List<String> sOnly = new ArrayList<>();

        for (String n : cIntfs)
        {
            if (!sIntfs.contains(n))
            {
                sIntfs.add(n);
                cOnly.add(n);
            }
        }
        for (String n : sIntfs)
        {
            if (!cIntfs.contains(n))
            {
                cIntfs.add(n);
                sOnly.add(n);
            }
        }
        Collections.sort(cIntfs); //Sort things, we're in obf territory but should stabilize things.
        Collections.sort(sIntfs);

        if (this.annotation != null && !cOnly.isEmpty() || !sOnly.isEmpty())
        {
            this.annotation.add(cClass, cOnly, sOnly);
            this.annotation.add(sClass, cOnly, sOnly);
        }
    }

    private ClassNode getClassNode(byte[] data)
    {
        ClassReader reader = new ClassReader(data);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        return classNode;
    }

    private void processFields(ClassNode cClass, ClassNode sClass)
    {
        merge(cClass.name, sClass.name, cClass.fields, sClass.fields, Equivalence.equals().onResultOf(FIELD), FIELD, FIELD, FIELD);
    }

    private void processMethods(ClassNode cClass, ClassNode sClass)
    {
        merge(cClass.name, sClass.name, cClass.methods, sClass.methods, Equivalence.equals().onResultOf(METHOD), METHOD, METHOD, METHOD);
    }

    private interface MemberAnnotator<T>
    {
        T process(T member, boolean isClient);
    }

    private class FieldName implements Function<FieldNode, String>, MemberAnnotator<FieldNode>, Comparator<FieldNode>
    {
        public String apply(FieldNode in)
        {
            return in == null ? "null" : in.name;
        }

        public FieldNode process(FieldNode field, boolean isClient)
        {
            if (Merger.this.annotation != null)
                Merger.this.annotation.add(field, isClient);
            return field;
        }

        @Override
        public int compare(FieldNode a, FieldNode b)
        {
            if (a == b) return 0;
            if (a == null) return 1;
            if (b == null) return -1;
            return a.name.compareTo(b.name);
        }
    }

    private class MethodDesc implements Function<MethodNode, String>, MemberAnnotator<MethodNode>, Comparator<MethodNode>
    {
        public String apply(MethodNode node)
        {
            return node == null ? "null" : node.name + node.desc;
        }

        public MethodNode process(MethodNode node, boolean isClient)
        {
            if (Merger.this.annotation != null)
                Merger.this.annotation.add(node, isClient);
            return node;
        }

        private int findLine(MethodNode member)
        {
            for (int x = 0; x < member.instructions.size(); x++)
            {
                AbstractInsnNode insn = member.instructions.get(x);
                if (insn instanceof LineNumberNode)
                {
                    return ((LineNumberNode)insn).line;
                }
            }
            return Integer.MAX_VALUE;
        }

        @Override
        public int compare(MethodNode a, MethodNode b)
        {
            if (a == b) return 0;
            if (a == null) return 1;
            if (b == null) return -1;
            return findLine(a) - findLine(b);
        }
    }

    private <T> void merge(String cName, String sName, List<T> client, List<T> server, Equivalence<? super T> eq,
            MemberAnnotator<T> annotator, Function<T, String> toString, Comparator<T> compare)
    {
        // adding null to the end to not handle the index overflow in a special way
        client.add(null);
        server.add(null);
        List<T> common = Lists.newArrayList();
        for(T ct : client)
        {
            for (T st : server)
            {
                if (eq.equivalent(ct, st))
                {
                    common.add(ct);
                    break;
                }
            }
        }

        int i = 0, mi = 0;
        for(; i < client.size(); i++)
        {
            T ct = client.get(i);
            T st = server.get(i);
            T mt = common.get(mi);

            if (eq.equivalent(ct, st))
            {
                mi++;
                if (!eq.equivalent(ct, mt))
                    throw new IllegalStateException("merged list is in bad state: " + toString.apply(ct) + " " + toString.apply(st) + " " + toString.apply(mt));
                if (DEBUG)
                    System.out.printf("%d/%d %d/%d Both Shared  : %s %s\n", i, client.size(), mi, common.size(), sName, toString.apply(st));

            }
            else if(eq.equivalent(st, mt))
            {
                server.add(i, annotator.process(ct, true));
                if (DEBUG)
                    System.out.printf("%d/%d %d/%d Server *add* : %s %s\n", i, client.size(), mi, common.size(), sName, toString.apply(ct));
            }
            else if (eq.equivalent(ct, mt))
            {
                client.add(i, annotator.process(st, false));
                if (DEBUG)
                    System.out.printf("%d/%d %d/%d Client *add* : %s %s\n", i, client.size(), mi, common.size(), cName, toString.apply(st));
            }
            else // Both server and client add a new method before we get to the next common method... Lets try and prioritize one.
            {
                int diff = compare.compare(ct,  st);
                if  (diff > 0)
                {
                    client.add(i, annotator.process(st, false));
                    if (DEBUG)
                        System.out.printf("%d/%d %d/%d Client *add* : %s %s\n", i, client.size(), mi, common.size(), cName, toString.apply(st));
                }
                else /* if (diff < 0) */ //Technically this should be <0 and we special case when they can't agree who goes first.. but for now just push the client's first.
                {
                    server.add(i, annotator.process(ct, true));
                    if (DEBUG)
                        System.out.printf("%d/%d %d/%d Server *add* : %s %s\n", i, client.size(), mi, common.size(), sName, toString.apply(ct));
                }
            }
        }
        if (i < server.size() || mi < common.size() || (client.size() != server.size()))
        {
            throw new IllegalStateException("merged list is in bad state: " + i + " " + mi);
        }
        // removing the null
        client.remove(client.size() - 1);
        server.remove(server.size() - 1);
    }

    private byte[] getResourceBytes(String path) throws IOException
    {
        try (InputStream stream = Merger.class.getResourceAsStream("/" + path))
        {
            return readFully(stream);
        }
    }
}
