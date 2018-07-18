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
import java.util.Locale;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.ValueConverter;

public class ConsoleMerger
{
    public static void main(String[] args)
    {
        OptionParser parser = new OptionParser();
        OptionSpec<File> client = parser.accepts("client").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> server = parser.accepts("server").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> merged = parser.accepts("output").withRequiredArg().ofType(File.class).required();
        OptionSpec<AnnotationVersion> anno = parser.accepts("ann").withOptionalArg().ofType(AnnotationVersion.class).withValuesConvertedBy(
            new ValueConverter<AnnotationVersion>()
            {
                @Override
                public AnnotationVersion convert(String value)
                {
                    return AnnotationVersion.valueOf(value.toUpperCase(Locale.ENGLISH));
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
            }).defaultsTo(AnnotationVersion.API);


        try
        {
            OptionSet options = parser.parse(args);

            File client_jar = options.valueOf(client);
            File server_jar = options.valueOf(server);
            File merged_jar = options.valueOf(merged);

            Merger merge = new Merger(client_jar, server_jar, merged_jar);

            if (merged_jar.exists() && !merged_jar.delete())
            {
                System.out.println("Could not delete output file: " + merged_jar);
            }

            if (options.has(anno))
            {
                merge.annotate(options.valueOf(anno));
            }

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
            System.out.println("Usage: ConsoleMerger --client <ClientJar> --server <ServerJar> --output <MergedJar> [--ann CPW|NMF|API]");
            e.printStackTrace();
        }
    }
}
