package dev.champ.kimetsu.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import dev.champ.kimetsu.Kimetsu;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.resource.ListenerKey;

/**
 * Loads the combat data on every data load (server start and {@code /reload}): the four folders' JSON, read off the
 * background thread and built into a generation all or nothing (spec Data &amp; State; D-052). It reads the files
 * itself, not through {@code SimpleJsonResourceReloadListener}, which logs and skips a file that fails to parse: a
 * generation with a file missing would pass. Retained in the server's resources, where the combat server picks the
 * result up once the load is done ({@code CombatServer}); a failed build leaves the running generation in place.
 */
public final class CombatDataLoader extends SimplePreparableReloadListener<CombatDataBuilder.Result> {
    public static final ListenerKey<CombatDataLoader> KEY =
            ListenerKey.create(Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, "combat_data"));

    private CombatDataBuilder.Result result = new CombatDataBuilder.Result(null, List.of("not loaded yet"));

    /** Registers a fresh loader for each data load. */
    public static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addRetainedListener(KEY, new CombatDataLoader());
    }

    /** This load's outcome. */
    public CombatDataBuilder.Result result() {
        return result;
    }

    @Override
    protected CombatDataBuilder.Result prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<String, Map<String, JsonElement>> sources = new TreeMap<>();
        List<String> errors = new ArrayList<>();
        for (String folder : CombatDataBuilder.FOLDERS) {
            FileToIdConverter files = FileToIdConverter.json(folder);
            Map<String, JsonElement> byId = new TreeMap<>();
            for (Map.Entry<Identifier, Resource> e :
                    files.listMatchingResources(manager).entrySet()) {
                String id = files.fileToId(e.getKey()).toString();
                try (Reader reader = e.getValue().openAsReader()) {
                    byId.put(id, StrictJsonParser.parse(reader));
                } catch (IOException | JsonParseException ex) {
                    errors.add(folder + "/" + id + ": " + ex.getMessage());
                }
            }
            sources.put(folder, byId);
        }
        return CombatDataBuilder.build(sources, errors);
    }

    @Override
    protected void apply(CombatDataBuilder.Result prepared, ResourceManager manager, ProfilerFiller profiler) {
        result = prepared;
    }
}
