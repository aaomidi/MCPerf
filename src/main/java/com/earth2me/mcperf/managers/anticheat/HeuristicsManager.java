/*
 * WARNING: THIS FILE IS EXTREMELY SENSITIVE
 * =========================================
 *
 * UNLESS YOU HAVE BEEN GRANTED EXPLICIT WRITTEN PERMISSION TO READ THIS
 * SPECIFIC FILE, YOU DO NOT HAVE PERMISSION TO DO SO.  IT CONTAINS
 * HIGHLY CONFIDENTIAL TRADE SECRETS.
 *
 * THE LIST OF PEOPLE CURRENTLY AUTHORIZED TO VIEW THIS FILE:
 *
 *   * Paul Buonopane
 *
 * IF YOU ARE NOT IN THE ABOVE LIST, PLEASE CLOSE THIS FILE.  DOING
 * OTHERWISE IS A BREACH OF LICENSE.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package com.earth2me.mcperf.managers.anticheat;

import com.earth2me.mcperf.config.ConfigSetting;
import com.earth2me.mcperf.managers.Manager;
import com.earth2me.mcperf.mojang.MathHelper;
import com.earth2me.mcperf.annotation.ContainsConfig;
import com.earth2me.mcperf.annotation.Service;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

@Service
@ContainsConfig
public final class HeuristicsManager extends Manager {
    private static final Set<Integer> JUMP_ACCELS = new HashSet<>(Arrays.asList(-850, -1684, -2501, -3331, -4115, -4884, -3487, -752, -5637, -6375, -7098, -4372));
    private static final UUID devId = UUID.fromString("04e66058-ddf6-4520-93b2-3bc3f675c132");  // Zenexer

    private final WeakHashMap<Player, Detector> detectors = new WeakHashMap<>();

    @Getter
    @Setter
    private double significantMovement = 0.4;
    @Getter
    @Setter
    private long timeout = 75;
    @Getter
    @Setter
    private int missThreshold = 4;
    @Getter
    @Setter
    private int maxBlackmarks = 4;
    @Getter
    @Setter
    private int forgivenOnDeath = 2;
    @Getter
    @Setter
    private int blackmarksTimeout = 60;
    @Getter
    @Setter
    @ConfigSetting
    private List<String> uncertainCommands = Collections.singletonList("kick %1$s [MCPerf] Cheating: %2$s");
    @Getter
    @Setter
    @ConfigSetting
    private List<String> certainCommands = Collections.singletonList("tempban %1$s 3d [MCPerf] Cheating: %2$s");
    @Getter
    @Setter
    @ConfigSetting
    private boolean debugEnabled = false;
    @Getter
    @Setter
    @ConfigSetting
    private boolean verboseDebugEnabled = false;
    @Setter
    private boolean ready = false;

    private BukkitTask readyTask;

    @SuppressWarnings("SpellCheckingInspection")
    public HeuristicsManager() {
        super("MTMbaGV1cmlzdGljcwo=");
    }

    // Note that this is not thread-safe.
    private void cancelEnableTask() {
        if (readyTask != null) {
            readyTask.cancel();
            readyTask = null;
        }
    }

    public boolean isReady() {
        return ready && isEnabled();
    }

    @Override
    public void onInit() {
        cancelEnableTask();

        getLogger().info("Waiting until ready: " + getId());
        readyTask = getServer().getScheduler().runTaskLater(getPlugin(), () -> {
            readyTask = null;
            getLogger().info("Ready: " + getId());
            setReady(true);
        }, 70);
    }

    @Override
    public void onDeinit() {
        cancelEnableTask();
        
        setReady(false);
    }

    private Detector getDetector(Player player) {
        return getDetector(player, true);
    }

    private Detector getDetector(Player player, boolean createIfMissing) {
        Detector detector = detectors.get(player);
        if (detector == null && createIfMissing) {
            detectors.put(player, detector = new Detector(player));
        }
        return detector;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!isReady()) {
            return;
        }

        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player victim = (Player) event.getEntity();
        Detector victimD = getDetector(victim);
        victimD.onDamaged();  // We need to call this even if damager isn't a player.

        if (event.getDamager() instanceof Player) {
            Player attacker = (Player) event.getDamager();
            Detector attackerD = getDetector(attacker);

            if (event.getFinalDamage() == 0.0) {
                debug("%s null-hit %s", attacker.getName(), victim.getName());
                return;
            }

            victimD.markGotHit(attacker);

            switch (event.getCause()) {
                case ENTITY_ATTACK:
                    attackerD.markHit(victim);
                    if (isDebugEnabled() && victimD.isEnabled() && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0) {
                        debug("%s is blocking", event.getEntity().getName());
                    }
                    break;

                // TODO: PROJECTILE
                default:
                    debug("%s hit %s via %s", attacker.getName(), victim.getName(), event.getCause().name().toLowerCase());
                    break;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByBlock(EntityDamageByBlockEvent event) {
        if (!isReady()) {
            return;
        }

        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            getDetector(player).onDamaged();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        if (!isReady()) {
            return;
        }

        Player player = event.getPlayer();
        Inventory inventory = player.getInventory();
        ItemStack a = inventory.getItem(event.getNewSlot());
        ItemStack b = inventory.getItem(event.getPreviousSlot());

        if (a != null && a.getType() != null && a.getType().isEdible() && (b == null || b.getType() == null || !b.getType().isEdible())) {
            getDetector(player).onEating();
        }

        getDetector(player).onItemHeld();
    }

    @SuppressWarnings("deprecation")
    private boolean isOnGround(Player player) {
        // As usual, Bukkit deprecated this inappropriately.
        // The reason they provided: Inconsistent with Entity.isOnGround()
        // Yes, that's why it'd be overridden.  Duh.  Except they didn't override it, so it's just wrong.
        return player.isOnGround();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        if (!isReady()) {
            return;
        }

        Player player = event.getPlayer();
        Vector velocity = event.getVelocity();
        Detector detector = getDetector(player);

        if (isDebugEnabled() && detector.isEnabled()) {
            if (velocity.getY() == -0.0784000015258789 && velocity.getX() == 0.0 && velocity.getZ() == 0.0) {
                debug("Standard damage velocity event: %s", player.getName());
            } else {
                debug("Velocity for %s: %s; %s; speed %f", player.getName(), velocity, isOnGround(player) ? "on ground" : "in air", player.isFlying() ? player.getFlySpeed() : player.getWalkSpeed());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!isReady()) {
            return;
        }

        Player player = event.getPlayer();
        Detector detector = getDetector(player);
        Location from = event.getFrom();
        Location to = event.getTo();

        if (event.isCancelled()) {
            detector.onCancelledMove(from);
            return;
        }

        if (!player.isFlying() && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
            Vector delta = to.toVector().subtract(from.toVector());
            double deltaH = Math.sqrt(Math.pow(delta.getX(), 2) + Math.pow(delta.getZ(), 2));
            double deltaV = delta.getY();
            if (player.getWalkSpeed() != 0.2) {
                float speed = player.getWalkSpeed();
                deltaH /= speed * 5;
            }

            double deltaEH = deltaH;
            double deltaEV = deltaV;

            if (player.isInsideVehicle()) {
                // TODO: Handle vehicles/mounts via deltaEH/deltaEV
                //       What happens if a mount or a player in a vehicle is affected by a potion?
                //       Do either have any effect?  Which one takes precedence?  Do they stack?
                detector.resetMovement();
            }

            for (PotionEffect potion : player.getActivePotionEffects()) {
                byte level = (byte) potion.getAmplifier();
                switch (potion.getType().hashCode()) {
                    case 1:  // SPEED
                        deltaEH /= 1.0 + 0.20 * level;
                        break;

                    case 2:  // SLOW
                        if (level >= 7) {
                            //deltaEH = deltaEH == 0.0 ? 0.0 : Double.POSITIVE_INFINITY;
                            // Skip for now
                            return;
                        } else {
                            deltaEH /= 1.0 - 0.15 * level;
                        }
                        break;

                    case 8:  // JUMP
                        deltaEV /= Math.pow(level + 4.2, 2) / 16 / 1.1025;
                        break;
                }
            }

            if (isDebugEnabled() && detector.isEnabled()) {
                double deltaVa = Math.abs(delta.getY());
                if (deltaVa > 0.5 || (deltaH > 0.6 && deltaV != 0.41999998688697815)) {  // 0.412 is jump while sprinting
                    debug("Movement for %s: %s; H:%.6f, V:%.6f; %s; speed %f", player.getName(), delta, deltaH, deltaV, isOnGround(player) ? "on ground" : "in air", player.getWalkSpeed());
                }
            }

            detector.onMove(deltaH, deltaV, deltaEH, deltaEV, from, event.getTo());
        } else {
            //debug("Reset due to flight/game mode: %s", player.getName());
            detector.reset();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!isReady()) {
            return;
        }

        Player player = event.getPlayer();
        Detector detector = getDetector(player);

        detector.onTeleport();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!isReady()) {
            return;
        }

        if (event.isCancelled()) {
            return;  // Just to be safe
        }

        Player player = event.getPlayer();
        Detector detector = getDetector(player);

        switch (event.getAction()) {
            case LEFT_CLICK_AIR:
                detector.markMiss(true);
                break;

            case LEFT_CLICK_BLOCK:
                if (event.useInteractedBlock() == Event.Result.DENY) {
                    // We're not going to receive a BlockBreakEvent, so we need to figure out if this
                    // type of block breaks instantly.
                    Material material = event.getClickedBlock().getType();
                    if (!material.isSolid()) {  // Should be good enough.
                        return;
                    }
                }

                detector.markMiss(false);
                break;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!isReady()) {
            return;
        }

        getDetector(event.getEntity()).forgiveBlackmarks(forgivenOnDeath);

        EntityDamageEvent damageEvent = event.getEntity().getLastDamageCause();
        if (damageEvent instanceof EntityDamageByEntityEvent) {
            EntityDamageByEntityEvent e = (EntityDamageByEntityEvent) damageEvent;
            if (e.getDamager() instanceof Player) {
                getDetector((Player) e.getDamager()).onKilled(event.getEntity());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        if (!isReady()) {
            return;
        }

        onPlayerDepart(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!isReady()) {
            return;
        }

        onPlayerDepart(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isReady()) {
            return;
        }

        // It's possible to break certain blocks very, very quickly.  We don't always know what
        // materials those are because they change with updates, and the server may not be as
        // up-to-date as the client.  Easiest to just taint everything, which doesn't really
        // create any false negatives.

        Player player = event.getPlayer();
        if (player != null) {
            getDetector(player).onAttackSpeedTainted();
        }
    }

    private void onPlayerDepart(Player player) {
        if (!isReady()) {
            return;
        }

        Detector detector = detectors.remove(player);
        if (detector != null) {
            detector.reset();
            detector.close();
        }
    }

    public void onCaughtCheating(Player player, boolean certain, String reason) {
        if (!player.isOnline()) {
            return;
        }

        Detector detector = getDetector(player);
        detector.reset();

        Object[] args = new Object[]{
                player.getName(),
                reason,
                player.getAddress().getAddress().getHostAddress(),
                player.getUniqueId(),
        };

        sendAlert("Caught %s cheating: %s.  IP: %s  UUID: %s", args);

        if (!isReady()) {
            return;
        }

        dispatchCommands(certain ? getCertainCommands() : getUncertainCommands(), args);
    }

    protected void dev(String message) {
        Player dev = getServer().getPlayer(devId);
        if (dev != null) {
            dev.sendMessage(":: " + message);
        }
    }

    protected void debugVerbose(String format, Object... args) {
        if (isVerboseDebugEnabled()) {
            debugVerbose(String.format(format, args));
        }
    }

    protected void debugVerbose(String message) {
        if (isVerboseDebugEnabled()) {
            println("[MCPerf:Heuristics:VDEBUG] " + message);
            dev(message);
        }
    }

    protected void debug(String format, Object... args) {
        if (isDebugEnabled()) {
            debug(String.format(format, args));
        }
    }

    protected void debug(String message) {
        if (isDebugEnabled()) {
            println("[MCPerf:Heuristics:DEBUG] " + message);
            dev(message);
        }
    }

    protected void info(String format, Object... args) {
        info(String.format(format, args));
    }

    protected void info(String message) {
        println("[MCPerf:Heuristics:INFO] " + message);
        dev(message);
    }

    private static void addBlocks(List<Block> blocks, World world, int height, int x, int y, int z) {
        for (int i = 0; i < height; i++) {
            Block block = world.getBlockAt(x, y + i, z);
            if (block != null) {
                blocks.add(block);
            }
        }
    }

    // This is oversimplified because players aren't 1x2 cylinders, but it'll do for now.
    private static List<Block> getOverlappingBlocks(Location location, int height, boolean wide) {
        List<Block> blocks = new LinkedList<>();

        boolean isSneaking = false;  // TODO: Minecraft 1.9
        @SuppressWarnings("ConstantConditions")
        double playerHeight = isSneaking ? 1.65 : 1.85;
        double playerWidth = 0.6;

        World world = location.getWorld();
        double xf = location.getX() - playerWidth / 2;
        double yf = location.getY();
        double zf = location.getZ() - playerWidth / 2;
        int x = (int) Math.floor(xf);
        int y = (int) Math.floor(yf);
        int z = (int) Math.floor(zf);

        addBlocks(blocks, world, height, x, y, z);

        boolean ox;
        boolean oy;
        boolean oz;
        if (wide) {
            ox = oy = oz = true;
        } else {
            ox = (double) x != Math.floor(xf + playerWidth);
            oy = (double) y != Math.floor(yf + playerHeight) && height > 0;
            oz = (double) z != Math.floor(zf + playerWidth);
        }

        if (height <= 0) {
            height = 1;
        }

        if (ox) {
            addBlocks(blocks, world, height, x + 1, y, z);

            if (oy) {
                addBlocks(blocks, world, height, x + 1, y + 1, z);

                if (oz) {
                    addBlocks(blocks, world, height, x + 1, y + 1, z + 1);
                }
            }

            if (oz) {
                addBlocks(blocks, world, height, x + 1, y, z + 1);
            }
        }

        if (oy) {
            addBlocks(blocks, world, height, x, y + 1, z);

            if (oz) {
                addBlocks(blocks, world, height, x, y + 1, z + 1);
            }
        }

        if (oz) {
            addBlocks(blocks, world, height, x, y, z + 1);
        }

        return blocks;
    }

    private static boolean isLiquid(Material material) {
        switch (material) {
            case WATER:
            case STATIONARY_WATER:
            case LAVA:
            case STATIONARY_LAVA:
                return true;

            default:
                return false;
        }
    }


    private class Detector {
        private WeakReference<Player> player;
        private Long lastTime = null;
        private int suspiciousHits = 0;
        private int misses = 0;
        private int blackmarks = 0;
        private Long lastBlackmarkTime = null;
        private List<Double> variancesH = new LinkedList<>();
        private List<Double> variancesV = new LinkedList<>();
        private Integer autosoupTask;
        private Double actualHealth = null;
        private Integer actualFoodLevel = null;
        private Float actualSaturation = null;
        private Long lastAutosoupCheck = null;
        private Long lastDamaged = null;
        private Double missDistance = null;
        private Double hitDistance = null;
        private int suspiciousAims = 0;
        private int farHits = 0;
        private int highSpeedAttacks = 0;
        private Long lastItemHeldTime = null;
        private int obviousFlyHacks = 0;
        private int suspiciousFlyHacks = 0;
        private Long firstInAir = null;
        private int inAirScore = 0;
        private double lastAirDeltaEV = 0;
        private Double lastAirAccelV = null;
        private Boolean lastAirAccelGoingUp = null;
        private int jumpingCount = 0;
        private int fallingCount = 0;
        private Long lastKnockbackTime = null;
        private int antiKnockbackCount = 0;
        private final long knockbackTimeout = 500;
        private final long consecutiveKnockbackTimeout = 300;
        private long lastClimbableTime = 0;
        private int strikes = 0;
        private long lastStrikeTime = 0;
        private Set<String> strikeReasonsDebug = new HashSet<>();
        private Set<String> strikeReasonsPublic = new HashSet<>();
        private Integer lastDeltaV4s = null;
        private int yesCheatPlusCount = 0;
        private int waterWalkCount = 0;
        private boolean teleported = false;
        private double cumulativeDistance = 0.0;
        private long cumulativeStart = 0;
        private long cumulativeLast = 0;
        private boolean cumulativeAfterCancelled = false;
        private Location cancelledMoveLocation = null;
        private Boolean lastSwimming = null;
        private int swimmingOscillations = 0;
        private double lastDeltaEV;
        private double lastDeltaEH;
        private double lastDistanceE;

        public Detector(Player player) {
            this.player = new WeakReference<>(player);
        }

        public void onTeleport() {
            teleported = true;
        }

        public void onCancelledMove(Location location) {
            cancelledMoveLocation = location;
        }

        public void onKnockback(Entity damager) {
            Player player = getPlayerIfEnabled();
            if (player == null) {
                return;
            }

            long now = System.currentTimeMillis();

            if (lastKnockbackTime != null) {
                long timeSinceKnockback = now - lastKnockbackTime;
                if (timeSinceKnockback > consecutiveKnockbackTimeout) {
                    debug("Anti-knockback +6: %d; %s; %d ms delay; subsequent knockback", antiKnockbackCount + 6, player.getName(), timeSinceKnockback);
                    onAntiKnockback(6, 1);
                }
            } else {
                Location pl = player.getLocation();
                Vector pv = pl.toVector();
                Vector dv = damager.getLocation().toVector();
                double distX = dv.getX() - pv.getX();
                double distZ = dv.getZ() - pv.getZ();
                double dist2 = distX * distX + distZ * distZ;

                if (dist2 < 0.0001) {
                    // Direction is random
                    return;
                }

                player.getVelocity();

                //float yaw = (float) (MathHelper.yaw(distZ, distX) * 180.0D / 3.1415927410125732D - (double) pl.getYaw());
                float dist = MathHelper.sqrt(dist2);
                float factor = 0.4F;
                Vector mot = player.getVelocity();
                double motX = mot.getX();
                double motY = mot.getY();
                double motZ = mot.getZ();
                motX /= 2.0D;
                motY /= 2.0D;
                motZ /= 2.0D;
                motX -= distX / (double) dist * (double) factor;
                motY += (double) factor;
                motZ -= distZ / (double) dist * (double) factor;
                if (motY > 0.4000000059604645D) {
                    motY = 0.4000000059604645D;
                }
                Vector change = new Vector(motX, motY, motZ).subtract(mot);
                mot.setX(motX);
                mot.setY(motY);
                mot.setZ(motZ);

                debug("Expected knockback for %s: %s", player.getName(), change.toString());

                // TODO: Account for attribute: generic.knockbackResistance

                lastKnockbackTime = now;
            }
        }

        private void onAntiKnockback(int inc, int id) {
            Player player = getPlayerIfEnabled();
            if (player == null) {
                return;
            }

            antiKnockbackCount += inc;

            if (antiKnockbackCount >= 24) {
                info("TRIGGERED anti-knockback#%d %d: %s", id, antiKnockbackCount, player.getName());
                //onCaughtCheating(player, String.format("anti-knockback#%d", id));
            }
        }


        private void strike(int count, String debugReason, String publicReason) {
            strike(count, debugReason, publicReason, false);
        }

        private void strike(int count, boolean certain, String debugReason, String publicReason) {
            strike(count, certain, debugReason, publicReason, false);
        }

        private void strike(int count, String debugReason, String publicReason, boolean skipReset) {
            strike(count, count >= 100, debugReason, publicReason, skipReset);
        }

        private void strike(int count, boolean certain, String debugReason, String publicReason, boolean skipReset) {
            Player player = getPlayer();
            if (player == null) {
                return;
            }

            long now = System.currentTimeMillis();

            if (!skipReset) {
                long sinceLast = now - lastStrikeTime;

                if (strikes > 0 && lastStrikeTime > 0 && sinceLast > 26_000) {
                    strikes = 0;
                    strikeReasonsDebug.clear();
                    strikeReasonsPublic.clear();
                    debug("Strikes reset: %d; %s", sinceLast, player.getName());
                }
            }

            lastStrikeTime = now;
            strikeReasonsDebug.add(debugReason);
            strikeReasonsPublic.add(publicReason);

            if (count > 0) {
                strikes += count;
                info("STRIKE +%d: %d; %s; %s", count, strikes, debugReason, player.getName());
            } else {
                debug("Null strike +%d: %d; %s; %s", count, strikes, debugReason, player.getName());
            }

            if (strikes >= 1_000) {
                info("STRUCK OUT: %s for %s", player.getName(), String.join(", ", strikeReasonsDebug));
                // TODO: Determine certainty
                onCaughtCheating(getPlayer(), certain, "hack client: " + String.join(", ", strikeReasonsPublic));
            }
        }

        @SuppressWarnings("ConstantConditions")  // TODO: Break method into multiple components
        public void onMove(double deltaH, double deltaV, double deltaEH, double deltaEV, Location from, Location to) {
            Player player = getPlayerIfEnabled();
            if (player == null) {
                return;
            }

            long now = System.currentTimeMillis();

            List<Block> blocks = getOverlappingBlocks(from, 2, false);
            List<Block> blocksBelow = getOverlappingBlocks(from.clone().subtract(0, 1, 0), 0, true);
            World world = from.getWorld();
            int x = from.getBlockX();
            int y = from.getBlockY();
            int z = from.getBlockZ();

            boolean jumping = false;
            boolean falling = false;
            boolean climbing = false;
            boolean swimming = false;
            boolean inAir = !isOnGround(player) && y >= 0;

            Block blockBelow = world.getBlockAt(x, y - 1, z);
            Material below = blockBelow == null ? Material.AIR : blockBelow.getType();
            if (below == null) {
                below = Material.AIR;
            }

            long climbableTimeDelta = now - lastClimbableTime;
            for (Block block : blocks) {
                switch (block.getType()) {
                    case LADDER:
                    case VINE:
                        climbing = true;
                        break;


                    case LAVA:
                    case STATIONARY_LAVA:
                        climbing = true;
                        swimming = true;
                        // Conservative guess
                        deltaEH *= 0.65;
                        deltaEV *= 0.65;
                        break;

                    case WATER:
                    case STATIONARY_WATER:
                        climbing = true;
                        swimming = true;
                        // Conservative guess
                        deltaEH *= 0.85;
                        deltaEV *= 0.85;
                        break;

                    case WEB:
                        climbing = true;  // TODO: Eliminate this by ignoring time in air
                        // This is approximate, but should be close.
                        deltaEH *= 0.15;
                        deltaEV *= 0.15;
                        break;
                }
            }
            if (climbing) {
                inAir = false;
                resetFlight();
                lastClimbableTime = now;
                debugVerbose("On climbable: %s", player.getName());
            } else if (climbableTimeDelta <= 800) {
                inAir = false;
                resetFlight();
                debugVerbose("Recently on climbable: %s", player.getName());
            }

            if (lastSwimming != null) {
                if (lastSwimming != swimming) {
                    swimmingOscillations++;

                    if (swimmingOscillations >= 6) {
                        info("Swimming oscillations +1: %d; %s", swimmingOscillations, player.getName());
                        strike(200, "water walk:oscillating", "water walk");
                    }
                } else if (swimmingOscillations != 0) {
                    swimmingOscillations = 0;
                }
            }
            lastSwimming = swimming;

            int deltaV4s = (int) (deltaV * 10000);
            int deltaEV4s = (int) (deltaEV * 10000);
            int deltaV4 = Math.abs(deltaV4s);
            int deltaEV4 = Math.abs(deltaEV4s);
            int deltaH4 = (int) (deltaH * 10000);
            int deltaEH4 = Math.max(0, (int) (deltaEH * 10000));
            Long preLastKnockbackTime = lastKnockbackTime;
            double distanceE = Math.sqrt(Math.pow(deltaEH, 2) + Math.pow(deltaEV, 2));

            if (lastDeltaEV != 0 || lastDeltaEH != 0) {

                if (player.getLocation().equals(cancelledMoveLocation)) {
                    cumulativeAfterCancelled = true;
                }

                if (now - cumulativeLast > 125 || cumulativeLast == 0) {  // Second one just to be safe
                    cumulativeStart = now;
                    cumulativeLast = now;
                    cumulativeDistance = distanceE;
                    cumulativeAfterCancelled = false;
                } else {
                    double lastCumulativeTime = cumulativeLast - cumulativeStart;
                    double lastSeconds = lastCumulativeTime / 1_000;
                    double lastBlocksPerSecond = cumulativeDistance / lastSeconds;
                    //double lastCumulativeLast = cumulativeLast;
                    double lastCumulativeDistance = cumulativeDistance;

                    cumulativeLast = now;
                    cumulativeDistance += distanceE;
                    double cumulativeTime = cumulativeLast - cumulativeStart;

                    if (lastCumulativeTime > 25 && lastCumulativeTime < 300 && lastDistanceE < 0.7) {
                        double seconds = cumulativeTime / 1_000;
                        double blocksPerSecond = cumulativeDistance / seconds;

                        if (cumulativeAfterCancelled && blocksPerSecond > 30) {
                            info("Blink after cancelled movement %s: %.2f over %.4f sec", player.getName(), blocksPerSecond, seconds);
                            strike(100, false, "blink:after cancelled", "blink/excessive lag");
                        }

                        if (blocksPerSecond > 30 && (deltaEH > deltaH || deltaEV > deltaV)) {
                            info("Evading slowness: %s; %.2f over %.3f sec", player.getName(), blocksPerSecond, seconds);
                            strike(200, "speed:anti-slow", "speed");
                            resetBlink();
                        } else if (blocksPerSecond < lastBlocksPerSecond) {
                            if (lastCumulativeDistance >= 7) {
                                boolean suspicious = deltaH == 0 && deltaV == 0;

                                if (blocksPerSecond > 300) {
                                    info("Extremely fast blink for %s: %.2f over %.4f sec", player.getName(), lastBlocksPerSecond, lastSeconds);
                                    strike(suspicious ? 100 : 0, false, "blink:extremely fast", "blink/excessive lag");
                                } else if (blocksPerSecond > 120) {
                                    if (suspicious) {
                                        info("Very fast blink for %s: %.2f over %.4f sec", player.getName(), lastBlocksPerSecond, lastSeconds);
                                        strike(suspicious ? 100 : 0, false, "blink:very fast", "blink/excessive lag");
                                    } else {
                                        // This seems to trigger a lot of false positives.
                                        debug("Very fast blink for %s: %.2f over %.4f sec", player.getName(), lastBlocksPerSecond, lastSeconds);
                                    }
                                } else if (blocksPerSecond > 80) {
                                    info("Fast blink for %s: %.2f over %.4f sec", player.getName(), lastBlocksPerSecond, lastSeconds);
                                    strike(suspicious ? 100 : 0, false, "blink:fast", "blink/excessive lag");
                                } else if (blocksPerSecond > 50) {
                                    if (suspicious) {
                                        info("Medium blink for %s: %.2f over %.4f sec", player.getName(), lastBlocksPerSecond, lastSeconds);
                                        strike(suspicious ? 100 : 0, false, "blink:medium", "blink/excessive lag");
                                    } else {
                                        debug("Medium blink for %s: %.2f over %.4f sec", player.getName(), lastBlocksPerSecond, lastSeconds);
                                        strike(0, false, "blink:medium", "blink/excessive lag");
                                    }
                                } else if (blocksPerSecond > 30) {
                                    debug("Short blink for %s: %.2f over %.4f sec", player.getName(), lastBlocksPerSecond, lastSeconds);
                                }
                            }

                            resetBlink();
                        }
                    }
                }
            }

            lastDistanceE = distanceE;
            lastDeltaEV = deltaEV;
            lastDeltaEH = deltaEH;

            if (lastKnockbackTime != null) {
                long timeSinceKnockback = now - lastKnockbackTime;

                if (timeSinceKnockback <= knockbackTimeout && deltaV4s <= 0) {
                    debug("Knockback grace period: %s", player.getName());
                } else {
                    lastKnockbackTime = null;

                    if (timeSinceKnockback <= knockbackTimeout && deltaV4s >= 0) {
                        if (antiKnockbackCount > 0) {
                            antiKnockbackCount--;
                            debug("Knockback check passed -1: %d; %s; %d ms delay; V: %.6f", antiKnockbackCount, player.getName(), timeSinceKnockback, deltaV);
                        } else {
                            debug("Received knockback: %d; %s; %d ms delay; V: %.6f", antiKnockbackCount, player.getName(), timeSinceKnockback, deltaV);
                        }
                    } else {
                        final int diff = 5;
                        debug("Anti-knockback +%d: %d; %s; %d ms delay; V: %.6f", diff, antiKnockbackCount + diff, player.getName(), timeSinceKnockback, deltaV);
                        onAntiKnockback(diff, 1);
                    }
                }
            }

            if (deltaH == 0.0 && deltaV == 0.0) {
                // Otherwise evasion is possible by rotating head often
                return;
            }

            if (lastDeltaV4s != null && deltaV4s != 0) {
                // deltaV4 == 1010 for flight
                String type = deltaV4 == 1010 ? "flight" : "speed";

                if (deltaV4s * -1 == lastDeltaV4s) {
                    if (deltaV4 == 784 || deltaEV4 == 784) {  // Something PvP-related; getting stuck?
                        debug("Player oscillating: %d; V: %.6f; EV: %.6f; %s", yesCheatPlusCount, deltaV, deltaEV, player.getName());
                    } else {
                        yesCheatPlusCount++;

                        if (deltaV4 == 1010 || yesCheatPlusCount >= 2) {
                            info("Wurst YesCheat+ %s +1: %d; %.6f; %s", type, yesCheatPlusCount, deltaV, player.getName());
                            strike(yesCheatPlusCount >= 4 ? 150 : 50, type + ":Wurst YesCheat+", type);
                        } else {
                            debug("Wurst YesCheat+ %s +1: %d; %.6f; %s", type, yesCheatPlusCount, deltaV, player.getName());
                        }
                    }
                }

                if (yesCheatPlusCount >= 5) {  // Seems to happen exactly 7 times for flight, plus initial
                    onCaughtCheating(player, true, type + " w/NCP evasion");
                }
            } else {
                yesCheatPlusCount = 0;
                if (yesCheatPlusCount > 0) {
                    debug("Reset Wurst YesCheat+: %d; %.6f; %s", yesCheatPlusCount, deltaV, player.getName());
                }
            }

            boolean overWater = !swimming && !climbing && isLiquid(below) && blocksBelow.stream().map(Block::getType).allMatch(HeuristicsManager::isLiquid);
            //if (!overWater) {
            //    debug("@@@ %d, %.6f; %s", y, from.getY(), String.join(" ", (Iterable<String>) blocksBelow.stream().map(Block::getType).map(Material::name)::iterator));
            //}

            if (inAir) {
                if (firstInAir == null) {
                    if (overWater) {
                        info("Jumping on water surface: %s", player.getName());
                        strike(250, "water walk:cold jump", "water walk");
                    }

                    firstInAir = now;
                    inAirScore = 1;
                    lastAirDeltaEV = deltaV;
                    lastAirAccelV = null;
                    lastAirAccelGoingUp = null;
                    debugVerbose("Entered airspace: %s; %s", player.getName(), overWater ? "over water" : "not over water");
                } else {
                    final int MAX_FALLING_COUNT = 4;
                    final int NORMAL_JUMPING_COUNT = 10;  // Often only 9

                    double accelV = deltaEV - lastAirDeltaEV;
                    int accelV4s = (int) (accelV * 10000);
                    @SuppressWarnings("unused")
                    int accelV4 = Math.abs(accelV4s);
                    long timeInAir = now - firstInAir;

                    if (overWater && accelV > 0 && climbableTimeDelta < 200) {
                        info("Skipping across water surface: %s", player.getName());
                        strike(250, "water walk:successive jump", "water walk");
                    }

                    if (lastAirAccelV != null) {
                        boolean airAccelGoingUp = accelV > lastAirAccelV;
                        if (lastAirAccelGoingUp != null && airAccelGoingUp != lastAirAccelGoingUp) {
                            if (jumpingCount > 1) {
                                debugVerbose("Ignoring expected vertical jerk from jump: %s", player.getName());
                            } else if (preLastKnockbackTime != null && now - preLastKnockbackTime < 600) {
                                debugVerbose("Ignoring expected vertical jerk from knockback: %s", player.getName());
                            } else {
                                if (inAirScore > 8) {
                                    inAirScore += 4;
                                    info("In-air +4 vertical jerk: %d; %d ms, %s", inAirScore, timeInAir, player.getName());
                                } else {
                                    debug("Ignoring in-air vertical jerk: %d; %d ms, %s", inAirScore, timeInAir, player.getName());
                                }
                            }
                        }
                        lastAirAccelGoingUp = airAccelGoingUp;
                    }

                    if (deltaV == 0) {
                        if (accelV == 0.0 && lastAirAccelV != null && lastAirAccelV == 0.0) {
                            inAirScore += 8;
                            info("In air, no vertical velocity +8: %d; %d ms; %s", inAirScore, timeInAir, player.getName());
                            strike(200, "flight:no vert velocity", "flight");
                        }
                    } else if (accelV4 == 0 && deltaV4s < 0 && deltaV4 < 1_0000) {  // Wurst glides at -0.1250 blocks/sec, 0 blocks/sec^2
                        if (deltaV4s == -1250) {
                            inAirScore += 16;
                            info("Gliding down at Wurst speed +16: %d; %d ms, %.6f blocks/sec; %.6f blocks/sec^2; %s", inAirScore, timeInAir, deltaV, accelV, player.getName());
                            strike(200, "glide:Wurst", "glide");
                        } else if (deltaV4s == -980 || deltaV4s == -784) {
                            debug("Ignoring glide due to key velocity: %d; %d ms, %.6f blocks/sec; %.6f blocks/sec^2; %s", inAirScore, timeInAir, deltaV, accelV, player.getName());
                        } else if (teleported) {
                            debug("Ignoring glide due to recent teleport: %d; %d ms, %.6f blocks/sec; %.6f blocks/sec^2; %s", inAirScore, timeInAir, deltaV, accelV, player.getName());
                        } else {
                            inAirScore += 8;
                            if (inAirScore > 8) {
                                info("Gliding down +8: %d; %d ms, %.6f blocks/sec; %.6f blocks/sec^2; %s", inAirScore, timeInAir, deltaV, accelV, player.getName());
                                strike(100, "glide:generic", "glide");
                            } else {
                                debug("Gliding down +8: %d; %d ms, %.6f blocks/sec; %.6f blocks/sec^2; %s", inAirScore, timeInAir, deltaV, accelV, player.getName());
                            }
                        }
                    } else if (accelV4 == 4020) {
                        if (inAirScore > 2) {
                            inAirScore += 8;
                            info("Vertical accel Metro +8: %d; %d ms; %s", inAirScore, timeInAir, player.getName());
                            strike(0, "flight:vert accel metro", "flight");
                        } else {
                            inAirScore++;
                            debug("Vertical accel Metro +1: %d; %d ms; %s", inAirScore, timeInAir, player.getName());
                        }
                    } else if (accelV4 == 3332) {
                        if (inAirScore > 0) {
                            //inAirScore += 3;
                            debug("Vertical accel Huzuni (disabled): %d; %d ms; %s", inAirScore, timeInAir, player.getName());
                        } else {
                            debug("Ignoring vertical accel Huzuni: %d; %d ms; %s", inAirScore, timeInAir, player.getName());
                        }
                    } else if (accelV4s > 0 && lastAirAccelV != null && lastAirAccelV > 0.0) {
                        if (preLastKnockbackTime != null && now - preLastKnockbackTime < 600) {
                            debug("Ignoring expected upwards accel from knockback: %s", player.getName());
                        } else if (inAirScore > 4) {
                            inAirScore += 2;
                            info("Upwards accel (unnatural) +2: %d; %d ms; %s", inAirScore, timeInAir, player.getName());
                            strike(0, "flight:unnatural upwards accel", "flight");
                        } else {
                            debug("Ignoring upwards accel: %d; %d ms; %s", inAirScore, timeInAir, player.getName());
                        }
                    } else if (jumpingCount <= 0 && (accelV4s == -752 || accelV4s == -1490 || accelV4s == -2213)) {
                        falling = true;
                        fallingCount++;

                        if (fallingCount <= 1 && JUMP_ACCELS.contains(accelV4s)) {
                            // Collision for -752, at minimum.
                            jumping = true;
                            jumpingCount++;
                            debug("Jumping %d/%d+ or falling %d/%d: %s; %.6f blocks/sec^2", jumpingCount, NORMAL_JUMPING_COUNT, fallingCount, MAX_FALLING_COUNT, player.getName(), accelV);
                        } else {
                            // Occasionally one is repeated, so > 4
                            debug("Falling%s %d/%d: %s; %.6f blocks/sec^2", fallingCount > MAX_FALLING_COUNT ? " (suspicious)" : "", fallingCount, MAX_FALLING_COUNT, player.getName(), accelV);
                            // TODO: As hack clients get smarter, we'll likely need to kick when fallingCount > some number
                        }
                    } else if (JUMP_ACCELS.contains(accelV4s)) {
                        jumping = true;
                        jumpingCount++;
                        // Normally not all elements are used, so there's a bit of padding here
                        //debug("Jumping%s %d/%d+: %s; %.6f blocks/sec^2", jumpingCount > NORMAL_JUMPING_COUNT ? " (extra-high)" : "", jumpingCount, NORMAL_JUMPING_COUNT, player.getName(), accelV);
                        // TODO: As hack clients get smarter, we'll likely need to kick when jumpingCount > some number
                    } else if (deltaV4s < 0) {
                        if (inAirScore > 0) {
                            inAirScore--;
                            if (inAirScore > 1 || obviousFlyHacks > 1 || suspiciousFlyHacks > 1) {
                                debug("Falling -1: %d; %d ms, %.6f blocks/sec; %.6f blocks/sec^2; %s", inAirScore, timeInAir, deltaV, accelV, player.getName());
                            } else {
                                debug("Falling -1: %d; %d ms, %.6f blocks/sec; %.6f blocks/sec^2; %s", inAirScore, timeInAir, deltaV, accelV, player.getName());
                            }
                        } else {
                            debug("Falling: %d ms, %.6f blocks/sec; %.6f blocks/sec^2; %s", timeInAir, deltaV, accelV, player.getName());
                        }
                    } else if (deltaV4s > 0 && accelV4s > 0 && lastAirAccelV != null && lastAirAccelV > 0.0) {
                        fallingCount++;
                        falling = true;
                        if (jumpingCount > 1) {
                            jumpingCount++;
                            jumping = true;
                        }

                        inAirScore += 8;
                        info("Falling but slowing down +8: %d; %d ms, %.6f blocks/sec; %.6f blocks/sec^2; %s", inAirScore, timeInAir, deltaV, accelV, player.getName());
                        strike(50, "flight:decel on fall", "flight");
                    } else if (deltaV4s > 0) {
                        debug("Rising: %d; %d ms; %.6f blocks/sec; %.6f blocks/sec^2; %s", inAirScore, timeInAir, deltaV, accelV, player.getName());
                    }

                    if (timeInAir >= 600 && inAirScore >= 24) {  // TODO: Use packet count as well as time; helps prevent lag
                        onCaughtCheating(player, false, "flight");
                    } else if (timeInAir > 6_000 && deltaV4s >= 0 && accelV4s >= 0 && lastAirAccelV != null && lastAirAccelV >= 0 && deltaH4 > 0) {
                        info("In air too long: %s; %d ms", player.getName(), timeInAir);
                        // This seems to have a lot of false positives on kitpvp if the time is too short
                        onCaughtCheating(player, false, "flight/floating");
                    }

                    if (inAirScore >= 2) {
                        strike(0, "flight:inAirScore", "flight", timeInAir >= 4_000);
                    }

                    lastAirAccelV = accelV;
                }
            } else if (firstInAir != null) {
                debug("Touched ground: %s", player.getName());
                resetFlight();
            }

            if (!falling && fallingCount != 0) {
                fallingCount = 0;
            }
            if (!jumping && jumpingCount != 0) {
                jumpingCount = 0;
            }

            if (!inAir && overWater && deltaV4 == 0 && lastDeltaV4s != null && lastDeltaV4s == 0 && (double) y == from.getY()) {
                if (waterWalkCount++ > 1) {
                    info("Walking on water +1: %d; %s", waterWalkCount, player.getName());
                    strike(250, "water walk:float", "water walk");
                }
            } else if (waterWalkCount > 0) {
                waterWalkCount = 0;
            }

            int fracV4s = deltaV4s % 1_0000;
            if (deltaH4 < 200 && deltaV4 >= 2_0000 && (fracV4s == 0 || fracV4s == -1142 || fracV4s == 8857)) {
                int step = deltaV > 0 ? 1 : -1;
                int max = to.getBlockY();
                int start = y + step;
                for (int iy = start; step > 0 ? iy < max : iy > max; iy += step) {
                    Block block = world.getBlockAt(x, iy, z);
                    if (block == null) {
                        continue;
                    }

                    Material material = block.getType();
                    if (material == null) {
                        continue;
                    }

                    if (material.isSolid()) {
                        info("NO-CLIP: %s; H:%.6f V:%.6f; (%s, %d, %d, %d); %s", player.getName(), deltaH, deltaV, world.getName(), x, iy, z, material.name());
                        onCaughtCheating(player, true, "no-clip through " + material.name().toLowerCase().replace('_', ' '));
                    }
                }
            }

            if (deltaH4 == 0 && deltaV4 == 1_0000) {  // Wurst
                obviousFlyHacks += 4;
                info("Obvious flight hack (Wurst flight) for %s +4: %d", player.getName(), obviousFlyHacks);
                strike(200, "flight:obvious Wurst", "flight");
            } else if (deltaV4 == 3750) {  // Metro, but PvP also seems to trigger this, so no strike
                obviousFlyHacks += 2;
                info("Obvious flight hack (Metro flight) for %s +4: %d; %.6f", player.getName(), obviousFlyHacks, deltaV);
                //strike(25, "flight:obvious Metro", "flight");
            } else if (deltaV4 == 3749) {  // Huzuni
                obviousFlyHacks += 4;
                info("Obvious flight hack (Huzuni flight) for %s +4: %d; %.6f", player.getName(), obviousFlyHacks, deltaV);
                strike(25, "flight:obvious Huzuni", "flight");
            } else if (deltaH4 == 9183 || deltaH4 == 10014) {  // Wurst speed hacks
                obviousFlyHacks += 2;
                info("Obvious speed hack (Wurst speed) for %s +1: %d; %.6f", player.getName(), obviousFlyHacks, deltaH);
                strike(25, "speed:obvious Wurst", "speed");
            } else if (deltaEV4s > 7200) {
                info("Suspicious vertical movement; not decrementing fly hacks: %s; EH: %.6f; EV: %.6f", player.getName(), deltaEH, deltaEV);
                strike(25, "flight:suspicious", "flight");
            } else if (deltaEH4 > 6000 && deltaEV4 != 4199 && deltaEV4 != 4200) {  // 0.42 is jump while sprinting
                if (obviousFlyHacks > 0) {
                    info("Suspicious horizontal movement; not decrementing fly hacks: %s; EH: %.6f; EV: %.6f", player.getName(), deltaEH, deltaEV);
                }
                // Knockback II triggers this
                //strike(0, "speed:suspicious", "speed");
            } else if (obviousFlyHacks > 0) {
                obviousFlyHacks--;
                debug("Decremented obvious fly hack movements for %s -1: %d", player.getName(), obviousFlyHacks);
            }

            if (deltaH4 == 9800) {  // Wurst
                suspiciousFlyHacks += 160;
                info("Suspicious horizontal speed (Wurst speed) for %s +160: %d; V: %.6f", player.getName(), suspiciousFlyHacks, deltaV);
                strike(200, "speed:suspicious", "speed");
            } else if (deltaV4 == 1_0100) {  // Wurst with YesCheat+ enabled
                // Previously had Huzuni here.  Is it both, or was one of them a copypasta error?
                suspiciousFlyHacks += 240;
                info("Wurst YesCheat+ upward movement for %s +240: %d; V: %.6f", player.getName(), suspiciousFlyHacks, deltaV);
                strike(100, "flight:suspicious Wurst", "flight");
            } else if (deltaEV4s >= 2_0000) {
                suspiciousFlyHacks += 240;
                info("Very fast upwards movement for %s: %d; EV: %.6f", player.getName(), suspiciousFlyHacks, deltaEV);
                strike(200, "flight:very fast", "flight");
            } else if (deltaEV4s > 1_1200) {
                // 1.1200 and 1.0192 seem to be PvP-related; knockback, maybe?  Sometimes 1.1084 instead of 1.1200.
                suspiciousFlyHacks += 80;
                info("Moderately fast upwards movement for %s: %d; EV: %.6f", player.getName(), suspiciousFlyHacks, deltaEV);
                strike(25, "flight:moderately fast", "flight");
            } else if (deltaEH4 >= 3_0000) {
                suspiciousFlyHacks += 160;
                info("Very fast horizontal movement for %s +160: %d; EH: %.6f", player.getName(), suspiciousFlyHacks, deltaEH);
                strike(100, "flight:very fast", "flight");
            } else if (deltaEH4 >= 1_2000) {
                // Knockback II triggers this
                suspiciousFlyHacks += 20;
                info("Moderately fast horizontal movement for %s +20: %d; EH: %.6f", player.getName(), suspiciousFlyHacks, deltaEH);
                //strike(25, "speed:moderately fast", "speed");
            } else if (deltaEH4 >= 9800) {  // Wurst
                if (deltaV4 == 0) {
                    //suspiciousFlyHacks += 20;
                    debug("Slightly fast and suspicious horizontal movement for %s +0: %d; EH: %.6f", player.getName(), suspiciousFlyHacks, deltaEH);
                    //strike(25, "speed:suspicious slightly fast horiz", "speed");
                } else {
                    //suspiciousFlyHacks += 20;
                    debug("Ignoring slightly fast horizontal movement for %s: %d; %.6f", player.getName(), suspiciousFlyHacks, deltaH);
                    //strike(10, "speed:slightly fast horiz", "speed");
                }
            } else if (suspiciousFlyHacks > 0 && obviousFlyHacks <= 0) {
                suspiciousFlyHacks -= 4;
                debug("Decremented suspicious movements for %s -4: %d", player.getName(), suspiciousFlyHacks);
            }

            if (obviousFlyHacks >= 12) {
                onCaughtCheating(player, false, "fly hacks");
            }
            if (suspiciousFlyHacks >= 640) {
                onCaughtCheating(player, false, "fly/speed hacks");
            }

            lastDeltaV4s = deltaV4s;
        }

        public void onKilled(@SuppressWarnings("UnusedParameters") Player killer) {
            Player player = getPlayerIfEnabled();
            if (player == null) {
                return;
            }

            scheduleAutosoupTask();
        }

        private void scheduleAutosoupTask() {
            Player player = getPlayerIfEnabled();
            if (player == null) {
                return;
            }

            if (autosoupTask != null) {
                getServer().getScheduler().cancelTask(autosoupTask);
                autosoupTask = null;
            }

            //autosoupTask = getServer().getScheduler().scheduleSyncDelayedTask(getPlugin(), this::checkAutosoup, 2 * 20);
        }

        @SuppressWarnings("unused")
        public void checkAutosoup() {
            autosoupTask = null;

            Player player = getPlayerIfEnabled();
            if (player == null) {
                return;
            }

            if (player.getHealth() >= player.getMaxHealth() / 3) {
                long time = System.currentTimeMillis();
                if (lastDamaged == null || time - lastDamaged > 2000) {
                    pushState(time);
                    getServer().getScheduler().scheduleSyncDelayedTask(getPlugin(), () -> {
                        if (player.isOnline() && actualHealth != null) {
                            popState();
                        }
                    }, 1);
                } else {
                    scheduleAutosoupTask();
                }
            }
        }

        public void close() {
            popState();

            if (autosoupTask != null) {
                getServer().getScheduler().cancelTask(autosoupTask);
            }

            player.clear();
            player = null;
        }

        @SuppressWarnings("unused")
        private void pushState() {
            pushState(System.currentTimeMillis());
        }

        private void pushState(long time) {
            Player player = getPlayerIfEnabled();
            if (player == null) {
                return;
            }

            lastAutosoupCheck = time;
            actualHealth = player.getHealth();
            actualFoodLevel = player.getFoodLevel();
            actualSaturation = player.getSaturation();
            player.setHealth(1.0);
            player.setFoodLevel(1);
            player.setSaturation(0.5f);
        }

        private void popState() {
            Player player = getPlayer();
            if (player == null) {
                return;
            }

            if (actualHealth == null) {
                return;
            }

            player.setHealth(actualHealth);
            player.setFoodLevel(actualFoodLevel);
            player.setSaturation(actualSaturation);

            actualHealth = null;
            actualFoodLevel = null;
            actualSaturation = null;
        }

        public void onDamaged() {
            Player player = getPlayerIfEnabled();
            if (player == null) {
                return;
            }

            lastDamaged = System.currentTimeMillis();

            if (lastAutosoupCheck != null) {
                lastAutosoupCheck = null;

                if (actualHealth != null) {
                    popState();
                }
            }
        }

        public void onEating() {
            Player player = getPlayerIfEnabled();
            if (player == null) {
                return;
            }

            if (lastAutosoupCheck != null) {
                long now = System.currentTimeMillis();
                long sinceEating = lastItemHeldTime == null ? -1 : now - lastItemHeldTime;
                long delay = now - lastAutosoupCheck;
                debug("Eating delay: %d ms; since eating: %d ms", delay, sinceEating);

                if (sinceEating < 1000) {
                    return;
                }

                if (delay < 250) {
                    //onCaughtCheating(player, "autosoup");
                    getLogger().log(Level.INFO, String.format("%s appears to be using autosoup (this test is inaccurate) (delay: %d ms; since eating: %d ms)", player.getName(), delay, sinceEating));
                }

                lastAutosoupCheck = null;
            }

            debug("Eating, but autosoup check is null");
        }

        private double getDistance(Player from, Player to) {
            return from.getEyeLocation().distance(to.getEyeLocation());
        }

        @SuppressWarnings("unused")
        private double getDistanceBuggy(Player from, Player to) {
            Location a = from.getEyeLocation();
            Location b = to.getEyeLocation();
            Vector direction = a.getDirection().normalize();

            List<Double> possibleDistances = new ArrayList<>(3);
            if (a.getX() != b.getX()) {
                possibleDistances.add(direction.clone().multiply((b.getX() - a.getX()) / direction.getX()).length());
            }
            if (a.getZ() != b.getZ()) {
                possibleDistances.add(direction.clone().multiply((b.getZ() - a.getZ()) / direction.getZ()).length());
            }
            if (a.getY() != b.getY()) {
                possibleDistances.add(direction.clone().multiply((b.getY() - a.getY()) / direction.getY()).length());
            }

            if (possibleDistances.isEmpty()) {
                return 0;
            }

            @SuppressWarnings("OptionalGetWithoutIsPresent")
            double distance = possibleDistances.stream().filter(n -> n > 0).map(Math::abs).min(Double::compare).get();
            Vector estimator = direction.clone().setY(0).normalize();
            distance -= Math.min(0.5, Math.min(Math.abs(estimator.getX()), Math.abs(estimator.getZ())) * 1.0);
            distance -= 0.45;

            return distance;
        }

        private void markGotHit(Entity entity) {
            resetAttack();
            onKnockback(entity);
        }

        public void markMiss(boolean air) {
            Player player = getPlayerIfEnabled();
            if (player == null) {
                return;
            }

            misses++;

            List<Entity> nearby = player.getNearbyEntities(7, 7, 7);
            Double distance = null;
            Player nearest = null;
            for (Entity entity : nearby) {
                if (!(entity instanceof Player)) {
                    return;
                }

                Player target = (Player) entity;
                double d = getDistance(player, target);
                if (distance == null || d < distance) {
                    distance = d;
                    nearest = target;
                }
            }

            if (nearest == null) {
                debug("%s swatted at air", player.getName());
                air = false;  // Indicating that we're not hitting the air near a player
            } else {
                debug("%s missed %s (%01.3f)", player.getName(), nearest.getName(), distance);

                if (hitDistance != null && distance > 0 && (double) hitDistance == distance) {
                    suspiciousAims++;
                    hitDistance = null;

                    if (suspiciousAims >= 8) {
                        onCaughtCheating(player, true, "aimbot (miss)");  // Particularly Kryptonite and Reflex
                    }
                }

                missDistance = distance;
                if (misses > 3) {
                    suspiciousAims = 0;
                }
            }

            update(air);
        }

        public void resetAttackSpeed() {
            suspiciousHits = 0;
            misses = 0;
            highSpeedAttacks = 0;
        }

        public void resetAttack() {
            resetAttackSpeed();

            missDistance = null;
            suspiciousAims = 0;
            farHits = 0;
            hitDistance = null;
        }

        public void resetFlight() {
            teleported = false;

            firstInAir = null;
            inAirScore = 0;
            lastAirDeltaEV = 0;
            lastAirAccelV = null;
            lastAirAccelGoingUp = null;
            fallingCount = 0;
            jumpingCount = 0;
        }

        public void resetFlightHistory() {
            resetFlight();

            suspiciousFlyHacks = 0;
            obviousFlyHacks = 0;
        }

        public void resetBlink() {
            cumulativeLast = 0;
            cumulativeStart = 0;
            cumulativeDistance = 0;
        }

        public void resetMovement() {
            resetFlightHistory();
            resetBlink();

            lastDeltaV4s = null;
            yesCheatPlusCount = 0;
            lastClimbableTime = 0;
            cancelledMoveLocation = null;
        }

        public void resetKnockback() {
            antiKnockbackCount = 0;
            lastKnockbackTime = null;
        }

        public void resetStrikes() {
            strikes = 0;
            lastStrikeTime = 0;
            strikeReasonsDebug.clear();
            strikeReasonsPublic.clear();
        }

        public void reset() {
            resetAttack();
            resetMovement();
            resetKnockback();
            resetStrikes();
        }

        public void onAttackSpeedTainted() {
            resetAttackSpeed();
        }

        private void update(boolean hitOrAir) {
            Player player = getPlayerIfEnabled();
            if (player == null) {
                return;
            }

            long time = System.currentTimeMillis();
            Long deltaTime = lastTime == null || lastTime > time ? null : time - lastTime;
            if (deltaTime != null) {
                if (deltaTime > 1750) {
                    debug("Initial attack: %d ms", deltaTime);
                    resetAttack();
                } else if (deltaTime > 75) {
                    debug("Normal attack: %d ms", deltaTime);
                    resetAttackSpeed();
                } else if (hitOrAir) {
                    highSpeedAttacks++;

                    if (highSpeedAttacks >= 5) {
                        info("High-speed attack %d: %s; %d ms", highSpeedAttacks, player.getName(), deltaTime);
                        strike(50, "attack speed", "attack speed");
                    } else {
                        debug("High-speed attack %d: %s; %d ms", highSpeedAttacks, player.getName(), deltaTime);
                    }

                    if (highSpeedAttacks >= 10) {
                        onCaughtCheating(player, false, "killaura/speed attack/lag");
                    }
                }
            }

            lastTime = time;
        }

        @SuppressWarnings({"deprecation", "RedundantCast"})
        public void markHit(Player target) {
            Player player = getPlayerIfEnabled();
            if (player == null) {
                return;
            }

            // Variance: How far from the eye point the hit occurred.
            Location a = player.getEyeLocation();
            Location b = target.getEyeLocation();
            double distance = getDistance(player, target);
            Vector direction = a.getDirection().normalize();
            Vector lookingAt = a.toVector().add(direction.multiply(distance));
            Vector variance = lookingAt.subtract(b.toVector());
            double varianceH = Math.sqrt(Math.pow(variance.getX(), 2) + Math.pow(variance.getZ(), 2));
            double varianceV = variance.getY();
            variancesH.add(varianceH);
            variancesV.add(varianceV);
            if (variancesH.size() > 10) {
                variancesH.remove(0);
            }
            if (variancesV.size() > 10) {
                variancesV.remove(0);
            }
            @SuppressWarnings("OptionalGetWithoutIsPresent")
            double vMinH = variancesH.stream().min(Double::compare).get();
            @SuppressWarnings("OptionalGetWithoutIsPresent")
            double vMaxH = variancesH.stream().max(Double::compare).get();
            @SuppressWarnings("OptionalGetWithoutIsPresent")
            double vMinV = variancesV.stream().min(Double::compare).get();
            @SuppressWarnings("OptionalGetWithoutIsPresent")
            double vMaxV = variancesV.stream().max(Double::compare).get();
            double vvH = vMaxH - vMinH;
            double vvV = vMaxV - vMinV;

            if (misses >= getMissThreshold()) {
                suspiciousHits++;
                misses = 0;
            }

            if (missDistance != null) {
                if (distance > 0 && (double) missDistance == distance) {
                    suspiciousAims++;
                }
                missDistance = null;
            }

            if (distance >= 6.2) {
                farHits += 8;
                info("Very far hit +4: %d; %.6f blocks; %s", farHits, distance, player.getName());
                strike(100, "reach:very far", "reach");
            } else if (distance >= 5.7) {  // With lag, players get up to 5.69 at times.
                farHits += 4;
                if (farHits > 4) {
                    info("Moderately far hit +2: %d; %.6f blocks; %s", farHits, distance, player.getName());
                    strike(50, "reach:moderately far", "reach");
                } else {
                    debug("Moderately far hit +2: %d; %.6f blocks; %s", farHits, distance, player.getName());
                }
            }
            if (distance >= 4.2) {
                farHits += 2;
                debug("Slightly far hit +0: %d; %.6f blocks; %s", farHits, distance, player.getName());
                //strike(10, "reach:slightly far", "reach");
            } else if (farHits > 0) {
                farHits--;
            }

            if (isDebugEnabled()) {
                double v = Math.sqrt(Math.pow(varianceH, 2) + Math.pow(varianceV, 2));
                double vMeanH = variancesH.stream().collect(Collectors.averagingDouble(Double::doubleValue));
                double vMeanV = variancesV.stream().map(Math::abs).collect(Collectors.averagingDouble(Double::doubleValue));

                debug("Hit by %s: #%d;  dist:%01.3f;  varH:%01.3f varV:%01.3f var:%01.3f;  vvH:%01.3f vvV:%01.3f;  far:%d aims:%d; vMeanH:%01.3f vMeanV:%01.3f%s",
                        getPlayer().getName(),
                        variancesH.size(),
                        distance,
                        varianceH,
                        varianceV,
                        v,
                        vvH,
                        vvV,
                        farHits,
                        suspiciousAims,
                        vMeanH,
                        vMeanV,
                        player.isOnGround() && !player.isFlying() ? "" : ";  jumping"
                );
            }

            update(true);

            if (suspiciousAims >= 6) {
                onCaughtCheating(player, true, "aimbot (hit)");  // Particularly Kryptonite and Reflex
            }

            if (suspiciousHits >= 3) {
                giveBlackmarks(1);
            }

            if (farHits >= 48) {
                onCaughtCheating(player, false, "reach hack/excessive lag");
            }
        }

        public void giveBlackmarks(int count) {
            Player player = getPlayerIfEnabled();
            if (player == null) {
                return;
            }

            debug("%s got %d blackmark(s); total blackmarks: %d", player.getName(), count, blackmarks);
            reset();

            long time = System.currentTimeMillis() / 1000L;
            if (lastBlackmarkTime != null && lastBlackmarkTime < time && time - lastBlackmarkTime >= getBlackmarksTimeout()) {
                blackmarks = 0;
            } else {
                blackmarks += count;
            }

            if (blackmarks >= getMaxBlackmarks()) {
                onCaughtCheating(player, true, "killaura/auto-click");
            }
        }

        public Player getPlayer() {
            return player.get();
        }

        @SuppressWarnings("unused")
        public boolean isEnabled() {
            return getPlayerIfEnabled() != null;
        }

        public Player getPlayerIfEnabled() {
            Player player = this.player.get();

            if (player == null || !player.isOnline()) {
                return null;
            }

            // !!!!!!
            if (player.getUniqueId().equals(devId)) {
                return null;
            }

            GameMode mode = player.getGameMode();
            switch (mode) {
                case ADVENTURE:
                case SURVIVAL:
                    return player;

                case CREATIVE:
                case SPECTATOR:
                    return null;

                default:
                    getLogger().warning(String.format("Unrecognized game mode: %s", mode));
                    return null;
            }
        }

        public void forgiveBlackmarks(int count) {
            Player player = getPlayer();
            if (player == null) {
                return;
            }

            if (blackmarks > 0) {
                blackmarks = Math.max(0, blackmarks - count);
                info("Forgiven %d blackmark(s): %d; %s", count, blackmarks, player.getName());
            }

            resetAttack();
        }

        public void onItemHeld() {
            lastItemHeldTime = System.currentTimeMillis();
        }
    }
}
