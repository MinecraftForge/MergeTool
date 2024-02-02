/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mergetool;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.ValueConverter;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IMappingFile.IClass;

public class ConsoleMerger {
    private static enum Tasks { MERGE, STRIP };
    private static final ValueConverter<AnnotationVersion> AnnotationReader = new ValueConverter<AnnotationVersion>() {
        @Override
        public AnnotationVersion convert(String value) {
            try {
                return AnnotationVersion.valueOf(value.toUpperCase(Locale.ENGLISH));
            } catch (IllegalArgumentException e) { //Invalid argument, lets try by version, wish there was a way to know before hand.
                return AnnotationVersion.fromVersion(value);
            }
        }

        @Override
        public Class<? extends AnnotationVersion> valueType() {
            return AnnotationVersion.class;
        }

        @Override
        public String valuePattern() {
            return null;
        }
    };

    public static void main(String[] args) {
        List<String> extra = new ArrayList<>();
        Tasks task = null;

        for (int x = 0; x < args.length; x++) {
            if ("--strip".equals(args[x])) {
                if (task != null)
                    throw new IllegalArgumentException("Only one task supported at a time: " + task);
                task = Tasks.STRIP;
            } else if ("--merge".equals(args[x])) {
                if (task != null)
                    throw new IllegalArgumentException("Only one task supported at a time: " + task);
                task = Tasks.MERGE;
            }
            else
                extra.add(args[x]);
        }

        if (task == Tasks.MERGE || task == null)
            merge(extra.toArray(new String[extra.size()]));
        else if (task == Tasks.STRIP)
            strip(extra.toArray(new String[extra.size()]));
    }

    private static void merge(String[] args) {
        OptionParser parser = new OptionParser();
        OptionSpec<File> client = parser.accepts("client").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> server = parser.accepts("server").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> merged = parser.accepts("output").withRequiredArg().ofType(File.class).required();
        OptionSpec<Boolean> inject = parser.accepts("inject").withOptionalArg().ofType(Boolean.class).defaultsTo(true);
        OptionSpec<Void> data = parser.accepts("keep-data");
        OptionSpec<Void> meta = parser.accepts("keep-meta");
        OptionSpec<AnnotationVersion> anno = parser.accepts("ann").withOptionalArg().ofType(AnnotationVersion.class).withValuesConvertedBy(AnnotationReader).defaultsTo(AnnotationVersion.API);
        OptionSpec<String> whitelist    = parser.accepts("whitelist").withRequiredArg().ofType(String.class);
        OptionSpec<String> whitelistPkg = parser.accepts("whitelist-pkg").withRequiredArg().ofType(String.class);
        OptionSpec<File>   whitelistMap = parser.accepts("whitelist-map").withRequiredArg().ofType(File.class);
        OptionSpec<String> blacklist    = parser.accepts("blacklist").withRequiredArg().ofType(String.class);
        OptionSpec<String> blacklistPkg = parser.accepts("blacklist-pkg").withRequiredArg().ofType(String.class);
        OptionSpec<File>   blacklistMap = parser.accepts("blacklist-map").withRequiredArg().ofType(File.class);
        OptionSpec<Void> bundled = parser.accepts("bundled");

        try {
            OptionSet options = parser.parse(args);

            File client_jar = options.valueOf(client);
            File server_jar = options.valueOf(server);
            File merged_jar = options.valueOf(merged);

            Merger merge = new Merger(client_jar, server_jar, merged_jar);

            if (merged_jar.exists() && !merged_jar.delete())
                System.out.println("Could not delete output file: " + merged_jar);

            if (options.has(anno))
                merge.annotate(options.valueOf(anno), !options.has(inject) || options.valueOf(inject));

            if (options.has(data))
                merge.keepData();

            if (options.has(meta))
                merge.keepMeta();

            if (options.has(bundled))
                merge.bundledServerJar();

            Predicate<String> filter = null;
            if (options.has(whitelist) || options.has(whitelistMap)) {
                Set<String> classes = loadList(options.valuesOf(whitelist), options.valuesOf(whitelistMap));
                if (!classes.isEmpty())
                    filter = classes::contains;
            }

            if (options.has(whitelistPkg)) {
                Set<String> pkgs = loadPackages(options.valuesOf(whitelistPkg));
                Predicate<String> prefixes = name -> {
                    int idx = name.lastIndexOf('/');
                    return idx == -1 ? pkgs.contains("/") : pkgs.contains(name.substring(0, idx));
                };

                if (filter == null)
                    filter = prefixes;
                else
                    filter = filter.or(prefixes);
            }

            if (filter == null)
                filter = name -> true;

            if (options.has(blacklist) || options.has(blacklistMap)) {
                Set<String> classes = loadList(options.valuesOf(blacklist), options.valuesOf(blacklistMap));
                if (!classes.isEmpty())
                    filter.and(name -> !classes.contains(name));
            }

            if (options.has(blacklistPkg)) {
                Set<String> pkgs = loadPackages(options.valuesOf(blacklistPkg));
                filter.and(name -> {
                    int idx = name.lastIndexOf('/');
                    return idx == -1 ? !pkgs.contains("/") : !pkgs.contains(name.substring(0, idx));
                });
            }

            merge.filter(filter);

            merge.process();
        } catch (OptionException e) {
            System.out.println("Usage: ConsoleMerger --merge --client <ClientJar> --server <ServerJar> --output <MergedJar> [--ann CPW|NMF|API] [--keep-data] [--keep-meta]");
            e.printStackTrace();
            sneak(e);
        } catch (IOException e) {
            e.printStackTrace();
            sneak(e);
        }
    }

    private static void strip(String[] args) {
        OptionParser parser = new OptionParser();
        OptionSpec<File> input = parser.accepts("input").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> output = parser.accepts("output").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> data = parser.accepts("data").withRequiredArg().ofType(File.class).required();

        try {
            OptionSet options = parser.parse(args);

            File input_jar = options.valueOf(input).getAbsoluteFile();
            File output_jar = options.valueOf(output).getAbsoluteFile();

            try {
                System.out.println("Input:  " + input_jar);
                System.out.println("Output: " + output_jar);
                Stripper strip = new Stripper();

                for (File dataF : options.valuesOf(data)) {
                    System.out.println("Data:   " + dataF.getAbsoluteFile());
                    strip.loadData(dataF);
                }

                if (output_jar.exists() && !output_jar.delete())
                    System.out.println("Could not delete output file: " + output_jar);

                strip.process(input_jar, output_jar);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (OptionException e) {
            System.out.println("Usage: ConsoleMerger --strip --input <InputJar> --output <OutputJar> --data <DataText>...");
            e.printStackTrace();
        }
    }


    private static Set<String> loadList(List<String> strings, List<File> files) throws IOException {
        Set<String> classes = new HashSet<>();
        classes.addAll(strings);

        for (File value : files) {
            IMappingFile map = IMappingFile.load(value);
            for (IClass cls : map.getClasses())
                classes.add(cls.getOriginal());
        }

        return classes;
    }

    private static Set<String> loadPackages(List<String> strings) {
        Set<String> pkgs = new HashSet<>();
        for (String value : strings) {
            if (!value.endsWith("/"))
                value += '/';
            pkgs.add(value);
        }
        return pkgs;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, R> R sneak(Throwable e) throws E {
        throw (E)e;
    }
}
