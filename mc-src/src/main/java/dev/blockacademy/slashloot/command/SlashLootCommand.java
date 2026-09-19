package dev.blockacademy.slashloot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.blockacademy.slashloot.compat.Ids;
import dev.blockacademy.slashloot.compat.Perms;
import dev.blockacademy.slashloot.config.SlashLootConfig;
import dev.blockacademy.slashloot.handler.CleanupHandler;
import dev.blockacademy.slashloot.store.SlashLootState;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Collection;

/**
 * Admin commands (permission level 2):
 *
 * <pre>
 *   /slashloot forget here             wipe every player's loot at the block you are looking at
 *   /slashloot forget at &lt;pos&gt;         same, for an explicit position
 *   /slashloot forget player &lt;player&gt;  wipe one player's loot everywhere in this dimension
 *   /slashloot forget all              wipe this dimension's stored loot entirely
 *   /slashloot prune                   drop entries whose container no longer exists (loaded chunks)
 *   /slashloot stats                   stored entry counts for this dimension
 *   /slashloot reload                  re-read config/slashloot.json
 * </pre>
 */
public final class SlashLootCommand {

    private SlashLootCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext ctx) {
        dispatcher.register(Commands.literal("slashloot")
                .requires(Perms.gamemasters())
                .then(Commands.literal("forget")
                        .then(Commands.literal("here").executes(SlashLootCommand::forgetHere))
                        .then(Commands.literal("at")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(SlashLootCommand::forgetAt)))
                        .then(Commands.literal("player")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(SlashLootCommand::forgetPlayer)))
                        .then(Commands.literal("all").executes(SlashLootCommand::forgetAll)))
                .then(Commands.literal("prune").executes(SlashLootCommand::prune))
                .then(Commands.literal("stats").executes(SlashLootCommand::stats))
                .then(Commands.literal("reload").executes(SlashLootCommand::reload)));
    }

    private static int forgetHere(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer sp = ctx.getSource().getPlayerOrException();
        BlockPos resolved = sp.blockPosition();
        if (sp.pick(5.0D, 0.0F, false) instanceof BlockHitResult bhr) {
            resolved = bhr.getBlockPos();
        }
        final BlockPos target = resolved;
        SlashLootState.get((ServerLevel) sp.level()).forgetBlock(target.asLong());
        ctx.getSource().sendSuccess(
                () -> Component.literal("SlashLoot: forgot all player loot at " + posStr(target)), true);
        return 1;
    }

    private static int forgetAt(CommandContext<CommandSourceStack> ctx) {
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        SlashLootState.get(ctx.getSource().getLevel()).forgetBlock(pos.asLong());
        ctx.getSource().sendSuccess(
                () -> Component.literal("SlashLoot: forgot all player loot at " + posStr(pos)), true);
        return 1;
    }

    private static int forgetPlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "player");
        ServerLevel level = ctx.getSource().getLevel();
        for (ServerPlayer p : targets) {
            SlashLootState.get(level).forgetPlayerEverywhere(p.getUUID());
        }
        int count = targets.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "SlashLoot: forgot loot for " + count + " player(s) in " + Ids.dimension(level)), true);
        return count;
    }

    private static int forgetAll(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        int removed = SlashLootState.get(level).forgetAll();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "SlashLoot: cleared " + removed + " stored container(s) in " + Ids.dimension(level)), true);
        return removed;
    }

    private static int prune(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        int removed = CleanupHandler.pruneNow(level);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "SlashLoot: pruned " + removed + " stale container(s) in " + Ids.dimension(level)
                        + " (entries in unloaded chunks are skipped)"), true);
        return removed;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        SlashLootState store = SlashLootState.get(level);
        int blocks = store.blockCount();
        int entities = store.entityCount();
        int copies = store.playerCopyCount();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "SlashLoot [" + Ids.dimension(level) + "]  blocks=" + blocks
                        + "  entities=" + entities
                        + "  player copies=" + copies
                        + "  pruned this session=" + CleanupHandler.totalPruned()
                        + "  queued=" + CleanupHandler.queuedForPrune()), false);
        return blocks + entities;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        SlashLootConfig.reload();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "SlashLoot: reloaded slashloot.json (debugLogging="
                        + SlashLootConfig.get().debugLogging + ")"), true);
        return 1;
    }

    private static String posStr(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
