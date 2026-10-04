package org.qa602906.bedrockminerMaster.client;

import org.qa602906.bedrockminerMaster.BedrockminerMaster;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.List;


public class Miner{

    private Task currentTask;
    private final LocalPlayer player;
    private final ClientPacketListener netHandler;
    private final MultiPlayerGameMode interactionManager;
    private Direction toFace;
    private boolean alreadyRunning;
    private BlockPos bedrockBlock;
    private BlockPos supportBlock;
    private BlockPos torchPos;
    private PistonPlacement pistonPlacement;
    private Item pistonType, pickaxeType;
    private boolean[] checks = {false,false}; //checklist for pistonplacement,torchplacement,
    private int failedCounter = -1;
    // 看门狗：任何一个任务状态停留超过 MAX_TASK_TICKS 就强制重置，避免卡死
    // Watchdog: force a reset if any task state lingers longer than MAX_TASK_TICKS ticks.
    private static final int MAX_TASK_TICKS = 60;
    private Task lastTask = null;
    private int taskTicks = 0;
    private int animationWait = 0;

    private final List<Item> allowedTools = List.of(Items.NETHERITE_PICKAXE,Items.DIAMOND_PICKAXE);
    private List<Item> allowedSupportBlocks = List.of(Items.SLIME_BLOCK, Items.NETHERRACK);
    private List<Block> allowedBlocksToMine = new ArrayList<>(List.of(Blocks.BEDROCK));

    public Miner(LocalPlayer player){
        this.player = player;
        this.netHandler = player.connection;
        this.interactionManager = Minecraft.getInstance().gameMode;
        this.alreadyRunning = false;
        this.currentTask = Task.NOTHING;
    }
    //-------------------- Miner ---------------------------------------
    public void tick(){
        // 看门狗：同一状态停留过久说明流程走死了，直接重置，保证玩家能再次点击重试
        // Watchdog: if a state is stuck for too long the flow is dead, so reset and let the player retry.
        if(currentTask == lastTask){
            if(++taskTicks > MAX_TASK_TICKS){
                BedrockminerMaster.log("watchdog: state " + currentTask + " stuck for "
                        + taskTicks + " ticks, forcing reset");
                this.reset();
                return;
            }
        }else{
            lastTask = currentTask;
            taskTicks = 0;
        }
        switch(currentTask){
            case INIT -> {
                if(failedCounter>0){
                    player.sendOverlayMessage(Component.translatable("bedrockminer-master.msg.no_piston_location"));
                    this.reset();
                    break;
                }
                if(pistonPlacement!=null)
                    torchPos = findRedstoneTorchPlace(pistonPlacement.pos(),pistonPlacement.dir());
                if(pistonPlacement!=null && torchPos!=null) {
                    this.toFace = pistonPlacement.dir().getOpposite();
                    this.currentTask = Task.ROTATEPLAYER;
                    break;
                }
                if(pistonPlacement ==null || torchPos ==null){
                    failedCounter++;
                }
            }
            case PLACEPISTON -> {
                placePiston(pistonPlacement.pos(),pistonPlacement.dir().getOpposite());
                if(torchPos!=null && pistonPlacement!=null) {
                    this.currentTask = Task.ROTATEPLAYER;
                    toFace = Direction.DOWN;
                    checks[0]=true;
                }
            }
            case REDSTONETORCH -> {
                if(supportBlock!=null){
                    selectItem(null,allowedSupportBlocks);
                    placeBlock(supportBlock);
                }
                if(torchPos!=null && checks[0] && !checks[1]) {
                    selectItem(null, Items.REDSTONE_TORCH);
                    placeBlock(torchPos);
                    checks[1] = true;
                    BlockPos dirPos = pistonPlacement.pos().subtract(new Vec3i(bedrockBlock.getX(), bedrockBlock.getY(), bedrockBlock.getZ()));
                    toFace = Direction.getNearest(dirPos.getX(),dirPos.getY(),dirPos.getZ(), null);
                    this.currentTask=Task.ROTATEPLAYER;
                }
            }
            case ROTATEPLAYER -> {
                if(toFace!=null) {
                    netHandler.send(
                            new ServerboundMovePlayerPacket.Rot(
                                    dirToYaw(toFace),
                                    dirToPitch(toFace),
                                    player.onGround(),
                                    player.horizontalCollision));
                    if(!checks[0] && !checks[1])
                        this.currentTask = Task.PLACEPISTON;
                    if(checks[0] && !checks[1]) {
                        this.currentTask = Task.REDSTONETORCH;
                        this.toFace = Direction.DOWN;
                    }
                    if(checks[0]&&checks[1])
                        this.currentTask = Task.SWITCHTOPICK;
                } else{
                    player.sendSystemMessage(Component.translatable("bedrockminer-master.msg.rotate_null",
                            checks[0] + "/" + checks[1]));
                    this.reset();
                }
            }
            case SWITCHTOPICK ->{
                toFace=pistonPlacement.dir().getOpposite();
                if(selectItem(efficiencyHolder(),pickaxeType)){
                    this.currentTask = Task.MINEPISTON;
                }else{
                    player.sendSystemMessage(Component.translatable("bedrockminer-master.msg.pickaxe_not_found"));
                    this.reset();
                }
            }
            case MINEPISTON -> {
                if(failedCounter>0 || this.pistonPlacement==null) {
                    this.reset();
                    return;
                }
                if(player.level().getBlockState(this.pistonPlacement.pos()).getBlock() == Blocks.MOVING_PISTON){
                    // 时间窗没赶上：不再提示，但要把已放下的活塞/火把/支撑安排补挖，避免留下残留方块
                    // Missed the timing window: no message any more, but schedule cleanup so the
                    // already placed piston / torch / support do not stay in the world.
                    this.scheduleCleanup();
                    failedCounter++;
                    break;
                }

                if(player.level().getBlockState(this.pistonPlacement.pos()).getValue(BlockStateProperties.EXTENDED)) {
                    // 活塞底座 extended=true 在伸出动画开始的瞬间就为真，此时头部还是 moving_piston。
                    // 必须等动画结束、头部固化成 piston_head 再动手。
                    // The base turns extended=true the moment the animation starts, while the head is
                    // still moving_piston. We must wait until it solidifies into piston_head.
                    BlockState headState = player.level().getBlockState(
                            this.pistonPlacement.pos().relative(this.pistonPlacement.dir()));
                    if(headState.getBlock() == Blocks.MOVING_PISTON){
                        // 正常情况下 2 tick 后头部就会固化；等太久说明卡住了，放弃本次
                        // Normally the head solidifies after 2 ticks; waiting much longer means it is stuck.
                        if(++animationWait > 10){
                            BedrockminerMaster.log("MINEPISTON animation never finished, giving up");
                            this.scheduleCleanup();
                            failedCounter++;
                        }
                        break;
                    }
                    if(headState.getBlock() != Blocks.PISTON_HEAD){
                        BedrockminerMaster.log("MINEPISTON head not piston_head, head=" + headState);
                        this.scheduleCleanup();
                        failedCounter++;
                        break;
                    }
                    BedrockminerMaster.log("MINEPISTON ready, head=" + headState);
                    mineBedrock();
                }else{
                    this.scheduleCleanup();
                    failedCounter++;
                    break;
                }
                if(this.supportBlock==null)
                    this.reset();
                else
                    this.currentTask = Task.MINESUPPORT;
                checks[0]=checks[1]=false;
            }
            case MINESUPPORT ->{
                breakBlock(supportBlock);
                this.reset();
            }
            case NOTHING -> {
                checks[0]=checks[1]=false;
                this.alreadyRunning=false;
                this.failedCounter = 0;
                this.bedrockBlock = null;
                this.supportBlock = null;
            }
        }
    }
    public void start(BlockPos bp, Direction offsetDir){
        // 每次尝试都强制回到干净状态，避免上一次的残留影响本次
        // Force a clean slate on every attempt so leftovers from the previous run cannot leak in.
        this.checks[0]=false;
        this.checks[1]=false;
        this.torchPos = null;
        this.supportBlock = null;
        this.pistonPlacement = null;
        this.toFace = null;
        this.failedCounter = -1;
        this.animationWait = 0;
        this.lastTask = null;
        this.taskTicks = 0;
        this.currentTask = Task.INIT;
        this.bedrockBlock = bp;
        this.alreadyRunning=true;
        this.pistonPlacement = new PistonPlacement(
                bp.relative(offsetDir),
                player.level().getBlockState(bp.relative(offsetDir).relative(Direction.UP)).isAir()?Direction.UP:canPistonExtend(bp.relative(offsetDir)));
        BlockPos pPos = bp.relative(offsetDir);
        BedrockminerMaster.log("start bedrock=" + bp + " clickedFace=" + offsetDir
                + " pistonPos=" + pPos + " pistonDir=" + this.pistonPlacement.dir()
                + " | abovePiston=" + player.level().getBlockState(pPos.relative(Direction.UP))
                + " | behindBedrock=" + player.level().getBlockState(bp.relative(offsetDir.getOpposite()))
                + " | bedrockUp=" + player.level().getBlockState(bp.relative(Direction.UP))
                + " | bedrockDown=" + player.level().getBlockState(bp.relative(Direction.DOWN)));
        if(this.pistonPlacement.pos().equals(player.blockPosition()) || player.level().isOutsideBuildHeight(this.pistonPlacement.pos()))
            this.pistonPlacement=null;
        if(!checkPickaxe()){
            player.sendSystemMessage(Component.translatable("bedrockminer-master.msg.no_efficiency_pickaxe"));
            this.reset();
            // 原版这里 reset() 之后还会继续往下跑，导致同时再弹一条“没有活塞”。
            // The original kept running after reset(), which also fired the "no pistons" message.
            return;
        }
        if(player.getInventory().contains(Items.PISTON.getDefaultInstance())){
            int s = player.getInventory().findSlotMatchingItem(Items.PISTON.getDefaultInstance());
            if(s>-1){
                if(player.getInventory().getItem(s).getCount()>=2){
                    this.pistonType = Items.PISTON;
                    this.currentTask = Task.INIT;
                    return;
                }
            }
        }
        if(player.getInventory().contains(Items.STICKY_PISTON.getDefaultInstance())){
            int s = player.getInventory().findSlotMatchingItem(Items.STICKY_PISTON.getDefaultInstance());
            if(s>-1){
                if(player.getInventory().getItem(s).getCount()>=2){
                    this.pistonType = Items.STICKY_PISTON;
                    this.currentTask = Task.INIT;
                }
            }
        }else{
            player.sendSystemMessage(Component.translatable("bedrockminer-master.msg.no_sticky_pistons"));
            this.reset();
        }
    }
    public boolean isRunning(){return this.alreadyRunning;}
    public void reset(){
        this.alreadyRunning=false;
        this.bedrockBlock = null;
        this.pistonPlacement = null;
        this.pistonType = null;
        this.torchPos = null;
        this.supportBlock = null;
        this.toFace = null;
        this.failedCounter = -1;
        this.currentTask = Task.NOTHING;
        this.checks[0]=false;
        this.checks[1]=false;
        this.animationWait = 0;
        this.lastTask = null;
        this.taskTicks = 0;
    }

    //-------------------- Inventory Actions -----------------------------------
    private Holder<Enchantment> efficiencyHolder(){
        return player.level().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.EFFICIENCY);
    }
    private boolean checkPickaxe(){
        Inventory inv = player.getInventory();
        int pickSlot = -1;
        ItemStack tempStack;
        for(int i=0;i<36;i++){
            tempStack = inv.getItem(i);
            if(EnchantmentHelper.getItemEnchantmentLevel(efficiencyHolder(),tempStack)==5 && allowedTools.contains(tempStack.getItem())) {
                pickSlot = i;
                break;
            }
        }
        if(pickSlot==-1) {
            player.sendOverlayMessage(Component.translatable("bedrockminer-master.msg.no_pickaxe"));
            return false;
        }
        pickaxeType = inv.getItem(pickSlot).getItem();
        return true;
    }
    private boolean selectItem(Holder<Enchantment> e, List<Item> items){
        for (Item i : items) {
            if(selectItem(e,i))
                return true;
        }
        return false;
    }
    private boolean selectItem(Holder<Enchantment> e, Item item){
        Inventory inv = player.getInventory();
        ItemStack iS;
        int slot = -1;
        for(int i=0;i<36;i++){
            iS = inv.getItem(i);
            if(iS.is(item)) {
                if(e==null) {
                    slot = i;
                    break;
                } else {
                    if(EnchantmentHelper.getItemEnchantmentLevel(e,iS)==5){
                        slot = i;
                        break;
                    }
                }
            }
        }
        if(slot < 0) {
            BedrockminerMaster.log("selectItem: NOT FOUND item=" + item + " ench=" + e);
            return false;
        }
        else if(Inventory.isHotbarSlot(slot))
            inv.setSelectedSlot(slot);
        else
            interactionManager.handleContainerInput(
                    player.containerMenu.containerId,
                    slot,
                    inv.getSelectedSlot(),
                    ContainerInput.SWAP,
                    player);
        BedrockminerMaster.log("selectItem: item=" + item + " slot=" + slot
                + " selected=" + inv.getSelectedSlot()
                + " held=" + player.getMainHandItem()
                + " menuId=" + player.containerMenu.containerId);
        return true;
    }

    //-------------------- Placement Checks ------------------------------------
    private Direction canPistonExtend(BlockPos pistonBody){
        for(Direction d: Direction.values()){
            if(d.equals(Direction.UP))
                continue;
            if(player.level().getBlockState(pistonBody.relative(d)).canBeReplaced()){
                return d;
            }
        }
        return null;
    }
    private BlockPos findRedstoneTorchPlace(BlockPos pistonBody, Direction facing){
        for(Direction d:Direction.values()){
            if(d.equals(facing) || d.equals(Direction.UP) || pistonBody.relative(d).equals(bedrockBlock)) {
                continue;
            }
            BlockPos probePos = pistonBody.relative(d);
            BlockState probeState = player.level().getBlockState(probePos);
            if(probeState.isAir()) {
                if (player.level().getBlockState(probePos.below()).isFaceSturdy(player.level(), probePos.below(), Direction.UP))
                    return probePos;
                else if (player.level().getBlockState(probePos.relative(Direction.DOWN)).isAir() && !probePos.relative(Direction.DOWN).equals(player.blockPosition().offset(0,1,0)) && !probePos.relative(Direction.DOWN).equals(player.blockPosition()) && probePos.getY() > -63 && !player.level().isOutsideBuildHeight(probePos)) {
                    supportBlock = probePos.relative(Direction.DOWN);
                    return probePos;
                }
            }
        }
        return null;
    }
    //-------------------- World Interactions ----------------------------------
    private void mineBedrock(){
        BlockPos pistonPos = this.pistonPlacement.pos();
        BlockPos supportPos = this.supportBlock;
        BedrockminerMaster.log("mineBedrock piston=" + player.level().getBlockState(pistonPos)
                + " pistonHead=" + player.level().getBlockState(pistonPos.relative(this.pistonPlacement.dir()))
                + " torch=" + player.level().getBlockState(this.torchPos)
                + " bedrock=" + player.level().getBlockState(this.bedrockBlock));
        breakBlock(this.torchPos);
        breakBlock(pistonPos);
        replacePiston(pistonPos);
        if(this.supportBlock!=null) {
            breakBlock(this.supportBlock);
            this.supportBlock=null;
        }
        breakBlock(pistonPos);
        BedrockminerMaster.log("mineBedrock done pistonNow=" + player.level().getBlockState(pistonPos)
                + " bedrockNow=" + player.level().getBlockState(this.bedrockBlock));
        this.verifyBedrock = this.bedrockBlock;
        this.verifyPistonPos = pistonPos;
        this.verifyCountdown = 20;
        this.cleanupPistonPos = pistonPos;
        this.cleanupTorchPos = this.torchPos;
        this.cleanupSupportPos = supportPos;
        this.cleanupCountdown = 20;
        this.pistonPlacement=null;
        this.torchPos=null;
    }
    private BlockPos verifyBedrock, verifyPistonPos;
    private int verifyCountdown = -1;
    private BlockPos cleanupPistonPos, cleanupTorchPos, cleanupSupportPos;
    private int cleanupCountdown = -1;

    /**
     * 失败退出时安排一次延迟补挖，把本次已经放下的活塞/火把/支撑清掉。
     * checks[0] 表示活塞已放、checks[1] 表示火把(+支撑)已放，只有确实放过的才清。
     * Schedules a delayed cleanup for a failed attempt so blocks already placed during this run are
     * removed. checks[0] means the piston was placed, checks[1] the torch (and support).
     */
    private void scheduleCleanup(){
        BlockPos pistonPos = (this.checks[0] && this.pistonPlacement != null) ? this.pistonPlacement.pos() : null;
        BlockPos torchPos = (this.checks[1] && this.torchPos != null) ? this.torchPos : null;
        BlockPos supportPos = torchPos != null ? this.supportBlock : null;
        if(pistonPos == null && torchPos == null)
            return;
        this.cleanupPistonPos = pistonPos;
        this.cleanupTorchPos = torchPos;
        this.cleanupSupportPos = supportPos;
        this.cleanupCountdown = 20;
    }

    private boolean stillThere(BlockPos p, Block... types){
        if(p == null)
            return false;
        Block b = player.level().getBlockState(p).getBlock();
        for(Block t : types){
            if(b == t)
                return true;
        }
        return false;
    }
    public void verifyTick(){
        if(verifyCountdown > 0 && --verifyCountdown == 0){
            BedrockminerMaster.log("VERIFY(20t later) bedrock=" + player.level().getBlockState(verifyBedrock)
                    + " piston=" + player.level().getBlockState(verifyPistonPos));
        }
        if(cleanupCountdown > 0 && --cleanupCountdown == 0){
            StringBuilder sb = new StringBuilder();
            if(stillThere(cleanupPistonPos, Blocks.PISTON, Blocks.STICKY_PISTON)){
                sb.append(" piston@").append(cleanupPistonPos);
                breakBlock(cleanupPistonPos);
            }
            if(stillThere(cleanupTorchPos, Blocks.REDSTONE_TORCH)){
                sb.append(" torch@").append(cleanupTorchPos);
                breakBlock(cleanupTorchPos);
            }
            if(stillThere(cleanupSupportPos, Blocks.NETHERRACK, Blocks.SLIME_BLOCK)){
                sb.append(" support@").append(cleanupSupportPos);
                breakBlock(cleanupSupportPos);
            }
            BedrockminerMaster.log("cleanup pass, re-mined leftovers:"
                    + (sb.length()==0 ? " none" : sb.toString()));
            cleanupPistonPos = null;
            cleanupTorchPos = null;
            cleanupSupportPos = null;
        }
    }
    private void placePiston(BlockPos pistonPos, Direction dir){
        if(pistonPos==null || dir == null)
            return;
        selectItem(null,pistonType);
        BedrockminerMaster.log("placePiston pos=" + pistonPos + " dir=" + dir
                + " stateBefore=" + player.level().getBlockState(pistonPos));
        net.minecraft.world.InteractionResult res = interactionManager.useItemOn(
                player,
                player.getUsedItemHand(),
                new BlockHitResult(Vec3.atCenterOf(pistonPos),dir,pistonPos,true));
        BedrockminerMaster.log("placePiston result=" + res
                + " stateAfter=" + player.level().getBlockState(pistonPos));
    }
    private void replacePiston(BlockPos pistonPos){
        if(pistonPos==null)
            return;
        int oldSlot = player.getInventory().getSelectedSlot();
        selectItem(null,pistonType);
        interactionManager.useItemOn(
                player,
                player.getUsedItemHand(),
                new BlockHitResult(Vec3.atCenterOf(pistonPos),
                        pistonPlacement.dir().getOpposite(),
                        pistonPos,
                        true));
        player.getInventory().setSelectedSlot(oldSlot);
    }
    private void placeBlock(BlockPos bp){
        BedrockminerMaster.log("placeBlock bp=" + bp
                + " stateBefore=" + player.level().getBlockState(bp));
        net.minecraft.world.InteractionResult res = interactionManager.useItemOn(
                player,
                player.getUsedItemHand(),
                new BlockHitResult(Vec3.atCenterOf(bp),Direction.UP,bp,true));
        BedrockminerMaster.log("placeBlock result=" + res
                + " stateAfter=" + player.level().getBlockState(bp));
    }
    private void breakBlock(BlockPos blockPos){
        netHandler.send(
                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,blockPos,player.getDirection()));
        netHandler.send(
                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,blockPos,player.getDirection()));
    }
    //-------------------- Util ------------------------------------------------
    public List<Block> getTargetBlocks(){return allowedBlocksToMine;}
    public void addTargetBlock(Block b){allowedBlocksToMine.add(b);}
    public void removeTargetBlock(Block b){allowedBlocksToMine.remove(b);}
    private float dirToYaw(Direction d){
        return switch (d) {
            case NORTH -> 180.0f;
            case EAST -> 270.0f;
            case SOUTH -> 0.0f;
            case WEST -> 90.0f;
            default -> player.getYRot();
        };
    }
    private float dirToPitch(Direction d){
        return switch (d) {
            case UP -> -90.0f;
            case DOWN -> 90.0f;
            default -> player.getXRot();
        };
    }
    private record PistonPlacement(BlockPos pos, Direction dir){}
    private enum Task{
        INIT,
        PLACEPISTON,
        REDSTONETORCH,
        ROTATEPLAYER,
        SWITCHTOPICK,
        MINEPISTON,
        MINESUPPORT,
        NOTHING
    }
}