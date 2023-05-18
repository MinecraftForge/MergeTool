/*
 * Minecraft Forge
 * Copyright (c) 2016.
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

package net.minecraftforge.api.distmarker;

/**
 * A physical distribution of Minecraft. There are two common distributions, and though
 * much code is common between them, there are some specific pieces that are only present
 * in one or the other.
 * <ul>
 *     <li>{@link #CLIENT} is the <em>client</em> distribution, it contains
 *     the game client, and has code to render a viewport into a game world.</li>
 *     <li>{@link #DEDICATED_SERVER} is the <em>dedicated server</em> distribution,
 *     it contains a server, which can simulate the world and communicates via network.</li>
 * </ul>
 * <p>
 * When working with Dist-specific code, it is important to guard invocations such that
 * classes invalid for the current Dist are not loaded.
 * <p>
 * This is done by obeying the following rules:<br>
 * 1. All Dist-specific code must go in a separate class.<br>
 * 2. All accesses to the Dist-specific class must be guarded by a Dist check.
 * <p>
 * Following these rules ensures that a Dist-induced classloading error will never occur.
 * <p>
 * An example of these rules in action is shown below:
 * <p>
 * <code><pre>
 * // Class which accesses code that is only present in Dist.CLIENT
 * public class ClientOnlyThings
 * {
 *     public static boolean isClientSingleplayer()
 *     {
 *         return Minecraft.getInstance().isSingleplayer();
 *     }
 * }
 * 
 * // Class which is loaded on both Dists.
 * public class SharedClass 
 * {
 *     // Returns true if the client is playing singleplayer.
 *     // Returns false if executed on the server (will never crash).
 *     public static boolean isClientSingleplayer()
 *     {
 *         if(currentDist.isClient())
 *         {
 *             return ClientOnlyThings.isClientSingleplayer();
 *         }
 *         return false;
 *     }
 * }
 * </pre></code>
 * 
 * In this example, any code can now call <code>SharedClass.isClientSingleplayer()</code> without guarding.<br>
 * However, only code that is specific to Dist.CLIENT may call <code>ClientOnlyThings.isClientSingleplayer()<code>.
 * 
 * @apiNote How to access the current Dist will depend on the project. When using FML, it is in FMLEnvironment.dist
 */
public enum Dist {

    /**
     * The client distribution. This is the game client players can purchase and play.
     * It contains the graphics and other rendering to present a viewport into the game world.
     */
    CLIENT,
    /**
     * The dedicated server distribution. This is the server only distribution available for
     * download. It simulates the world, and can be communicated with via a network.
     * It contains no visual elements of the game whatsoever.
     */
    DEDICATED_SERVER;

    /**
     * @return If this marks a dedicated server.
     */
    public boolean isDedicatedServer()
    {
        return !isClient();
    }

    /**
     * @return if this marks a client.
     */
    public boolean isClient()
    {
        return this == CLIENT;
    }
}
