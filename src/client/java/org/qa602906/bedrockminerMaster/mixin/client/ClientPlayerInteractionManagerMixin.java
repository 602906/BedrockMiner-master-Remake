package org.qa602906.bedrockminerMaster.mixin.client;

import org.qa602906.bedrockminerMaster.BedrockminerMaster;
import org.qa602906.bedrockminerMaster.client.BedrockminerMasterClient;
import org.qa602906.bedrockminerMaster.client.Miner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class ClientPlayerInteractionManagerMixin {

    @Shadow @Final private Minecraft minecraft;
    private Miner m;
    private boolean clickMine, interacting = false;
    // 按住左键时 Minecraft 会每隔约 10 tick 重复调用 startDestroyBlock，
    // 启动失败（缺镐/缺活塞）就会反复弹提示。这里记住本次按下已经失败过，
    // 直到玩家松手才允许再次尝试。
    // While the attack key is held, Minecraft re-invokes startDestroyBlock roughly every 10 ticks.
    // A failed start (missing pickaxe / pistons) would therefore spam its message, so remember the
    // failure and only allow a new attempt after the button is released.
    private boolean startFailed = false;

    @Inject(at=@At("HEAD"), method="startDestroyBlock", cancellable = true)
    public void bedrockminer$onStartDestroyBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir){
        if(clickMine){
            if(m.isRunning() || startFailed){
                cir.setReturnValue(false);
                return;
            }
            if(m.getTargetBlocks().contains(minecraft.level.getBlockState(pos).getBlock())){
                m.start(pos,direction);
                if(!m.isRunning())
                    startFailed = true;
                cir.setReturnValue(false);
            }else{
                BedrockminerMaster.log("not mineable | clicked="
                        + minecraft.level.getBlockState(pos).getBlock().getName().getString()
                        + " @ " + pos
                        + " | targets=" + m.getTargetBlocks().stream().map(b -> b.getName().getString()).toList());
            }
        }
    }

    @Inject(at=@At("TAIL"), method="tick")
    public void bedrockminer$tick(CallbackInfo ci){
        if(m==null && minecraft.player!=null)
            m = new Miner(minecraft.player);
        // 松手后清掉失败标记，允许下一次点击重试
        // Clear the failure flag once the button is released so the next click can retry.
        if(startFailed && !minecraft.options.keyAttack.isDown())
            startFailed = false;
        m.verifyTick();
        if(m.isRunning()) {
            try {
                m.tick();
            }catch(Exception e){
                BedrockminerMaster.log("tick threw, resetting miner:", e);
                m.reset();
            }
        }
        if(BedrockminerMasterClient.toggleMiner.consumeClick()) {
            if(clickMine) {
                clickMine = false;
                m=null;
            }
            else{
                clickMine = true;
            }
            minecraft.player.sendOverlayMessage(Component.translatable(clickMine
                    ? "bedrockminer-master.msg.toggle_on"
                    : "bedrockminer-master.msg.toggle_off"));
        }

        if(interacting)
            interacting = false;
    }

    @Inject(at=@At("HEAD"), method="useItem", cancellable = true)
    public void bedrockminer$InteractItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir){
        if(!interacting && clickMine && minecraft.hitResult instanceof BlockHitResult) {
            interacting = !interacting;
            BlockHitResult hit = (BlockHitResult) minecraft.hitResult;
            Block toAdd = hit.getBlockPos() != null ? 
                player.level().getBlockState(hit.getBlockPos()).getBlock() : null;
            if (toAdd != null && m.getTargetBlocks().contains(toAdd)){
                m.removeTargetBlock(toAdd);
                player.sendSystemMessage(Component.translatable(
                        "bedrockminer-master.msg.target_removed", toAdd.getName()));
            }else if(toAdd != null){
                m.addTargetBlock(toAdd);
                player.sendSystemMessage(Component.translatable(
                        "bedrockminer-master.msg.target_added", toAdd.getName()));
            }
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}