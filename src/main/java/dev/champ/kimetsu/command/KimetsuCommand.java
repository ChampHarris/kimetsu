package dev.champ.kimetsu.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.champ.kimetsu.combat.CombatServer;
import dev.champ.kimetsu.entity.KimetsuEntities;
import dev.champ.kimetsu.entity.M1Demon;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /kimetsu}, for game masters. {@code /kimetsu demon} puts the M1 demon 4 blocks in front of you, facing you.
 * {@code /kimetsu debug log on|off} starts or stops the fight logs until the server stops (D-069; on by default in dev
 * runs, until 5d's SYNCED switch). {@code RegisterCommandsEvent} runs on a worker thread at every data load, so this
 * only builds the tree.
 */
public final class KimetsuCommand {
    private static final double AHEAD = 4;

    private KimetsuCommand() {}

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(Commands.literal("kimetsu")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("demon").executes(c -> demon(c.getSource())))
                        .then(Commands.literal("debug")
                                .then(Commands.literal("log")
                                        .then(Commands.literal("on").executes(c -> log(c.getSource(), true)))
                                        .then(Commands.literal("off").executes(c -> log(c.getSource(), false))))));
    }

    private static int log(CommandSourceStack source, boolean on) {
        CombatServer c = CombatServer.of(source.getServer());
        if (c == null) {
            source.sendFailure(Component.literal("Kimetsu: combat is off (its data didn't load)"));
            return 0;
        }
        c.logging(on);
        String folder = c.logFolder().toAbsolutePath().normalize().toString();
        source.sendSuccess(
                () -> Component.literal(
                        on ? "Kimetsu: the fight log is on, in " + folder : "Kimetsu: the fight log is off"),
                true);
        return 1;
    }

    private static int demon(CommandSourceStack source) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        M1Demon d = KimetsuEntities.M1_DEMON.get().create(level, EntitySpawnReason.COMMAND);
        if (d == null) {
            source.sendFailure(Component.literal("Kimetsu: the demon couldn't be made here"));
            return 0;
        }
        float yaw = source.getRotation().y;
        Vec3 at = source.getPosition().add(Vec3.directionFromRotation(0, yaw).scale(AHEAD));
        d.snapTo(at.x, at.y, at.z, yaw + 180, 0); // facing back at you
        d.setYHeadRot(yaw + 180);
        d.setYBodyRot(yaw + 180); // or the body faces south until its goal turns it
        if (!level.tryAddFreshEntityWithPassengers(d)) {
            source.sendFailure(Component.literal("Kimetsu: the demon couldn't be added"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Kimetsu: an M1 demon, 4 blocks ahead"), true);
        return 1;
    }
}
