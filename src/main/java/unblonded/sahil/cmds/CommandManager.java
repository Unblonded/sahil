package unblonded.sahil.cmds;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class CommandManager {
    public static final CommandDispatcher<FabricClientCommandSource> DISPATCHER = new CommandDispatcher<>();
    private static final List<Command> commands = new ArrayList<>();
    private static final String PREFIX = "`";
    private static MinecraftClient client;

    public static void init() {
        ClientSendMessageEvents.ALLOW_CHAT.register((message) -> {
            if (message.startsWith(PREFIX)) {
                handleCommand(message);
                return false;
            }
            return true;
        });

        client = MinecraftClient.getInstance();
    }

    public static void register(Command command) {
        commands.add(command);

        LiteralArgumentBuilder<FabricClientCommandSource> builder = LiteralArgumentBuilder.literal(command.name);
        command.build(builder);
        DISPATCHER.register(builder);
    }

    private static void handleCommand(String message) {
        try {
            String commandText = message.substring(PREFIX.length());

            if (client.getNetworkHandler() != null && client.player != null) {
                FabricClientCommandSource source = (FabricClientCommandSource) client.getNetworkHandler().getCommandSource();
                DISPATCHER.execute(commandText, source);
            }
        } catch (Exception e) {
            client.player.sendMessage(Text.literal("Error executing command: " + e.getMessage()), false);
        }
    }

    public static String getPrefix() {return PREFIX;}

    public static List<Command> getCommands() {return new ArrayList<>(commands);}
}