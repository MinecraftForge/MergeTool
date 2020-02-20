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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

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
        ret.setTime(0x92D6688800L); //Stabilize output as java will use current time if we don't set this, we can't use 0 as older java versions output different jars for values less then 1980
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

    private ClassNode getClassNode(byte[] data)
    {
        ClassReader reader = new ClassReader(data);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        return classNode;
    }

    private void processFields(ClassNode cClass, ClassNode sClass)
    {
        List<FieldNode> cFields = cClass.fields;
        List<FieldNode> sFields = sClass.fields;
        String cFieldsStr = cFields.stream().map(e -> e.name).collect(Collectors.joining(", "));
        String sFieldsStr = sFields.stream().map(e -> e.name).collect(Collectors.joining(", "));

        int serverFieldIdx = 0;
        if (DEBUG)
            System.out.printf("B: Server List: [%s]\nB: Client List: [%s]\n", sFieldsStr, cFieldsStr);
        for (int clientFieldIdx = 0; clientFieldIdx < cFields.size(); clientFieldIdx++)
        {
            FieldNode clientField = cFields.get(clientFieldIdx);
            if (serverFieldIdx < sFields.size())
            {
                FieldNode serverField = sFields.get(serverFieldIdx);
                if (!clientField.name.equals(serverField.name))
                {
                    boolean foundServerField = false;
                    for (int serverFieldSearchIdx = serverFieldIdx + 1; serverFieldSearchIdx < sFields.size(); serverFieldSearchIdx++)
                    {
                        if (clientField.name.equals(sFields.get(serverFieldSearchIdx).name))
                        {
                            foundServerField = true;
                            break;
                        }
                    }
                    // Found a server field match ahead in the list - walk to it and add the missing server fields to the client
                    if (foundServerField)
                    {
                        boolean foundClientField = false;
                        for (int clientFieldSearchIdx = clientFieldIdx + 1; clientFieldSearchIdx < cFields.size(); clientFieldSearchIdx++)
                        {
                            if (serverField.name.equals(cFields.get(clientFieldSearchIdx).name))
                            {
                                foundClientField = true;
                                break;
                            }
                        }
                        if (!foundClientField)
                        {
                            FIELD.process(serverField, false);
                            cFields.add(clientFieldIdx, serverField);
                            if (DEBUG)
                                System.out.printf("1. Server List: %s\n1. Client List: %s\nIdx: %d %d\n", sFieldsStr, cFieldsStr, serverFieldIdx, clientFieldIdx);
                        }
                    }
                    else
                    {
                        FIELD.process(clientField, true);
                        sFields.add(serverFieldIdx, clientField);
                        if (DEBUG)
                            System.out.printf("2. Server List: %s\n2. Client List: %s\nIdx: %d %d\n", sFieldsStr, cFieldsStr, serverFieldIdx, clientFieldIdx);
                    }
                }
            }
            else
            {
                FIELD.process(clientField, true);
                sFields.add(serverFieldIdx, clientField);
                if (DEBUG)
                    System.out.printf("3. Server List: %s\n3. Client List: %s\nIdx: %d %d\n", sFieldsStr, cFieldsStr, serverFieldIdx, clientFieldIdx);
            }
            serverFieldIdx++;
        }
        if (DEBUG)
            System.out.printf("A. Server List: %s\nA. Client List: %s\n", sFieldsStr, cFieldsStr);
        if (sFields.size() != cFields.size())
        {
            for (int x = cFields.size(); x < sFields.size(); x++)
            {
                FieldNode sF = sFields.get(x);
                FIELD.process(sF, true);
                cFields.add(x++, sF);
            }
        }
        if (DEBUG)
            System.out.printf("E. Server List: %s\nE. Client List: %s\n", sFieldsStr, cFieldsStr);
    }

    private void processMethods(ClassNode cClass, ClassNode sClass)
    {
        List<MethodNode> cMethods = cClass.methods;
        List<MethodNode> sMethods = sClass.methods;
        LinkedHashSet<MethodWrapper> allMethods = new LinkedHashSet<>();

        int cPos = 0;
        int sPos = 0;
        int cLen = cMethods.size();
        int sLen = sMethods.size();
        String clientName = "";
        String lastName = clientName;
        String serverName = "";
        while (cPos < cLen || sPos < sLen)
        {
            do
            {
                if (sPos >= sLen)
                {
                    break;
                }
                MethodNode sM = sMethods.get(sPos);
                serverName = sM.name;
                if (!serverName.equals(lastName) && cPos != cLen)
                {
                    if (DEBUG)
                    {
                        System.out.printf("Server -skip : %s %s %d (%s %d) %d [%s]\n", sClass.name, clientName, cLen - cPos, serverName, sLen - sPos, allMethods.size(), lastName);
                    }
                    break;
                }
                MethodWrapper mw = new MethodWrapper(sM);
                mw.server = true;
                allMethods.add(mw);
                if (DEBUG)
                {
                    System.out.printf("Server *add* : %s %s %d (%s %d) %d [%s]\n", sClass.name, clientName, cLen - cPos, serverName, sLen - sPos, allMethods.size(), lastName);
                }
                sPos++;
            } while (sPos < sLen);
            do
            {
                if (cPos >= cLen)
                {
                    break;
                }
                MethodNode cM = cMethods.get(cPos);
                lastName = clientName;
                clientName = cM.name;
                if (!clientName.equals(lastName) && sPos != sLen)
                {
                    if (DEBUG)
                    {
                        System.out.printf("Client -skip : %s %s %d (%s %d) %d [%s]\n", cClass.name, clientName, cLen - cPos, serverName, sLen - sPos, allMethods.size(), lastName);
                    }
                    break;
                }
                MethodWrapper mw = new MethodWrapper(cM);
                mw.client = true;
                allMethods.add(mw);
                if (DEBUG)
                {
                    System.out.printf("Client *add* : %s %s %d (%s %d) %d [%s]\n", cClass.name, clientName, cLen - cPos, serverName, sLen - sPos, allMethods.size(), lastName);
                }
                cPos++;
            } while (cPos < cLen);
        }

        cMethods.clear();
        sMethods.clear();

        for (MethodWrapper mw : allMethods)
        {
            if (DEBUG)
            {
                System.out.println(mw);
            }
            cMethods.add(mw.node);
            sMethods.add(mw.node);
            if (mw.server && mw.client)
            {
                // no op
            }
            else
            {
                METHOD.process(mw.node, mw.client);
            }
        }
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

    private byte[] getResourceBytes(String path) throws IOException
    {
        try (InputStream stream = Merger.class.getResourceAsStream("/" + path))
        {
            return readFully(stream);
        }
    }

    private class MethodWrapper
    {
        private MethodNode node;
        public boolean     client;
        public boolean     server;

        public MethodWrapper(MethodNode node)
        {
            this.node = node;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (obj == null || !(obj instanceof MethodWrapper))
            {
                return false;
            }
            MethodWrapper mw = (MethodWrapper) obj;
            boolean eq = Objects.equals(node.name, mw.node.name) && Objects.equals(node.desc, mw.node.desc);
            if (eq)
            {
                mw.client = client | mw.client;
                mw.server = server | mw.server;
                client = client | mw.client;
                server = server | mw.server;
                if (DEBUG)
                {
                    System.out.printf(" eq: %s %s\n", this, mw);
                }
            }
            return eq;
        }

        @Override
        public int hashCode()
        {
        	int ret = 1;
        	ret = 31 * ret + (node.name == null ? 0 : node.name.hashCode());
        	ret = 31 * ret + (node.desc == null ? 0 : node.desc.hashCode());
            return ret;
        }

        @Override
        public String toString()
        {
        	return "MethodWrapper[name=" + node.name + ",desc=" + node.desc + ",server=" + server + ",client=" + client + "]";
        }
    }
}
