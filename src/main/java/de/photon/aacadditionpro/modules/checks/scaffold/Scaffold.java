package de.photon.aacadditionpro.modules.checks.scaffold;

import de.photon.aacadditionpro.modules.ModuleLoader;
import de.photon.aacadditionpro.modules.ViolationModule;
import de.photon.aacadditionpro.user.User;
import de.photon.aacadditionpro.user.data.DataKey;
import de.photon.aacadditionpro.user.data.TimestampKey;
import de.photon.aacadditionpro.user.data.batch.ScaffoldBatch;
import de.photon.aacadditionpro.util.config.LoadFromConfiguration;
import de.photon.aacadditionpro.util.inventory.InventoryUtil;
import de.photon.aacadditionpro.util.violationlevels.Flag;
import de.photon.aacadditionpro.util.violationlevels.ViolationLevelManagement;
import de.photon.aacadditionpro.util.violationlevels.ViolationManagement;
import de.photon.aacadditionpro.util.world.BlockUtil;
import de.photon.aacadditionpro.util.world.InternalPotion;
import de.photon.aacadditionpro.util.world.LocationUtil;
import de.photon.aacadditionpro.util.world.MaterialUtil;
import lombok.Getter;
import lombok.val;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Objects;

@Getter
public class Scaffold extends ViolationModule implements Listener
{
    private final ScaffoldAngle scaffoldAngle = new ScaffoldAngle(this.getConfigString());

    private final ScaffoldJumping scaffoldJumping = new ScaffoldJumping(this.getConfigString());

    private final ScaffoldPosition scaffoldPosition = new ScaffoldPosition(this.getConfigString());

    private final ScaffoldRotationFastChange scaffoldRotationFastChange = new ScaffoldRotationFastChange(this.getConfigString());
    private final ScaffoldRotationDerivative scaffoldRotationDerivative = new ScaffoldRotationDerivative(this.getConfigString());
    private final ScaffoldRotationSecondDerivative scaffoldRotationSecondDerivative = new ScaffoldRotationSecondDerivative(this.getConfigString());

    private final ScaffoldSafewalkPosition scaffoldSafewalkPosition = new ScaffoldSafewalkPosition(this.getConfigString());
    private final ScaffoldSafewalkTiming scaffoldSafewalkTiming = new ScaffoldSafewalkTiming(this.getConfigString());

    private final ScaffoldSprinting scaffoldSprinting = new ScaffoldSprinting(this.getConfigString());

    @LoadFromConfiguration(configPath = ".cancel_vl")
    private int cancelVl;
    @LoadFromConfiguration(configPath = ".timeout")
    private int timeout;

    public Scaffold()
    {
        super("Scaffold");
    }

    // ------------------------------------------- BlockPlace Handling ---------------------------------------------- //

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreBlockPlace(final BlockPlaceEvent event)
    {
        val user = User.getUser(event.getPlayer());
        if (User.isUserInvalid(user, this)) return;

        // To prevent too fast scaffolding -> Timeout
        if (user.getTimestampMap().at(TimestampKey.SCAFFOLD_TIMEOUT).recentlyUpdated(timeout)) {
            event.setCancelled(true);
            InventoryUtil.syncUpdateInventory(user.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event)
    {
        val user = User.getUser(event.getPlayer());
        if (User.isUserInvalid(user, this)) return;

        val blockPlaced = event.getBlockPlaced();

        // Short distance between player and the block (at most 2 Blocks)
        if (LocationUtil.areLocationsInRange(user.getPlayer().getLocation(), blockPlaced.getLocation(), 4D) &&
            // Not flying
            !user.getPlayer().isFlying() &&
            // Above the block
            user.getPlayer().getLocation().getY() > blockPlaced.getY() &&
            // Check if this check applies to the block
            blockPlaced.getType().isSolid() &&
            // Ladders and Vines are prone to false positives as they can be used to place blocks immediately after placing
            // them, therefore almost doubling the placement speed. However they can only be placed one at a time, which
            // allows simply ignoring them.
            event.getBlockPlaced().getType() != Material.LADDER && event.getBlockPlaced().getType() != Material.VINE &&
            // Check if the block is placed against one block face only, also implies no blocks above and below.
            // Only one block that is not a liquid is allowed (the one which the Block is placed against).
            BlockUtil.countBlocksAround(blockPlaced, BlockUtil.ALL_FACES, MaterialUtil.LIQUIDS) == 1 &&
            // In between check to make sure it is somewhat a scaffold movement as the buffering does not work.
            BlockUtil.HORIZONTAL_FACES.contains(event.getBlock().getFace(event.getBlockAgainst())))
        {

            val lastScaffoldBlock = user.getScaffoldBatch().peekLastAdded().getBlock();
            // This checks if the block was placed against the expected block for scaffolding.
            val newScaffoldLocation = !Objects.equals(lastScaffoldBlock, event.getBlockAgainst()) || !BlockUtil.isNext(lastScaffoldBlock, event.getBlockPlaced(), BlockUtil.HORIZONTAL_FACES);

            // ---------------------------------------------- Average ---------------------------------------------- //

            if (newScaffoldLocation) user.getScaffoldBatch().clear();

            user.getScaffoldBatch().addDataPoint(new ScaffoldBatch.ScaffoldBlockPlace(event.getBlockPlaced(),
                                                                                      event.getBlockPlaced().getFace(event.getBlockAgainst()),
                                                                                      InternalPotion.SPEED.getPotionEffect(event.getPlayer()),
                                                                                      event.getPlayer().getLocation(),
                                                                                      user.hasSneakedRecently(175)));

            // --------------------------------------------- Rotations ---------------------------------------------- //

            int vl = this.scaffoldAngle.getApplyingConsumer().applyAsInt(user, event);
            vl += this.scaffoldJumping.getApplyingConsumer().applyAsInt(user, event);
            vl += this.scaffoldPosition.getApplyingConsumer().applyAsInt(user, event);

            // All these checks may have false positives in new situations.
            if (!newScaffoldLocation) {
                val angleInformation = user.getLookPacketData().getAngleInformation();

                val rotationVl = this.scaffoldRotationFastChange.getApplyingConsumer().applyAsInt(user) +
                                 this.scaffoldRotationDerivative.getApplyingConsumer().applyAsInt(user, angleInformation[0]) +
                                 this.scaffoldRotationSecondDerivative.getApplyingConsumer().applyAsInt(user, angleInformation[1]);

                if (rotationVl > 0) {
                    if (user.getDataMap().getCounter(DataKey.CounterKey.SCAFFOLD_ROTATION_FAILS).incrementCompareThreshold()) vl += rotationVl;
                } else user.getDataMap().getCounter(DataKey.CounterKey.SCAFFOLD_ROTATION_FAILS).decrementAboveZero();

                vl += this.scaffoldSafewalkPosition.getApplyingConsumer().applyAsInt(user, event);
                vl += this.scaffoldSafewalkTiming.getApplyingConsumer().applyAsInt(user);
                vl += this.scaffoldSprinting.getApplyingConsumer().applyAsInt(user);
            }

            if (vl > 0) {
                this.getManagement().flag(Flag.of(event.getPlayer()).setCancelAction(cancelVl, () -> {
                    event.setCancelled(true);
                    user.getTimestampMap().at(TimestampKey.SCAFFOLD_TIMEOUT).update();
                    InventoryUtil.syncUpdateInventory(user.getPlayer());
                }));
            }
        }
    }

    @Override
    protected ModuleLoader createModuleLoader()
    {
        val batchProcessor = new ScaffoldAverageBatchProcessor(this);
        return ModuleLoader.builder(this)
                           .batchProcessor(batchProcessor)
                           .build();
    }

    @Override
    protected ViolationManagement createViolationManagement()
    {
        return ViolationLevelManagement.builder(this).withDecay(80, 1).build();
    }
}