package net.civmc.shards.paper.border;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * The border as a surveyed line: a strip laid along the ground, with a post standing every few
 * blocks of it.
 *
 * <p>Here because {@link GlassBorderRenderer} draws the truth in a misleading shape. A continuous
 * pane four blocks tall is what a wall looks like, and a shard border is not a wall - most of them
 * can be walked straight through, and the ones that cannot are closed by a neighbour being down
 * rather than by anything standing there. Somebody who reads the border as solid does not try it.</p>
 *
 * <p>So the line is drawn the way a boundary is marked on the ground rather than the way a fence is
 * built on it: an ankle-high strip you can see running away in both directions, and stakes at
 * intervals tall enough to pick out from a distance and through a hill. Nothing spans the gap between
 * two stakes above the ground, which is the whole point - the eye reads a line, not a barrier.</p>
 *
 * <p>The strip is unbroken. Ground steps, and each length of it stands on its own floor, so on a
 * slope or a stair the neighbouring lengths sit at different heights with nothing between them - a
 * row of dashes, which is not what a line is. Where two of them differ a riser is drawn at the
 * boundary they share, joining the lower to the upper, so the line goes up and down the terrain
 * instead of coming apart on it.</p>
 *
 * <p>Like the panes, these are display entities and they glow, so the line is drawn through whatever
 * is in front of it. That matters more here than it did there, because there is much less of it to
 * see: digging towards a border, running a tunnel beside one, or standing in a building built over
 * one are exactly the places where the first you would otherwise know of it is being stopped.</p>
 *
 * <p>Everything is per player, for the same reason as the panes: displays are real entities and
 * would otherwise be shown to everybody, including people on the far side who are not being told
 * anything.</p>
 */
public final class MarkerBorderRenderer implements BorderRenderer {

    // Drawn out to here, kept out to the wider radius below, so that standing on the boundary and
    // shifting about does not spawn and remove the same marker repeatedly
    private static final int SPAWN_RADIUS = 12;
    private static final int KEEP_RADIUS = 16;
    // The most faces one player has drawn, spent on the nearest, and the ceiling on the pieces those
    // faces add up to. Two numbers because a face is worth between one piece and three - a length of
    // line always, a post every so often, a riser wherever the ground steps - and capping the faces
    // is what keeps the line continuous as far as it is drawn at all. Faces are handed over well past
    // either so the ones inside the spawn radius are never crowded out by ones in the keep band
    private static final int MAX_FACES = 48;
    private static final int MAX_MARKERS = 96;
    private static final int HANDED_OVER = 160;

    // A post every this many blocks along the run, counted in world coordinates rather than from the
    // player, so the posts stand in the same places however you approach them - and so the two sides
    // of one seam agree about where they are
    private static final int POST_SPACING = 4;

    // Ankle high and no more. High enough to be seen over grass, low enough that nobody reads it as
    // something to climb
    private static final float LINE_HEIGHT = 0.12F;
    private static final float LINE_WIDTH = 0.12F;
    // Head height, near enough. A post is what you sight along to see where the border runs, so it
    // has to clear a wall or a hedge, and it must not read as half of a fence
    private static final float POST_HEIGHT = 2.5F;
    private static final float POST_THICKNESS = 0.16F;
    // What hangs below the line where a post has no floor under it. Half, because it is there to say
    // which way the ground is rather than to be a second post
    private static final float UNDER_HEIGHT = POST_HEIGHT / 2.0F;

    // What the client uses to decide a marker is off screen. Left at the entity's own size, one
    // scaled well past it is culled while plainly in view
    private static final float CULLING_WIDTH = 2.0F;
    private static final float CULLING_HEIGHT = POST_HEIGHT + 2.0F;
    // Entity render distance is a client setting that this multiplies against, so it is set well past
    // the window rather than trimmed to it
    private static final float VIEW_RANGE = 1.0F;
    // Fully lit, so a border is the same colour at midnight and down a hole as it is at noon
    private static final Display.Brightness FULLY_LIT = new Display.Brightness(15, 15);
    // Long enough to read as growing rather than blinking, short enough to finish inside one pass of
    // the timer that drives this
    private static final int FADE_TICKS = 4;

    private static final BlockData CROSSABLE_BLOCK = Material.LIGHT_BLUE_STAINED_GLASS.createBlockData();
    private static final BlockData CLOSED_BLOCK = Material.RED_STAINED_GLASS.createBlockData();
    // The outline colours, matched to the block so a marker seen through rock and the same marker
    // seen in the open are recognisably the one thing
    private static final Color CROSSABLE_GLOW = Color.fromRGB(120, 200, 255);
    private static final Color CLOSED_GLOW = Color.fromRGB(255, 90, 90);

    private final JavaPlugin plugin;
    private final Map<UUID, Map<Marker, Standing>> shown = new HashMap<>();
    // Every marker this has ever put in the world and not yet taken out, including the ones part way
    // through fading. A display that outlives the plugin is litter nothing else will ever clean up
    private final Set<BlockDisplay> live = new HashSet<>();

    public MarkerBorderRenderer(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int radius() {
        return KEEP_RADIUS;
    }

    @Override
    public int limit() {
        return HANDED_OVER;
    }

    @Override
    public void show(final Player player, final Location at, final List<DrawnFace> faces) {
        if (faces.isEmpty() && !this.shown.containsKey(player.getUniqueId())) {
            // Which is almost everybody, almost all the time
            return;
        }
        final Map<Marker, Boolean> wanted = wanted(at.getWorld(), at.getBlockY(), faces);
        final Map<Marker, Standing> standing =
            this.shown.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>());

        for (final Iterator<Map.Entry<Marker, Standing>> entries = standing.entrySet().iterator();
             entries.hasNext(); ) {
            final Map.Entry<Marker, Standing> entry = entries.next();
            final Boolean stillWanted = wanted.get(entry.getKey());
            final BlockDisplay display = entry.getValue().display();
            if (stillWanted == null || !display.isValid() || !display.getWorld().equals(at.getWorld())) {
                entries.remove();
                fadeOut(entry.getKey(), display);
                continue;
            }
            if (stillWanted != entry.getValue().crossable()) {
                // One that has only changed colour is recoloured where it stands. Replacing it would
                // mean a despawn and a respawn at every marker at once whenever a neighbour goes
                // down, which is the moment a player most wants the border to stay put
                display.setBlock(stillWanted ? CROSSABLE_BLOCK : CLOSED_BLOCK);
                display.setGlowColorOverride(stillWanted ? CROSSABLE_GLOW : CLOSED_GLOW);
                entry.setValue(new Standing(display, stillWanted));
            }
        }

        final List<Map.Entry<Marker, BlockDisplay>> grown = new ArrayList<>();
        for (final Map.Entry<Marker, Boolean> entry : wanted.entrySet()) {
            if (standing.containsKey(entry.getKey())) {
                continue;
            }
            final BlockDisplay display = raise(player, at.getWorld(), entry.getKey(), entry.getValue());
            standing.put(entry.getKey(), new Standing(display, entry.getValue()));
            grown.add(Map.entry(entry.getKey(), display));
        }
        if (standing.isEmpty()) {
            // Nothing left near this player, so their row goes too rather than sitting empty for as
            // long as they stay online
            this.shown.remove(player.getUniqueId());
        }
        if (!grown.isEmpty()) {
            // A tick later, so the client has been told the marker exists at no size before it is
            // told what size to become. Both in one tick and it simply appears at full size
            Bukkit.getScheduler().runTask(this.plugin,
                () -> grown.forEach(marker -> growIn(marker.getKey(), marker.getValue())));
        }
    }

    /**
     * Which markers should be standing, where, and what colour.
     *
     * <p>Every face in range gets its length of ground line; the ones sitting on the post spacing get
     * a post as well, and a half-bar hanging below it where that post has no floor under it. Then the
     * risers, which are the only part that needs more than one face to work out, so they are found
     * once the footings are all known.</p>
     *
     * <p>The cap is spent nearest first. The faces arrive sorted, so stopping at the limit keeps the
     * markers closest to the player, which are the ones they are looking at - a jagged outline can
     * otherwise spend the whole budget on faces off to the side. The footings are kept in that same
     * order, so if the pieces run out it is the risers furthest away that go without.</p>
     */
    private static Map<Marker, Boolean> wanted(final World world, final int playerY,
                                               final List<DrawnFace> faces) {
        final Map<Marker, Boolean> wanted = new HashMap<>();
        final Map<Run, Footing> footings = new LinkedHashMap<>();
        final Map<Run, Boolean> crossable = new HashMap<>();
        int drawnFaces = 0;
        for (final DrawnFace drawn : faces) {
            final EdgeSighting face = drawn.face();
            if (face.distance() > SPAWN_RADIUS || drawnFaces == MAX_FACES
                || wanted.size() >= MAX_MARKERS) {
                // Sorted nearest first, so everything past here is further still
                break;
            }
            drawnFaces++;
            final Run run = run(face);
            final Footing footing = footing(world, face.insideX(), face.insideZ(), playerY);
            footings.put(run, footing);
            crossable.put(run, drawn.crossable());
            wanted.put(run.marker(Kind.LINE, footing.base(), 0), drawn.crossable());
            if (postHere(face)) {
                wanted.put(run.marker(Kind.POST, footing.base(), 0), drawn.crossable());
                if (!footing.grounded()) {
                    // The post is buried in a hillside or hanging in the air, so nothing shows where
                    // the ground it belongs to is. The half-bar points at it
                    wanted.put(run.marker(Kind.UNDER, footing.base(), 0), drawn.crossable());
                }
            }
        }
        risers(footings, crossable, wanted);
        return wanted;
    }

    /**
     * Joins each length of ground line to the next one along, wherever they stand at different
     * heights.
     *
     * <p>A riser belongs to the lower-numbered of the pair and stands on the boundary the two share,
     * so the same step is only ever drawn once however the two faces are ordered. It spans from the
     * lower base to the top of the upper strip, which is what makes a stair read as one line stepping
     * rather than as two lines that happen to be near each other.</p>
     *
     * <p>Only faces running along the same seam are joined. Where the outline turns a corner the two
     * lengths meet at right angles rather than end to end, and a riser drawn between them would sit
     * across the corner rather than in it.</p>
     */
    private static void risers(final Map<Run, Footing> footings, final Map<Run, Boolean> crossable,
                               final Map<Marker, Boolean> wanted) {
        for (final Map.Entry<Run, Footing> entry : footings.entrySet()) {
            if (wanted.size() >= MAX_MARKERS) {
                break;
            }
            final Footing beyond = footings.get(entry.getKey().next());
            if (beyond == null || beyond.base() == entry.getValue().base()) {
                continue;
            }
            final int lower = Math.min(entry.getValue().base(), beyond.base());
            final int step = Math.abs(entry.getValue().base() - beyond.base());
            wanted.put(entry.getKey().marker(Kind.RISER, lower, step), crossable.get(entry.getKey()));
        }
    }

    /**
     * Whether this face is one of the ones that carries a post.
     *
     * <p>Taken from the world coordinate the seam runs along, not from a count of faces handed over,
     * because that count starts wherever the player happens to be standing: posts would then shuffle
     * along the line as somebody walked beside it, spawning and despawning the whole way.</p>
     */
    private static boolean postHere(final EdgeSighting face) {
        final int alongRun = face.alongX() ? face.insideZ() : face.insideX();
        return Math.floorMod(alongRun, POST_SPACING) == 0;
    }

    private static Run run(final EdgeSighting face) {
        return new Run(face.insideX(), face.insideZ(), face.seamCoordinate(), face.alongX());
    }

    /**
     * Where a marker at this column stands, and whether it is standing on anything.
     *
     * <p>Where the column is solid all the way up - a border running into a hillside, or through a
     * wall somebody has built on it - it stands at the player's own level instead. It is inside rock
     * there and invisible as a block, which is exactly the case the glow is for.</p>
     */
    private static Footing footing(final World world, final int x, final int z, final int playerY) {
        final int floor = BorderFooting.floorUnder(world, x, z, playerY);
        if (floor == BorderFooting.SOLID) {
            return new Footing(playerY, false);
        }
        final int base = BorderFooting.follow(floor, playerY);
        // Grounded only where the marker is on the floor that was found. Lifted to meet somebody
        // flying, it is standing on nothing, and it should say so
        return new Footing(base, base == floor);
    }

    private BlockDisplay raise(final Player player, final World world, final Marker marker,
                               final boolean crossable) {
        final Location standing = marker.standsAt(world);
        final BlockDisplay display = world.spawn(standing, BlockDisplay.class, spawned -> {
            // Before it is added to the world, so it is never briefly visible to everybody nearby
            spawned.setVisibleByDefault(false);
            // Or a crash writes the whole border into the region file, to be found by whoever loads
            // that chunk next
            spawned.setPersistent(false);
            spawned.setBlock(crossable ? CROSSABLE_BLOCK : CLOSED_BLOCK);
            // Outlined, and the outline is drawn through whatever is in front of it. Without this a
            // border is only ever a surprise to somebody digging towards it, or walking a tunnel
            // beside it, or in a building standing on it
            spawned.setGlowing(true);
            spawned.setGlowColorOverride(crossable ? CROSSABLE_GLOW : CLOSED_GLOW);
            spawned.setBrightness(FULLY_LIT);
            spawned.setViewRange(VIEW_RANGE);
            // It has depth and sits on a line, so it must stay where the line is rather than turning
            // to face whoever is looking at it
            spawned.setBillboard(Display.Billboard.FIXED);
            // Otherwise every marker puts a dark blot on the ground beneath it
            spawned.setShadowRadius(0.0F);
            spawned.setDisplayWidth(CULLING_WIDTH);
            spawned.setDisplayHeight(CULLING_HEIGHT);
            spawned.setInterpolationDuration(FADE_TICKS);
            spawned.setTransformation(marker.shape(0.0F));
        });
        player.showEntity(this.plugin, display);
        this.live.add(display);
        return display;
    }

    private void growIn(final Marker marker, final BlockDisplay display) {
        if (!display.isValid()) {
            return;
        }
        display.setInterpolationDelay(0);
        display.setTransformation(marker.shape(1.0F));
    }

    private void fadeOut(final Marker marker, final BlockDisplay display) {
        if (!display.isValid()) {
            this.live.remove(display);
            return;
        }
        display.setInterpolationDelay(0);
        display.setTransformation(marker.shape(0.0F));
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            display.remove();
            this.live.remove(display);
        }, FADE_TICKS);
    }

    /**
     * Takes this player's markers away at once, with no fade.
     *
     * <p>Called when they are handed over or disconnect, and in both cases there may be no next tick
     * for them - one left mid-fade would be an invisible entity nobody ever removes.</p>
     */
    @Override
    public void forget(final UUID playerUuid) {
        final Map<Marker, Standing> standing = this.shown.remove(playerUuid);
        if (standing == null) {
            return;
        }
        for (final Standing marker : standing.values()) {
            marker.display().remove();
            this.live.remove(marker.display());
        }
    }

    @Override
    public void close() {
        for (final BlockDisplay display : this.live) {
            display.remove();
        }
        this.live.clear();
        this.shown.clear();
    }

    /**
     * What a marker is. All four are the same thin coloured shape in different proportions, which is
     * what keeps them reading as one line rather than as a collection of decorations.
     */
    private enum Kind {
        /** A block of the strip lying along the seam. */
        LINE,
        /** A stake standing on the strip. */
        POST,
        /** Half a stake hanging below the strip, where the strip is not on the ground. */
        UNDER,
        /** The join between two lengths of strip at different heights. */
        RISER
    }

    /**
     * Where a column's markers stand, and whether that is real ground.
     *
     * @param grounded the floor was found and the marker is on it, rather than the marker having been
     *     lifted to meet somebody in the air or left at their own level inside rock
     */
    private record Footing(int base, boolean grounded) {
    }

    /**
     * One face of the border, as the thing the markers belonging to it are named after.
     *
     * <p>The seam is part of its identity, not decoration: the two sides of one line are two faces,
     * and a shard can own the ground on either side of it somewhere else along that line.</p>
     */
    private record Run(int insideX, int insideZ, int seam, boolean alongX) {

        /**
         * The next face along the same seam, which is the one a riser would join this to.
         */
        Run next() {
            return this.alongX
                ? new Run(this.insideX, this.insideZ + 1, this.seam, this.alongX)
                : new Run(this.insideX + 1, this.insideZ, this.seam, this.alongX);
        }

        Marker marker(final Kind kind, final int base, final int step) {
            return new Marker(this.insideX, this.insideZ, this.seam, this.alongX, base, kind, step);
        }
    }

    /**
     * A marker in the world, and the colour it was last given. Kept so an unchanged one is left
     * alone: setting the block every pass would be a packet per marker twice a second saying nothing
     * new.
     */
    private record Standing(BlockDisplay display, boolean crossable) {
    }

    /**
     * One marker: which face it belongs to, which of the four things it is, and the height it stands
     * at.
     *
     * <p>The kind is part of its identity because a length of strip, the post on it, the half-bar
     * under that post and the riser off its end all share a column and are all wanted at once. So is
     * the base, so that a marker whose floor has changed is a different marker and is raised
     * again - which is what lets the line follow a player down into a cave or up onto a roof.</p>
     *
     * @param insideX the block on our side of the face
     * @param seam the grid line the marker stands on
     * @param alongX whether the seam is crossed in x, so the line runs in z
     * @param base the y the marker stands at, which for a riser is the lower of the two it joins
     * @param step how many blocks a riser has to climb; zero for everything else
     */
    private record Marker(int insideX, int insideZ, int seam, boolean alongX, int base, Kind kind,
                          int step) {

        /**
         * A block display draws its block from its own position outwards, so a marker is placed at
         * the low corner of the volume it should fill rather than at the middle of it.
         *
         * <p>The strip runs the full block along the seam, so consecutive lengths meet. A post is
         * centred on its own block, so it reads as a stake on the line rather than as a piece of it.
         * A riser sits on the far boundary of its block, which is the seam between its own length of
         * strip and the next one along - the one place a step in the ground leaves a gap.</p>
         */
        Location standsAt(final World world) {
            // A riser is as thin as the strip it joins, not as thick as a post
            final float wide = this.kind == Kind.POST || this.kind == Kind.UNDER
                ? POST_THICKNESS : LINE_WIDTH;
            final double along = switch (this.kind) {
                case LINE -> 0.0D;
                case RISER -> 1.0D - LINE_WIDTH / 2.0D;
                default -> 0.5D - POST_THICKNESS / 2.0D;
            };
            return this.alongX
                ? new Location(world, this.seam - wide / 2.0F, this.base, this.insideZ + along)
                : new Location(world, this.insideX + along, this.base, this.seam - wide / 2.0F);
        }

        /**
         * @param grown 0 while it is appearing or leaving, 1 once it is fully up. An UNDER hangs from
         *     the strip rather than standing on the ground, so it is translated down by its own
         *     height - which also means it grows downwards out of the line instead of upwards off
         *     whatever is below it
         */
        Transformation shape(final float grown) {
            final float full = switch (this.kind) {
                case LINE -> LINE_HEIGHT;
                case POST -> POST_HEIGHT;
                case UNDER -> UNDER_HEIGHT;
                case RISER -> this.step + LINE_HEIGHT;
            };
            final float height = full * grown;
            final Vector3f scale = switch (this.kind) {
                case LINE -> this.alongX
                    ? new Vector3f(LINE_WIDTH, height, 1.0F)
                    : new Vector3f(1.0F, height, LINE_WIDTH);
                case RISER -> new Vector3f(LINE_WIDTH, height, LINE_WIDTH);
                default -> new Vector3f(POST_THICKNESS, height, POST_THICKNESS);
            };
            final Vector3f offset = this.kind == Kind.UNDER
                ? new Vector3f(0.0F, -height, 0.0F)
                : new Vector3f();
            return new Transformation(offset, new AxisAngle4f(), scale, new AxisAngle4f());
        }
    }
}
