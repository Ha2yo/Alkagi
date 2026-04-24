package org.ha2yo.alkagi.game;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PresetRepository {

    private final File file;
    private final YamlConfiguration config;

    public PresetRepository(JavaPlugin plugin) {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        this.file = new File(plugin.getDataFolder(), "presets.yml");
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public List<String> listPresetNames() {
        ConfigurationSection section = config.getConfigurationSection("presets");
        if (section == null) {
            return List.of();
        }

        List<String> names = new ArrayList<>(section.getKeys(false));
        Collections.sort(names);
        return names;
    }

    public boolean hasPreset(String presetName) {
        return config.isConfigurationSection("presets." + presetName);
    }

    public @Nullable PresetData loadPreset(String presetName) {
        String path = "presets." + presetName;
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return null;
        }

        PresetData presetData = new PresetData(presetName);
        double legacyPieceSize = Math.max(0.5D, section.getDouble("piece-size", 2.35D));
        readTeamLocations(section, "black", TeamType.BLACK, presetData, legacyPieceSize);
        readTeamLocations(section, "white", TeamType.WHITE, presetData, legacyPieceSize);
        return presetData;
    }

    public void savePreset(PresetData presetData) {
        String path = "presets." + presetData.getName();
        config.set(path + ".piece-size", null);
        writeTeamLocations(path + ".black", presetData.getPieces(TeamType.BLACK));
        writeTeamLocations(path + ".white", presetData.getPieces(TeamType.WHITE));
        save();
    }

    public boolean deletePreset(String presetName) {
        if (!hasPreset(presetName)) {
            return false;
        }

        config.set("presets." + presetName, null);
        save();
        return true;
    }

    private void readTeamLocations(
            ConfigurationSection section,
            String teamKey,
            TeamType teamType,
            PresetData presetData,
            double defaultPieceSize
    ) {
        List<java.util.Map<?, ?>> rawList = section.getMapList(teamKey);
        for (java.util.Map<?, ?> raw : rawList) {
            Location location = readLocation(raw);
            if (location != null) {
                presetData.addPiece(teamType, location, readPieceSize(raw, defaultPieceSize));
            }
        }
    }

    private void writeTeamLocations(String path, List<PresetData.PresetPiece> pieces) {
        List<java.util.Map<String, Object>> serialized = new ArrayList<>();
        for (PresetData.PresetPiece piece : pieces) {
            Location location = piece.location();
            if (location.getWorld() == null) {
                continue;
            }

            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("world", location.getWorld().getName());
            entry.put("x", location.getX());
            entry.put("y", location.getY());
            entry.put("z", location.getZ());
            entry.put("size", piece.pieceSize());
            serialized.add(entry);
        }
        config.set(path, serialized);
    }

    private @Nullable Location readLocation(java.util.Map<?, ?> map) {
        Object worldNameRaw = map.get("world");
        if (!(worldNameRaw instanceof String worldName)) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }

        return new Location(
            world,
            toDouble(map.get("x")),
            toDouble(map.get("y")),
            toDouble(map.get("z"))
        );
    }

    private double toDouble(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0D;
    }

    private double readPieceSize(java.util.Map<?, ?> map, double defaultPieceSize) {
        Object value = map.get("size");
        if (value == null) {
            return defaultPieceSize;
        }
        return Math.max(0.5D, toDouble(value));
    }

    private void save() {
        try {
            config.save(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save presets.yml", exception);
        }
    }
}
