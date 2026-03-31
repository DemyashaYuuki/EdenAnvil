package pro.edenworld.spinanvil;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class EdenAnvilSpinPlugin extends JavaPlugin implements Listener {

    private static final Set<Material> ANVIL_TYPES = Set.of(
            Material.ANVIL,
            Material.CHIPPED_ANVIL,
            Material.DAMAGED_ANVIL
    );

    private final Map<BlockKey, ActiveSession> sessions = new HashMap<>();
    private final Map<UUID, Integer> soundUsageCounts = new HashMap<>();

    private BukkitTask tickTask;

    private float spinRadiansPerTick;
    private int hideRefreshTicks;
    private double viewerRadius;
    private double viewerRadiusSquared;
    private String soundKey;
    private SoundCategory soundCategory;
    private float soundVolume;
    private float soundPitch;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfig();
        getServer().getPluginManager().registerEvents(this, this);
        tickTask = getServer().getScheduler().runTaskTimer(this, this::tickSessions, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }

        for (ActiveSession session : sessions.values()) {
            session.cleanup(this);
        }
        sessions.clear();
        soundUsageCounts.clear();
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        reloadPluginConfig();
    }

    private void reloadPluginConfig() {
        FileConfiguration config = getConfig();
        this.spinRadiansPerTick = (float) Math.toRadians(config.getDouble("spin.degrees-per-tick", 8.0D));
        this.hideRefreshTicks = Math.max(1, config.getInt("spin.hide-refresh-ticks", 10));
        this.viewerRadius = Math.max(1.0D, config.getDouble("spin.viewer-radius", 32.0D));
        this.viewerRadiusSquared = viewerRadius * viewerRadius;
        this.soundKey = config.getString("sound.key", "edenanvil:anvil_spin");
        this.soundVolume = (float) config.getDouble("sound.volume", 1.6D);
        this.soundPitch = (float) config.getDouble("sound.pitch", 1.0D);

        String rawCategory = config.getString("sound.category", SoundCategory.BLOCKS.name());
        if (rawCategory == null) {
            this.soundCategory = SoundCategory.BLOCKS;
            return;
        }
        try {
            this.soundCategory = SoundCategory.valueOf(rawCategory.toUpperCase());
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Unknown sound.category '" + rawCategory + "', using BLOCKS.");
            this.soundCategory = SoundCategory.BLOCKS;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getType() != InventoryType.ANVIL) {
            return;
        }

        Location location = event.getInventory().getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }

        Block block = location.getBlock();
        if (!isAnvil(block.getType())) {
            return;
        }

        BlockKey key = BlockKey.from(block);
        ActiveSession session = sessions.computeIfAbsent(key, ignored -> ActiveSession.create(block));
        session.viewers.add(player.getUniqueId());
        session.ensureDisplay(this);
        session.hideForNearbyPlayers(this);
        session.playForNearbyPlayers(this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getType() != InventoryType.ANVIL) {
            return;
        }

        Location location = event.getInventory().getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }

        ActiveSession session = sessions.get(BlockKey.from(location));
        if (session == null) {
            return;
        }

        session.viewers.remove(player.getUniqueId());
        if (session.viewers.isEmpty()) {
            sessions.remove(session.key);
            session.cleanup(this);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isAnvil(block.getType())) {
            return;
        }

        ActiveSession session = sessions.remove(BlockKey.from(block));
        if (session != null) {
            session.cleanup(this);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeViewerEverywhere(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        removeViewerEverywhere(event.getPlayer().getUniqueId());
    }

    private void removeViewerEverywhere(UUID playerId) {
        Iterator<Map.Entry<BlockKey, ActiveSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockKey, ActiveSession> entry = iterator.next();
            ActiveSession session = entry.getValue();
            session.viewers.remove(playerId);
            if (session.viewers.isEmpty()) {
                iterator.remove();
                session.cleanup(this);
            }
        }
    }

    private void tickSessions() {
        Iterator<Map.Entry<BlockKey, ActiveSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockKey, ActiveSession> entry = iterator.next();
            ActiveSession session = entry.getValue();

            if (!session.isStillValid()) {
                iterator.remove();
                session.cleanup(this);
                continue;
            }

            session.rotate(this);

            session.hideTickCounter++;
            if (session.hideTickCounter >= hideRefreshTicks) {
                session.hideTickCounter = 0;
                session.hideForNearbyPlayers(this);
                session.playForNearbyPlayers(this);
            }
        }
    }

    private boolean isAnvil(Material type) {
        return ANVIL_TYPES.contains(type);
    }

    private Set<Player> getNearbyPlayers(Location origin) {
        Set<Player> players = new HashSet<>();
        World world = origin.getWorld();
        if (world == null) {
            return players;
        }

        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(origin) <= viewerRadiusSquared) {
                players.add(player);
            }
        }
        return players;
    }

    private void incrementSoundUsage(Player player) {
        soundUsageCounts.merge(player.getUniqueId(), 1, Integer::sum);
    }

    private void decrementSoundUsage(Player player) {
        UUID uuid = player.getUniqueId();
        Integer current = soundUsageCounts.get(uuid);
        if (current == null) {
            return;
        }

        if (current <= 1) {
            soundUsageCounts.remove(uuid);
            player.stopSound(soundKey, soundCategory);
            return;
        }

        soundUsageCounts.put(uuid, current - 1);
    }

    private static final class ActiveSession {
        private final BlockKey key;
        private final World world;
        private final Location blockLocation;
        private final Set<UUID> viewers = new HashSet<>();
        private final Set<UUID> hiddenRecipients = new HashSet<>();
        private final Set<UUID> soundedRecipients = new HashSet<>();
        private BlockData originalBlockData;
        private BlockDisplay display;
        private float angle;
        private int hideTickCounter;

        private ActiveSession(BlockKey key, World world, Location blockLocation, BlockData originalBlockData) {
            this.key = key;
            this.world = world;
            this.blockLocation = blockLocation;
            this.originalBlockData = originalBlockData;
        }

        public static ActiveSession create(Block block) {
            return new ActiveSession(
                    BlockKey.from(block),
                    block.getWorld(),
                    block.getLocation(),
                    block.getBlockData().clone()
            );
        }

        public boolean isStillValid() {
            return world != null && isAnvil(world.getBlockAt(blockLocation).getType());
        }

        private static boolean isAnvil(Material material) {
            return ANVIL_TYPES.contains(material);
        }

        public void ensureDisplay(EdenAnvilSpinPlugin plugin) {
            Block currentBlock = world.getBlockAt(blockLocation);
            this.originalBlockData = currentBlock.getBlockData().clone();

            if (display != null && display.isValid()) {
                display.setBlock(originalBlockData);
                return;
            }

            display = world.spawn(blockLocation.clone(), BlockDisplay.class, spawned -> {
                spawned.setBlock(originalBlockData);
                spawned.setPersistent(false);
                spawned.setInvulnerable(true);
                spawned.setGravity(false);
                spawned.setSilent(true);
                spawned.setShadowRadius(0.0F);
                spawned.setViewRange((float) Math.max(1.0D, plugin.viewerRadius));
                spawned.setInterpolationDelay(0);
                spawned.setInterpolationDuration(1);
            });

            applyTransform();
        }

        public void rotate(EdenAnvilSpinPlugin plugin) {
            ensureDisplay(plugin);
            angle += plugin.spinRadiansPerTick;
            applyTransform();
        }

        private void applyTransform() {
            if (display == null || !display.isValid()) {
                return;
            }

            Matrix4f matrix = new Matrix4f()
                    .translate(0.5F, 0.5F, 0.5F)
                    .rotateY(angle)
                    .translate(-0.5F, -0.5F, -0.5F);

            display.setTransformationMatrix(matrix);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(1);
        }

        public void hideForNearbyPlayers(EdenAnvilSpinPlugin plugin) {
            BlockData air = Material.AIR.createBlockData();
            for (Player nearby : plugin.getNearbyPlayers(blockLocation)) {
                nearby.sendBlockChange(blockLocation, air);
                hiddenRecipients.add(nearby.getUniqueId());
            }
        }

        public void playForNearbyPlayers(EdenAnvilSpinPlugin plugin) {
            for (Player nearby : plugin.getNearbyPlayers(blockLocation)) {
                if (!soundedRecipients.add(nearby.getUniqueId())) {
                    continue;
                }
                nearby.playSound(blockLocation, plugin.soundKey, plugin.soundCategory, plugin.soundVolume, plugin.soundPitch);
                plugin.incrementSoundUsage(nearby);
            }
        }

        public void cleanup(EdenAnvilSpinPlugin plugin) {
            BlockData actualData = world.getBlockAt(blockLocation).getBlockData();

            for (UUID uuid : hiddenRecipients) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline() && player.getWorld().equals(world)) {
                    player.sendBlockChange(blockLocation, actualData);
                }
            }
            hiddenRecipients.clear();

            for (UUID uuid : soundedRecipients) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    plugin.decrementSoundUsage(player);
                }
            }
            soundedRecipients.clear();

            if (display != null && display.isValid()) {
                display.remove();
            }
            display = null;
        }
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        private static BlockKey from(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        private static BlockKey from(Location location) {
            return new BlockKey(
                    Objects.requireNonNull(location.getWorld(), "world").getUID(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
        }
    }
}
