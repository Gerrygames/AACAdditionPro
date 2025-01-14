package de.photon.aacadditionpro.modules.sentinel;

import de.photon.aacadditionpro.modules.ModuleLoader;
import de.photon.aacadditionpro.user.User;
import de.photon.aacadditionpro.util.messaging.DebugSender;
import de.photon.aacadditionpro.util.pluginmessage.MessageChannel;
import lombok.val;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

public class VapeSentinel extends SentinelModule implements Listener
{
    private static final MessageChannel VAPE_MESSAGE_CHANNEL = MessageChannel.ofLegacy("LOLIMAHCKER");

    public VapeSentinel()
    {
        super("Vape");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event)
    {
        event.getPlayer().sendMessage("§8 §8 §1 §3 §3 §7 §8 ");
    }

    @Override
    public void onPluginMessageReceived(@NotNull final String channel, final Player player, @NotNull final byte[] message)
    {
        val user = User.getUser(player);
        if (User.isUserInvalid(user, this)) return;

        String clientData;
        try {
            clientData = new String(message);
        } catch (Exception e) {
            clientData = "";
        }

        DebugSender.getInstance().sendDebug("Player " + player.getName() + " joined with Vape | Data: " + clientData);
        detection(player);
    }

    @Override
    protected ModuleLoader createModuleLoader()
    {
        return ModuleLoader.builder(this)
                           .addIncomingMessageChannels(VAPE_MESSAGE_CHANNEL)
                           .build();
    }
}
