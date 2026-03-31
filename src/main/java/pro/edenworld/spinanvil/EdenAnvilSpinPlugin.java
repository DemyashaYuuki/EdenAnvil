package pro.edenworld.spinanvil;

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
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Matrix4f;

import java.util.HashMap;
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

    private BukkitTask tickTask;

    private float spinRadiansPerTick;
    private double liftBlocks;
    private int musicDurationTicks;
    private float displayViewRange;
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
            session.restoreImmediately();
        }
        sessions.clear();
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        reloadPluginConfig();
    }

    private void reloadPluginConfig() {
        FileConfiguration config = getConfig();
        this.spinRadiansPerTick = (float) Math.toRadians(config.getDouble("spin.degrees-per-tick", 10.0D));
        this.liftBlocks = config.getDouble("spin.lift-blocks", 0.125D);
        this.musicDurationTicks = Math.max(1, (int) Math.round(config.getDouble("spin.music-duration-seconds", 143.0D) * 20.0D));
        this.displayViewRange = (float) Math.max(1.0D, config.getDouble("spin.viewer-radius", 32.0D));
        this.soundKey = config.getString("sound.key", "edenanvil:anvil_spin");
        this.soundVolume = (float) config.getDouble("sound.volume", 1.6D);
        this.soundPitch = (float) config.getDouble("sound.pitch", 1.0D);

        String rawCategory = config.getString("sound.category", SoundCategory.RECORDS.name());
        if (rawCategory == null) {
            this.soundCategory = SoundCategory.RECORDS;
            return;
        }

        try {
            this.soundCategory = SoundCategory.valueOf(rawCategory.toUpperCase());
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Unknown sound.category '" + rawCategory + "', using RECORDS.");
            this.soundCategory = SoundCategory.RECORDS;
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
        if (sessions.containsKey(key)) {
            getServer().getScheduler().runTask(this, player::closeInventory);
            return;
        }

        ActiveSession session = ActiveSession.start(block, this);
        sessions.put(key, session);
        getServer().getScheduler().runTask(this, player::closeInventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (sessions.containsKey(BlockKey.from(event.getBlockPlaced()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (sessions.containsKey(BlockKey.from(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    private void tickSessions() {
        Iterator<Map.Entry<BlockKey, ActiveSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            ActiveSession session = iterator.next().getValue();

            session.tick(this);
            if (session.isFinished()) {
                iterator.remove();
                session.restoreImmediately();
            }
        }
    }

    private boolean isAnvil(Material type) {
        return ANVIL_TYPES.contains(type);
    }

    private static final class ActiveSession {
        private final World world;
        private final Location blockLocation;
        private final BlockData originalBlockData;
        private final int totalDurationTicks;
        private BlockDisplay display;
        private float angle;
        private int ageTicks;

        private ActiveSession(World world, Location blockLocation, BlockData originalBlockData, int totalDurationTicks) {
            this.world = world;
            this.blockLocation = blockLocation;
            this.originalBlockData = originalBlockData;
            this.totalDurationTicks = totalDurationTicks;
        }

        public static ActiveSession start(Block block, EdenAnvilSpinPlugin plugin) {
            ActiveSession session = new ActiveSession(
                    block.getWorld(),
                    block.getLocation(),
                    block.getBlockData().clone(),
                    plugin.musicDurationTicks
            );

            block.setType(Material.AIR, false);

            session.display = session.world.spawn(session.blockLocation.clone(), BlockDisplay.class, spawned -> {
                spawned.setBlock(session.originalBlockData);
                spawned.setPersistent(false);
                spawned.setInvulnerable(true);
                spawned.setGravity(false);
                spawned.setSilent(true);
                spawned.setShadowRadius(0.0F);
                spawned.setViewRange(plugin.displayViewRange);
                spawned.setInterpolationDelay(0);
                spawned.setInterpolationDuration(1);
            });

            session.applyTransform(plugin);
            session.world.playSound(session.blockLocation, plugin.soundKey, plugin.soundCategory, plugin.soundVolume, plugin.soundPitch);
            return session;
        }

        public void tick(EdenAnvilSpinPlugin plugin) {
            ageTicks++;
            angle += plugin.spinRadiansPerTick;
            applyTransform(plugin);
        }

        public boolean isFinished() {
            return ageTicks >= totalDurationTicks;
        }

        private void applyTransform(EdenAnvilSpinPlugin plugin) {
            if (display == null || !display.isValid()) {
                return;
            }

            Matrix4f matrix = new Matrix4f()
                    .translate(0.5F, (float) (0.5D + plugin.liftBlocks), 0.5F)
                    .rotateY(angle)
                    .translate(-0.5F, -0.5F, -0.5F);

            display.setTransformationMatrix(matrix);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(1);
        }

        public void restoreImmediately() {
            Block block = world.getBlockAt(blockLocation);
            if (block.getType().isAir()) {
                block.setBlockData(originalBlockData.clone(), false);
            }

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
