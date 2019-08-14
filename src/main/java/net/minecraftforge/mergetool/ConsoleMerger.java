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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.ValueConverter;

public class ConsoleMerger
{
    private static enum Tasks { MERGE, STRIP };
    private static final ValueConverter<AnnotationVersion> AnnotationReader = new ValueConverter<AnnotationVersion>()
    {
        @Override
        public AnnotationVersion convert(String value)
        {
            try
            {
                return AnnotationVersion.valueOf(value.toUpperCase(Locale.ENGLISH));
            }
            catch (IllegalArgumentException e) //Invalid argument, lets try by version, wish there was a way to know before hand.
            {
                return AnnotationVersion.fromVersion(value);
            }
        }

        @Override
        public Class<? extends AnnotationVersion> valueType()
        {
            return AnnotationVersion.class;
        }

        @Override
        public String valuePattern()
        {
            return null;
        }
    };

    public static void main(String[] args)
    {
        List<String> extra = new ArrayList<>();
        Tasks task = null;

        for (int x = 0; x < args.length; x++)
        {
            if ("--strip".equals(args[x]))
            {
                if (task != null)
                    throw new IllegalArgumentException("Only one task supported at a time: " + task);
                task = Tasks.STRIP;
            }
            else if ("--merge".equals(args[x]))
            {
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

    private static void merge(String[] args)
    {
        OptionParser parser = new OptionParser();
        OptionSpec<File> client = parser.accepts("client").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> server = parser.accepts("server").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> merged = parser.accepts("output").withRequiredArg().ofType(File.class).required();
        OptionSpec<Boolean> inject = parser.accepts("inject").withOptionalArg().ofType(Boolean.class).defaultsTo(true);
        OptionSpec<AnnotationVersion> anno = parser.accepts("ann").withOptionalArg().ofType(AnnotationVersion.class).withValuesConvertedBy(AnnotationReader).defaultsTo(AnnotationVersion.API);

        try
        {
            OptionSet options = parser.parse(args);

            File client_jar = options.valueOf(client);
            File server_jar = options.valueOf(server);
            File merged_jar = options.valueOf(merged);

            Merger merge = new Merger(client_jar, server_jar, merged_jar);

            if (merged_jar.exists() && !merged_jar.delete())
                System.out.println("Could not delete output file: " + merged_jar);

            if (options.has(anno))
                merge.annotate(options.valueOf(anno), !options.has(inject) || options.valueOf(inject));

            try
            {
                merge.process();
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
        catch (OptionException e)
        {
            System.out.println("Usage: ConsoleMerger --merge --client <ClientJar> --server <ServerJar> --output <MergedJar> [--ann CPW|NMF|API]");
            e.printStackTrace();
        }
    }

    private static void strip(String[] args)
    {
        OptionParser parser = new OptionParser();
        OptionSpec<File> input = parser.accepts("input").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> output = parser.accepts("output").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> data = parser.accepts("data").withRequiredArg().ofType(File.class).required();

        try
        {
            OptionSet options = parser.parse(args);

            File input_jar = options.valueOf(input).getAbsoluteFile();
            File output_jar = options.valueOf(output).getAbsoluteFile();

            try
            {
                System.out.println("Input:  " + input_jar);
                System.out.println("Output: " + output_jar);
                Stripper strip = new Stripper();

                for (File dataF : options.valuesOf(data))
                {
                    System.out.println("Data:   " + dataF.getAbsoluteFile());
                    strip.loadData(dataF);
                }

                if (output_jar.exists() && !output_jar.delete())
                    System.out.println("Could not delete output file: " + output_jar);

                strip.process(input_jar, output_jar);
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
        catch (OptionException e)
        {
            System.out.println("Usage: ConsoleMerger --strip --input <InputJar> --output <OutputJar> --data <DataText>...");
            e.printStackTrace();
        }
    }
}
