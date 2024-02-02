/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mergetool;

import java.util.List;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import net.minecraftforge.srgutils.MinecraftVersion;

public enum AnnotationVersion {
    CPW("cpw/mods/fml/relauncher",           "SideOnly", "Side", null,      null,         "CLIENT", "SERVER"),
    NMF("net/minecraftforge/fml/relauncher", "SideOnly", "Side", null,      null,         "CLIENT", "SERVER"),
    API("net/minecraftforge/api/distmarker", "OnlyIn",   "Dist", "OnlyIns", "_interface", "CLIENT", "DEDICATED_SERVER");

    private final String holder;
    private final String value;
    private final String repeatable;
    private final String interface_key;
    private final String client;
    private final String server;

    private static final MinecraftVersion MC_8 = MinecraftVersion.from("14w02a");
    private static final MinecraftVersion MC_13 = MinecraftVersion.from("17w43a");

    private AnnotationVersion(String pkg, String holder, String value, String repeatable, String interface_key, String client, String server) {
        this.holder = 'L' + pkg + '/' + holder + ';';
        this.value  = 'L' + pkg + '/' + value  + ';';
        this.repeatable = repeatable == null ? null : 'L' + pkg + '/' + repeatable + ';';
        this.interface_key = interface_key;
        this.client = client;
        this.server = server;
    }

    public static AnnotationVersion fromVersion(String v) {
        if (v == null)
            return AnnotationVersion.NMF;

        try {
            MinecraftVersion target = MinecraftVersion.from(v);
            if (target.compareTo(MC_8) < 0)
                return AnnotationVersion.CPW;
            if (target.compareTo(MC_13) < 0)
                return AnnotationVersion.NMF;
            return AnnotationVersion.API;
        } catch (NumberFormatException e) {
            return sneak(e);
        }
    }

    public String[] getClasses() {
        if (this.repeatable == null)
            return new String[] { Type.getType(holder).getInternalName(), Type.getType(value).getInternalName() };
        else
            return new String[] { Type.getType(holder).getInternalName(), Type.getType(value).getInternalName(), Type.getType(repeatable).getInternalName()};
    }

    public void add(ClassVisitor cls, List<String> clientOnly, List<String> serverOnly) {
        if (this.repeatable == null || this.interface_key == null)
            return;

        if (clientOnly.size() + serverOnly.size() == 1) {
            if (clientOnly.size() == 1)
                add(cls.visitAnnotation(this.holder, true), true).visit(interface_key, Type.getObjectType(clientOnly.get(0)));
            else
                add(cls.visitAnnotation(this.holder, true), false).visit(interface_key, Type.getObjectType(serverOnly.get(0)));
        } else {
            AnnotationVisitor rep = cls.visitAnnotation(this.holder, true).visitArray("value");
            clientOnly.forEach(intf -> add(rep.visitAnnotation(null, this.repeatable), true).visit(interface_key, Type.getObjectType(intf)));
            serverOnly.forEach(intf -> add(rep.visitAnnotation(null, this.repeatable), false).visit(interface_key, Type.getObjectType(intf)));
        }
    }

    public void add(ClassVisitor cls, boolean isClientOnly) {
        add(cls.visitAnnotation(this.holder, true), isClientOnly);
    }

    public void add(FieldVisitor fld, boolean isClientOnly) {
        add(fld.visitAnnotation(this.holder, true), isClientOnly);
    }

    public void add(MethodVisitor mtd, boolean isClientOnly) {
        add(mtd.visitAnnotation(this.holder, true), isClientOnly);
    }

    private AnnotationVisitor add(AnnotationVisitor ann, boolean isClientOnly) {
        ann.visitEnum("value", this.value, (isClientOnly ? this.client : this.server));
        return ann;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, R> R sneak(Throwable e) throws E {
        throw (E)e;
    }
}
