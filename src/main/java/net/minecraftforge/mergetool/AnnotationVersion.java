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

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public enum AnnotationVersion
{
    CPW(cpw.mods.fml.relauncher.SideOnly.class, cpw.mods.fml.relauncher.Side.class, "CLIENT", "SERVER"),
    NMF(net.minecraftforge.fml.relauncher.SideOnly.class, net.minecraftforge.fml.relauncher.Side.class, "CLIENT", "SERVER"),
    API(OnlyIn.class, Dist.class, "CLIENT", "DEDICATED_SERVER");

    public final Class<?> holder;
    public final Class<?> value;
    public final String client;
    public final String server;

    private AnnotationVersion(Class<?> holder, Class<?> value, String client, String server)
    {
        this.holder = holder;
        this.value = value;
        this.client = client;
        this.server = server;
    }

    public static AnnotationVersion fromVersion(String v)
    {
        if (v == null)
            return AnnotationVersion.NMF;

        try
        {
            if (v.length() == 6 && v.charAt(2) == 'w')
            {
                int year = Integer.parseInt(v.substring(0, 2));
                int week = Integer.parseInt(v.substring(3, 5));
                int date = (year * 100) + week;

                if (date < 1402) //14w02a was first 1.8 snapshot
                    return AnnotationVersion.CPW;

                if (date < 1743) //17w43a was first 1.13 snapshot
                    return AnnotationVersion.NMF;

                return AnnotationVersion.API;
            }
            v = v.split("-")[0]; //Strip pre's
            String[] pts = v.split("\\.");
            //int major = Integer.parseInt(pts[0]);
            int minor = Integer.parseInt(pts[1]);
            //int revision = pts.length > 2 ? Integer.parseInt(pts[2]) : 0;

            return minor < 8  ? AnnotationVersion.CPW :
                   minor < 13 ? AnnotationVersion.NMF :
                                AnnotationVersion.API;
        }
        catch (NumberFormatException e)
        {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public void add(ClassVisitor cls, boolean isClientOnly)
    {
        add(cls.visitAnnotation(Type.getDescriptor(this.holder), true), isClientOnly);
    }
    public void add(FieldVisitor fld, boolean isClientOnly)
    {
        add(fld.visitAnnotation(Type.getDescriptor(this.holder), true), isClientOnly);
    }
    public void add(MethodVisitor mtd, boolean isClientOnly)
    {
        add(mtd.visitAnnotation(Type.getDescriptor(this.holder), true), isClientOnly);
    }
    private void add(AnnotationVisitor ann, boolean isClientOnly)
    {
        ann.visitEnum("value", Type.getDescriptor(this.value), (isClientOnly ? this.client : this.server));
    }
}
