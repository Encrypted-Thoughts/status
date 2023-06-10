package de.maxhenkel.status.playerstate;

import de.maxhenkel.status.Status;
import de.maxhenkel.status.events.PlayerEvents;
import de.maxhenkel.status.net.NetManager;
import de.maxhenkel.status.net.PlayerStatePacket;
import de.maxhenkel.status.net.PlayerStatesPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStateManager {

    private final ConcurrentHashMap<UUID, PlayerState> states;

    public PlayerStateManager() {
        states = new ConcurrentHashMap<>();
        PlayerEvents.PLAYER_LOGGED_OUT.register(this::removePlayer);
        PlayerEvents.PLAYER_LOGGED_IN.register(this::notifyPlayer);
        PlayerEvents.PLAYER_SLEEP.register(this::onSleep);

        NetManager.registerServerReceiver(PlayerStatePacket.class, (server, changedplayer, handler, responseSender, packet) -> {
            PlayerState state = packet.getPlayerState();

            var insomniacCount = getNoSleepPlayers(server).size();

            if (states.containsKey(changedplayer.getUUID()) && state.isNoSleep() != states.get(changedplayer.getUUID()).isNoSleep()) {
                for (var player : server.getPlayerList().getPlayers()) {
                    if (state.isNoSleep()) {
                        player.sendSystemMessage(changedplayer.getDisplayName().copy()
                                .append(" doesn't want anyone to sleep. " + (insomniacCount+1) + " total players don't want sleep.")
                                .withStyle(ChatFormatting.RED)
                        );
                    }
                    else {
                        player.sendSystemMessage(changedplayer.getDisplayName().copy()
                                .append(" is ok with people sleeping now.")
                                .withStyle(ChatFormatting.GREEN)
                        );
                        if (insomniacCount-1 <= 0) {
                            player.sendSystemMessage(Component.literal("Players are free to sleep now.")
                                    .withStyle(ChatFormatting.GREEN)
                            );
                        }
                        else {
                            player.sendSystemMessage(Component.literal("There's still " + (insomniacCount-1) + " players requesting no sleep.")
                                    .withStyle(ChatFormatting.RED)
                            );
                        }

                    }
                }
            }

            state.setPlayer(changedplayer.getUUID());
            states.put(changedplayer.getUUID(), state);
            broadcastState(server, state);
        });
    }

    private void onSleep(ServerPlayer player) {
        List<ServerPlayer> noSleepPlayers = getNoSleepPlayers(player.server);

        if (noSleepPlayers.isEmpty()) {
            return;
        }

        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(Status.SERVER_CONFIG.noSleepTitle.get())));
        if (noSleepPlayers.size() > 1) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(Status.SERVER_CONFIG.noSleepMultipleSubtitle.get())));
        } else {
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(String.format(Status.SERVER_CONFIG.noSleepPlayerSubtitle.get(), noSleepPlayers.get(0).getDisplayName().getString()))));
        }

    }

    private List<ServerPlayer> getNoSleepPlayers(MinecraftServer server) {
        List<ServerPlayer> players = new ArrayList<>();
        for (Map.Entry<UUID, PlayerState> entry : states.entrySet()) {
            if (entry.getValue().isNoSleep()) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    players.add(player);
                }
            }
        }
        return players;
    }

    private void broadcastState(MinecraftServer server, PlayerState state) {
        PlayerStatePacket packet = new PlayerStatePacket(state);
        server.getPlayerList().getPlayers().forEach(p -> NetManager.sendToClient(p, packet));
    }

    private void notifyPlayer(ServerPlayer player) {
        PlayerStatesPacket packet = new PlayerStatesPacket(states);
        NetManager.sendToClient(player, packet);
        broadcastState(player.server, new PlayerState(player.getUUID()));
    }

    private void removePlayer(ServerPlayer player) {
        states.remove(player.getUUID());
        broadcastState(player.server, new PlayerState(player.getUUID()));
    }

    @Nullable
    public PlayerState getState(UUID playerUUID) {
        return states.get(playerUUID);
    }

    public Collection<PlayerState> getStates() {
        return states.values();
    }

}
