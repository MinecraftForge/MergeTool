/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
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
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Merger {
    private static final boolean DEBUG = false;

    private final File client;
    private final File server;
    private final File merged;
    private AnnotationVersion annotation = null;
    private boolean annotationInject = true;
    private FieldName FIELD = new FieldName();
    private MethodDesc METHOD = new MethodDesc();
    private HashSet<String> whitelist = new HashSet<>();
    private Predicate<String> filter = name -> this.whitelist.isEmpty() || this.whitelist.contains(name);
    private boolean copyData = false;
    private boolean keepMeta = false;
    private boolean bundledServerJar = false;

    public Merger(File client, File server, File merged) {
        this.client = client;
        this.server = server;
        this.merged = merged;
    }

    public Merger annotate(AnnotationVersion ano, boolean inject) {
        this.annotation = ano;
        this.annotationInject = inject;
        return this;
    }

    public Merger whitelist(String file) {
        this.whitelist.add(file);
        return this;
    }

    /*
     * The predicate is called with the class name in binary format {essentially the path to the class file, minus the .class suffix)
     * Anything that does not return true from this predicate will be filtered out.
     */
    public Merger filter(Predicate<String> filter) {
        this.filter = filter;
        return this;
    }

    public Merger keepData() {
        this.copyData = true;
        return this;
    }

    public Merger skipData() {
        this.copyData = false;
        return this;
    }

    public Merger keepMeta() {
        this.keepMeta = true;
        return this;
    }

    public Merger skipMeta() {
        this.keepMeta = false;
        return this;
    }

    public Merger bundledServerJar() {
        this.bundledServerJar = true;
        return this;
    }

    public void process() throws IOException {
        try (ZipOutputStream outJar = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(this.merged)))) {
            Set<String> added = new HashSet<>();
            Map<String, byte[]> cClasses = getClassEntries(this.client, outJar, added);
            Map<String, byte[]> sClasses;
            if (this.bundledServerJar)
                sClasses = getBundledClassEntries(this.server, outJar, null);
            else
                sClasses = getClassEntries(this.server, outJar, null); //Skip data from the server, as it contains libraries.

            for (Entry<String, byte[]> entry : cClasses.entrySet()) {
                String name = entry.getKey();

                if (!this.filter.test(name))
                    continue;

                byte[] cData = entry.getValue();
                byte[] sData = sClasses.get(name);

                if (sData == null) {
                    if (DEBUG)
                        System.out.println("Copy class c->s : " + name);
                    copyClass(name, cData, outJar, true);
                } else {
                    if (DEBUG)
                        System.out.println("Processing class: " + name);

                    sClasses.remove(name);

                    byte[] data = processClass(cData, sData);

                    outJar.putNextEntry(getNewEntry(name + ".class"));
                    outJar.write(data);
                }
            }

            for (Entry<String, byte[]> entry : sClasses.entrySet()) {
                String name = entry.getKey();
                if (!this.filter.test(name))
                    continue;

                if (DEBUG)
                    System.out.println("Copy class s->c : " + name);
                copyClass(name, entry.getValue(), outJar, false);
            }

            if (this.annotation != null && this.annotationInject) {
                for (String cls : this.annotation.getClasses()) {
                    byte[] data = getResourceBytes(cls);

                    outJar.putNextEntry(getNewEntry(cls + ".class"));
                    outJar.write(data);
                }
            }

        }
    }

    private ZipEntry getNewEntry(String name) {
        ZipEntry ret = new ZipEntry(name);
        ret.setTime(0x92D6688800L); //Stabilize output as java will use current time if we don't set this, we can't use 0 as older java versions output different jars for values less then 1980
        return ret;
    }

    private void copyClass(String name, byte[] entry, ZipOutputStream outJar, boolean isClientOnly) throws IOException {
        ClassReader reader = new ClassReader(entry);
        ClassNode classNode = new ClassNode();

        reader.accept(classNode, 0);

        if (this.annotation != null)
            this.annotation.add(classNode, isClientOnly);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);

        byte[] data = writer.toByteArray();

        outJar.putNextEntry(getNewEntry(name + ".class"));
        outJar.write(data);
    }

    private Map<String, byte[]> getClassEntries(File inFile, ZipOutputStream output, Set<String> added) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(inFile))) {
            return getClassEntries(zin, output, added);
        }
    }

    private Map<String, byte[]> getClassEntries(ZipInputStream input, ZipOutputStream output, Set<String> added) throws IOException {
        Map<String, byte[]> ret = new Hashtable<>();
        for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
            String entryName = entry.getName();
            if (!entry.isDirectory() && entryName.endsWith(".class") && !entryName.startsWith(".")) {
                entryName = entryName.substring(0, entryName.length() - 6);
                byte[] data = readFully(input);
                ret.put(entryName, data);
            } else if (this.copyData && added != null && !added.contains(entryName)) {
                if (!this.keepMeta && entryName.startsWith("META-INF"))
                    continue;

                if (entry.isDirectory()) {
                    //Skip directories, they arnt required.
                    //output.putNextEntry(getNewEntry(entryName)); //New entry to reset time
                    added.add(entryName);
                } else {
                    output.putNextEntry(getNewEntry(entryName));
                    copy(input, output);
                    added.add(entryName);
                }
            }
        }
        return ret;
    }

    private static final Attributes.Name BUNDLER_FORMAT = new Attributes.Name("Bundler-Format");
    private static final String VERSIONS_LIST = "META-INF/versions.list";
    private Map<String, byte[]> getBundledClassEntries(File inFile, ZipOutputStream output, Set<String> added) throws IOException {
        try (ZipFile zin = new ZipFile(inFile)) {
            ZipEntry mfEntry = zin.getEntry(JarFile.MANIFEST_NAME);
            if (mfEntry == null)
                throw new IOException("Invalid bundled server jar, Missing " + JarFile.MANIFEST_NAME);

            Manifest mf = new Manifest(zin.getInputStream(mfEntry));
            String format = mf.getMainAttributes().getValue(BUNDLER_FORMAT);
            if (format == null)
                throw new IOException("Invalid bundled server jar, Missing " + BUNDLER_FORMAT + " manifest entry");

            if (!"1.0".equals(format))
                throw new IOException("Unsupported Bundler-Format: " + format);

            ZipEntry verEntry = zin.getEntry(VERSIONS_LIST);
            if (verEntry == null)
                throw new IllegalStateException("Bundled Jar missing " + VERSIONS_LIST);

            List<BundleEntry> verList = readList(zin.getInputStream(verEntry));
            if (verList.size() != 1)
                throw new IllegalStateException("Invalid bundler " + VERSIONS_LIST + " file, " + verList.size() + " entries, expected 1");

            String serverJarName = "META-INF/versions/" + verList.get(0).path;
            ZipEntry serverJarEntry = zin.getEntry(serverJarName);
            if (serverJarEntry == null)
                throw new IOException("Invalid bundled server jar, Missing jar entry " + serverJarName);

            try (ZipInputStream server = new ZipInputStream(zin.getInputStream(serverJarEntry))) {
                return getClassEntries(server, output, added);
            }
        }
    }

    private static List<BundleEntry> readList(InputStream in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            List<BundleEntry> ret = new ArrayList<>();
            while (reader.ready()) {
                String line = reader.readLine();
                String[] pts = line.split("\t");
                if (pts.length != 3)
                    throw new IOException("Invalid bunder list line: " + line);
                ret.add(new BundleEntry(pts[0], pts[1], pts[2]));
            }
            return ret;
        }
    }

    private byte[] readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        copy(stream, buf);
        return buf.toByteArray();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] data = new byte[4096];
        int len;
        do {
            len = input.read(data);
            if (len > 0)
                output.write(data, 0, len);
        } while (len != -1);
    }

    private byte[] processClass(byte[] cIn, byte[] sIn) {
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

    private boolean innerMatches(InnerClassNode o, InnerClassNode o2) {
        return equals(o.innerName, o2.innerName) &&
               equals(o.name,      o2.name) &&
               equals(o.outerName, o2.outerName);
    }

    private boolean equals(Object o1, Object o2) {
        return o1 == null ? o2 == null : o2 == null ? false : o1.equals(o2);
    }

    private void processInners(ClassNode cClass, ClassNode sClass) {
        List<InnerClassNode> cIners = cClass.innerClasses;
        List<InnerClassNode> sIners = sClass.innerClasses;

        for (InnerClassNode n : cIners) {
            if (!sIners.stream().anyMatch(e -> innerMatches(e, n)))
                sIners.add(n);
        }

        for (InnerClassNode n : sIners) {
            if (!cIners.stream().anyMatch(e -> innerMatches(e, n)))
                cIners.add(n);
        }
    }

    private void processInterfaces(ClassNode cClass, ClassNode sClass) {
        List<String> cIntfs = cClass.interfaces;
        List<String> sIntfs = sClass.interfaces;
        List<String> cOnly = new ArrayList<>();
        List<String> sOnly = new ArrayList<>();

        for (String n : cIntfs) {
            if (!sIntfs.contains(n)) {
                sIntfs.add(n);
                cOnly.add(n);
            }
        }

        for (String n : sIntfs) {
            if (!cIntfs.contains(n)) {
                cIntfs.add(n);
                sOnly.add(n);
            }
        }

        Collections.sort(cIntfs); //Sort things, we're in obf territory but should stabilize things.
        Collections.sort(sIntfs);

        if (this.annotation != null && !cOnly.isEmpty() || !sOnly.isEmpty()) {
            this.annotation.add(cClass, cOnly, sOnly);
            this.annotation.add(sClass, cOnly, sOnly);
        }
    }

    private ClassNode getClassNode(byte[] data) {
        ClassReader reader = new ClassReader(data);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        return classNode;
    }

    private static <T, U> BiPredicate<? super U, ? super U> curry(Function<? super U, ? extends T> function, BiPredicate<? super T, ? super T> primary) {
        return (a,b) -> primary.test(function.apply(a), function.apply(b));
    }

    private void processFields(ClassNode cClass, ClassNode sClass) {
        merge(cClass.name, sClass.name, cClass.fields, sClass.fields, curry(FIELD, Objects::equals), FIELD, FIELD, FIELD);
    }

    private void processMethods(ClassNode cClass, ClassNode sClass) {
        merge(cClass.name, sClass.name, cClass.methods, sClass.methods, curry(METHOD, Objects::equals), METHOD, METHOD, METHOD);
    }

    private interface MemberAnnotator<T> {
        T process(T member, boolean isClient);
    }

    private class FieldName implements Function<FieldNode, String>, MemberAnnotator<FieldNode>, Comparator<FieldNode> {
        public String apply(FieldNode in) {
            return in == null ? "null" : in.name;
        }

        public FieldNode process(FieldNode field, boolean isClient) {
            if (Merger.this.annotation != null)
                Merger.this.annotation.add(field, isClient);
            return field;
        }

        @Override
        public int compare(FieldNode a, FieldNode b) {
            if (a == b) return 0;
            if (a == null) return 1;
            if (b == null) return -1;
            return a.name.compareTo(b.name);
        }
    }

    private class MethodDesc implements Function<MethodNode, String>, MemberAnnotator<MethodNode>, Comparator<MethodNode> {
        public String apply(MethodNode node) {
            return node == null ? "null" : node.name + node.desc;
        }

        public MethodNode process(MethodNode node, boolean isClient) {
            if (Merger.this.annotation != null)
                Merger.this.annotation.add(node, isClient);
            return node;
        }

        private int findLine(MethodNode member) {
            for (int x = 0; x < member.instructions.size(); x++) {
                AbstractInsnNode insn = member.instructions.get(x);
                if (insn instanceof LineNumberNode)
                    return ((LineNumberNode)insn).line;
            }
            return Integer.MAX_VALUE;
        }

        @Override
        public int compare(MethodNode a, MethodNode b) {
            if (a == b) return 0;
            if (a == null) return 1;
            if (b == null) return -1;
            return findLine(a) - findLine(b);
        }
    }

    private <T> void merge(String cName, String sName, List<T> client, List<T> server, BiPredicate<? super T, ? super T> eq,
            MemberAnnotator<T> annotator, Function<T, String> toString, Comparator<T> compare) {
        // adding null to the end to not handle the index overflow in a special way
        client.add(null);
        server.add(null);
        List<T> common = new ArrayList<>();
        for(T ct : client) {
            for (T st : server) {
                if (eq.test(ct, st)) {
                    common.add(ct);
                    break;
                }
            }
        }

        int i = 0, mi = 0;
        for(; i < client.size(); i++) {
            T ct = client.get(i);
            T st = server.get(i);
            T mt = common.get(mi);

            if (eq.test(ct, st)) {
                mi++;
                if (!eq.test(ct, mt))
                    throw new IllegalStateException("merged list is in bad state: " + toString.apply(ct) + " " + toString.apply(st) + " " + toString.apply(mt));
                if (DEBUG)
                    System.out.printf("%d/%d %d/%d Both Shared  : %s %s\n", i, client.size(), mi, common.size(), sName, toString.apply(st));

            } else if(eq.test(st, mt)) {
                server.add(i, annotator.process(ct, true));
                if (DEBUG)
                    System.out.printf("%d/%d %d/%d Server *add* : %s %s\n", i, client.size(), mi, common.size(), sName, toString.apply(ct));
            } else if (eq.test(ct, mt)) {
                client.add(i, annotator.process(st, false));
                if (DEBUG)
                    System.out.printf("%d/%d %d/%d Client *add* : %s %s\n", i, client.size(), mi, common.size(), cName, toString.apply(st));
            } else { // Both server and client add a new method before we get to the next common method... Lets try and prioritize one.
                int diff = compare.compare(ct,  st);
                if  (diff > 0) {
                    client.add(i, annotator.process(st, false));
                    if (DEBUG)
                        System.out.printf("%d/%d %d/%d Client *add* : %s %s\n", i, client.size(), mi, common.size(), cName, toString.apply(st));
                } else { /* if (diff < 0) */ //Technically this should be <0 and we special case when they can't agree who goes first.. but for now just push the client's first.
                    server.add(i, annotator.process(ct, true));
                    if (DEBUG)
                        System.out.printf("%d/%d %d/%d Server *add* : %s %s\n", i, client.size(), mi, common.size(), sName, toString.apply(ct));
                }
            }
        }

        if (i < server.size() || mi < common.size() || (client.size() != server.size()))
            throw new IllegalStateException("merged list is in bad state: " + i + " " + mi);

        // removing the null
        client.remove(client.size() - 1);
        server.remove(server.size() - 1);
    }

    private byte[] getResourceBytes(String path) throws IOException {
        // If we're in the built jar, use the relocated classes {prevents them being
        InputStream stream = Merger.class.getResourceAsStream("/markers/" + path + ".marker");
        // If not, then try and get them from the classpath
        if (stream == null)
            stream = Merger.class.getResourceAsStream("/" + path + ".class");
        if (stream == null)
            throw new IllegalStateException("Could not find marker files: " + path);

        try {
            return readFully(stream);
        } finally {
            stream.close();
        }
    }


    @SuppressWarnings("unused")
    private static class BundleEntry {
        public final String hash;
        public final String artifact;
        public final String path;

        private BundleEntry(String hash, String artifact, String path) {
            this.hash = hash;
            this.artifact = artifact;
            this.path = path;
        }
    }
}
