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

import java.util.List;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.OnlyIns;
import net.minecraftforge.srgutils.MinecraftVersion;

public enum AnnotationVersion
{
    CPW(cpw.mods.fml.relauncher.SideOnly.class, cpw.mods.fml.relauncher.Side.class, "CLIENT", "SERVER"),
    NMF(net.minecraftforge.fml.relauncher.SideOnly.class, net.minecraftforge.fml.relauncher.Side.class, "CLIENT", "SERVER"),
    API(OnlyIn.class, Dist.class, OnlyIns.class, "_interface", "CLIENT", "DEDICATED_SERVER");

    private final String holder;
    private final String value;
    private final String repeatable;
    private final String interface_key;
    private final String client;
    private final String server;

    private static final MinecraftVersion MC_8 = MinecraftVersion.from("14w02a");
    private static final MinecraftVersion MC_13 = MinecraftVersion.from("17w43a");

    private AnnotationVersion(Class<?> holder, Class<?> value, String client, String server)
    {
        this(holder, value, null, null, client, server);
    }

    private AnnotationVersion(Class<?> holder, Class<?> value, Class<?> repeatable, String interface_key, String client, String server)
    {
        this.holder = Type.getDescriptor(holder);
        this.value = Type.getDescriptor(value);
        this.repeatable = repeatable == null ? null : Type.getDescriptor(repeatable);
        this.interface_key = interface_key;
        this.client = client;
        this.server = server;
    }

    public static AnnotationVersion fromVersion(String v)
    {
        if (v == null)
            return AnnotationVersion.NMF;

        try
        {
            MinecraftVersion target = MinecraftVersion.from(v);
            if (target.compareTo(MC_8) < 0)
                return AnnotationVersion.CPW;
            if (target.compareTo(MC_13) < 0)
                return AnnotationVersion.NMF;
            return AnnotationVersion.API;
        }
        catch (NumberFormatException e)
        {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public String[] getClasses()
    {
        if (this.repeatable == null)
            return new String[] { Type.getType(holder).getInternalName(), Type.getType(value).getInternalName() };
        else
            return new String[] { Type.getType(holder).getInternalName(), Type.getType(value).getInternalName(), Type.getType(repeatable).getInternalName()};
    }

    public void add(ClassVisitor cls, List<String> clientOnly, List<String> serverOnly)
    {
        if (this.repeatable == null || this.interface_key == null)
            return;

        if (clientOnly.size() + serverOnly.size() == 1)
        {
            if (clientOnly.size() == 1)
                add(cls.visitAnnotation(this.holder, true), true).visit(interface_key, Type.getObjectType(clientOnly.get(0)));
            else
                add(cls.visitAnnotation(this.holder, true), false).visit(interface_key, Type.getObjectType(serverOnly.get(0)));
        }
        else
        {
            AnnotationVisitor rep = cls.visitAnnotation(this.holder, true).visitArray("value");
            clientOnly.forEach(intf -> add(rep.visitAnnotation(null, this.repeatable), true).visit(interface_key, Type.getObjectType(intf)));
            serverOnly.forEach(intf -> add(rep.visitAnnotation(null, this.repeatable), false).visit(interface_key, Type.getObjectType(intf)));
        }
    }
    public void add(ClassVisitor cls, boolean isClientOnly)
    {
        add(cls.visitAnnotation(this.holder, true), isClientOnly);
    }
    public void add(FieldVisitor fld, boolean isClientOnly)
    {
        add(fld.visitAnnotation(this.holder, true), isClientOnly);
    }
    public void add(MethodVisitor mtd, boolean isClientOnly)
    {
        add(mtd.visitAnnotation(this.holder, true), isClientOnly);
    }
    private AnnotationVisitor add(AnnotationVisitor ann, boolean isClientOnly)
    {
        ann.visitEnum("value", this.value, (isClientOnly ? this.client : this.server));
        return ann;
    }
}
